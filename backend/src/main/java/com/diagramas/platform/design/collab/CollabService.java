package com.diagramas.platform.design.collab;

import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.design.collab.CollabMessages.CollabMessage;
import com.diagramas.platform.design.collab.CollabMessages.Type;
import com.diagramas.platform.design.collab.CollabPort.Participant;
import com.diagramas.platform.design.collab.CollabPort.SessionCleanup;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.diagramas.platform.design.service.DiagramService;
import com.diagramas.platform.design.service.DiagramService.OperationResult;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

/** CU-17: orquesta join/leave, locks por elemento y operaciones (flujo 9.1 paso 4 + 10.5). */
@Service
public class CollabService {

    private static final Logger log = LoggerFactory.getLogger(CollabService.class);

    private final CollabPort collab;
    private final DiagramService diagrams;
    private final DiagramOperationApplier applier;
    private final UserRepository users;
    private final SimpMessagingTemplate messaging;

    public CollabService(
            CollabPort collab,
            DiagramService diagrams,
            DiagramOperationApplier applier,
            UserRepository users,
            SimpMessagingTemplate messaging) {
        this.collab = collab;
        this.diagrams = diagrams;
        this.applier = applier;
        this.users = users;
        this.messaging = messaging;
    }

    public void join(AuthPrincipal p, UUID diagramId, String sessionId) {
        requireAnyRole(p, AuthPrincipal.DESIGNER, AuthPrincipal.DEVELOPER);
        diagrams.get(p, diagramId); // 404 si es de otra empresa
        String username = usernameOf(p);
        List<Participant> current = collab.join(diagramId, sessionId, p.userId(), username);
        broadcast(new CollabMessage(Type.JOIN, diagramId, p.userId(), username, null, null, null, now(), current));
    }

    public void leave(AuthPrincipal p, UUID diagramId, String sessionId) {
        publishCleanup(collab.leave(sessionId));
    }

    public void lock(AuthPrincipal p, UUID diagramId, String elementId, String sessionId) {
        requireAnyRole(p, AuthPrincipal.DESIGNER);
        diagrams.get(p, diagramId);
        requireElement(elementId);
        String username = usernameOf(p);
        collab.ensurePresence(diagramId, sessionId, p.userId(), username);
        collab.lock(sessionId, diagramId, elementId, p.userId());
        broadcast(new CollabMessage(Type.LOCK, diagramId, p.userId(), username, elementId, null, null, now(), null));
    }

    public void unlock(AuthPrincipal p, UUID diagramId, String elementId, String sessionId) {
        requireAnyRole(p, AuthPrincipal.DESIGNER);
        diagrams.get(p, diagramId);
        requireElement(elementId);
        String username = usernameOf(p);
        collab.ensurePresence(diagramId, sessionId, p.userId(), username);
        collab.unlock(sessionId, diagramId, elementId, p.userId());
        broadcast(new CollabMessage(Type.UNLOCK, diagramId, p.userId(), username, elementId, null, null, now(), null));
    }

    /**
     * Flujo de una operación: validar (applier) → lock del elemento → aplicar serializado por diagrama
     * (version++) → difundir → liberar lock. Cualquier fallo antes de aplicar deja el diagrama intacto.
     */
    public void operate(AuthPrincipal p, UUID diagramId, JsonNode operation, String sessionId) {
        requireAnyRole(p, AuthPrincipal.DESIGNER);
        String username = usernameOf(p);
        collab.ensurePresence(diagramId, sessionId, p.userId(), username);
        JsonNode prepared = diagrams.validateOperation(p, diagramId, operation);
        String elementId = applier.elementIdOf(prepared);
        boolean acquired = collab.lock(sessionId, diagramId, elementId, p.userId());
        OperationResult result;
        try {
            result = diagrams.applyOperation(p, diagramId, prepared);
        } finally {
            if (acquired) {
                collab.unlock(sessionId, diagramId, elementId, p.userId());
            }
        }
        collab.touch(diagramId);
        broadcast(new CollabMessage(Type.OP, diagramId, p.userId(), username, elementId,
                result.operation(), result.diagram().getVersion(), now(), null));
    }

    @EventListener
    public void onDisconnect(SessionDisconnectEvent event) {
        publishCleanup(collab.disconnect(event.getSessionId()));
    }

    // ---- helpers

    private void publishCleanup(SessionCleanup c) {
        if (c == null) {
            return;
        }
        log.info("Sesión fuera del diagrama {}: locks liberados={}, participante se fue={}",
                c.diagramId(), c.releasedLocks().size(), c.participantGone());
        for (String elementId : c.releasedLocks()) {
            broadcast(new CollabMessage(Type.UNLOCK, c.diagramId(), c.participant().userId(),
                    c.participant().username(), elementId, null, null, now(), null));
        }
        if (c.participantGone()) {
            broadcast(new CollabMessage(Type.LEAVE, c.diagramId(), c.participant().userId(),
                    c.participant().username(), null, null, null, now(), collab.participants(c.diagramId())));
        }
    }

    private void broadcast(CollabMessage m) {
        messaging.convertAndSend("/topic/diagram." + m.diagramId(), m);
    }

    private String usernameOf(AuthPrincipal p) {
        return users.findById(p.userId()).map(User::getUsername).orElse(p.userId().toString());
    }

    private static long now() {
        return Instant.now().toEpochMilli();
    }

    private static void requireElement(String elementId) {
        if (elementId == null || elementId.isBlank()) {
            throw ApiException.validation("Debe indicar el elemento (elementId).");
        }
    }

    private static void requireAnyRole(AuthPrincipal p, String... roles) {
        for (String r : roles) {
            if (p.hasRole(r)) {
                return;
            }
        }
        throw ApiException.forbidden();
    }
}
