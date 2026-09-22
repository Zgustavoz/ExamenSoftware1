package com.diagramas.platform.copilot.service;

import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.copilot.domain.AiChat;
import com.diagramas.platform.copilot.repository.AiChatRepository;
import com.diagramas.platform.design.service.DiagramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persistencia de conversaciones IA (tabla ai_chats): una conversación continua por (diagrama, usuario). */
@Service
public class AiChatService {

    private static final int HISTORY_LIMIT = 20;

    private final AiChatRepository chats;
    private final DiagramService diagrams;

    public AiChatService(AiChatRepository chats, DiagramService diagrams) {
        this.chats = chats;
        this.diagrams = diagrams;
    }

    /** CU-13: historial del usuario para un diagrama, ordenado por tiempo. */
    @Transactional(readOnly = true)
    public List<AiChat> list(AuthPrincipal p, UUID diagramId) {
        diagrams.get(p, diagramId); // NOT_FOUND si el diagrama no existe o es de otra empresa
        return chats.findByDiagramIdAndUserIdOrderByCreatedAtAsc(diagramId, p.userId());
    }

    /** Últimos mensajes de la conversación (contexto para la IA), como [{role, content}]. */
    @Transactional(readOnly = true)
    public ArrayNode history(AuthPrincipal p, UUID diagramId) {
        ArrayNode out = Json.array();
        chats.findFirstByDiagramIdAndUserIdOrderByCreatedAtDesc(diagramId, p.userId()).ifPresent(chat -> {
            JsonNode all = chat.getMessages();
            int from = Math.max(0, all.size() - HISTORY_LIMIT);
            for (int i = from; i < all.size(); i++) {
                ObjectNode m = Json.object();
                m.put("role", all.get(i).path("role").asText());
                m.put("content", all.get(i).path("content").asText());
                out.add(m);
            }
        });
        return out;
    }

    /** registrarMensajeUsuario / registrarMensajeIA */
    @Transactional
    public void append(AuthPrincipal p, UUID diagramId, String role, String content) {
        AiChat chat = chats.findFirstByDiagramIdAndUserIdOrderByCreatedAtDesc(diagramId, p.userId())
                .orElseGet(() -> {
                    AiChat c = new AiChat();
                    c.setDiagramId(diagramId);
                    c.setUserId(p.userId());
                    c.setTitle("Conversación con el asistente");
                    return c;
                });
        chat.addMessage(role, content);
        chats.save(chat);
    }
}
