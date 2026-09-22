package com.diagramas.platform.design.collab;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.error.ErrorResponse;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.design.collab.CollabMessages.CollabRequest;
import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;

/**
 * Frontera WebSocket de colaboración. SEND /app/diagram/{id}/join | leave | lock | unlock | op.
 * Los errores llegan solo al emisor por /user/queue/errors con el formato 7.1.
 */
@Controller
public class CollabWebSocketController {

    private static final Logger log = LoggerFactory.getLogger(CollabWebSocketController.class);

    private final CollabService collab;

    public CollabWebSocketController(CollabService collab) {
        this.collab = collab;
    }

    @MessageMapping("/diagram/{id}/join")
    public void join(@DestinationVariable UUID id, Principal principal, SimpMessageHeaderAccessor headers) {
        collab.join(identity(principal), id, headers.getSessionId());
    }

    @MessageMapping("/diagram/{id}/leave")
    public void leave(@DestinationVariable UUID id, Principal principal, SimpMessageHeaderAccessor headers) {
        collab.leave(identity(principal), id, headers.getSessionId());
    }

    @MessageMapping("/diagram/{id}/lock")
    public void lock(@DestinationVariable UUID id, @Payload CollabRequest req, Principal principal,
                     SimpMessageHeaderAccessor headers) {
        collab.lock(identity(principal), id, req.elementId(), headers.getSessionId());
    }

    @MessageMapping("/diagram/{id}/unlock")
    public void unlock(@DestinationVariable UUID id, @Payload CollabRequest req, Principal principal,
                       SimpMessageHeaderAccessor headers) {
        collab.unlock(identity(principal), id, req.elementId(), headers.getSessionId());
    }

    @MessageMapping("/diagram/{id}/op")
    public void operation(@DestinationVariable UUID id, @Payload CollabRequest req, Principal principal,
                          SimpMessageHeaderAccessor headers) {
        collab.operate(identity(principal), id, req.op(), headers.getSessionId());
    }

    @MessageExceptionHandler
    @SendToUser(destinations = "/queue/errors", broadcast = false)
    public ErrorResponse handleError(Exception ex) {
        if (ex instanceof ApiException api) {
            return ErrorResponse.of(api);
        }
        log.error("Error en mensaje WebSocket", ex);
        return ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado. Intente nuevamente.", Map.of());
    }

    private static AuthPrincipal identity(Principal principal) {
        if (principal instanceof Authentication auth && auth.getPrincipal() instanceof AuthPrincipal p) {
            return p;
        }
        throw new ApiException(ErrorCode.UNAUTHORIZED, "Debe iniciar sesión para continuar.");
    }
}
