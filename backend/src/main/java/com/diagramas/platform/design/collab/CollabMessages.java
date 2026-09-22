package com.diagramas.platform.design.collab;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.UUID;

public final class CollabMessages {

    private CollabMessages() {}

    public enum Type { OP, LOCK, UNLOCK, JOIN, LEAVE, ERROR }

    /** Mensaje difundido en /topic/diagram.{id}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CollabMessage(
            Type type,
            UUID diagramId,
            UUID userId,
            String username,
            String elementId,
            JsonNode op,
            Integer version,
            long ts,
            List<CollabPort.Participant> participants) {}

    /** Cuerpo de los SEND del cliente: elementId (lock/unlock) u op (operación). */
    public record CollabRequest(String elementId, JsonNode op) {}
}
