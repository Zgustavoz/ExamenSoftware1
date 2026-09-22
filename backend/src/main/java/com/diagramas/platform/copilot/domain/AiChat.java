package com.diagramas.platform.copilot.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.diagramas.platform.common.util.Json;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "ai_chats")
@Getter
@Setter
public class AiChat {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "diagram_id")
    private UUID diagramId;

    @Column(name = "user_id")
    private UUID userId;

    private String title;

    /** [{role, content, timestamp}] */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private JsonNode messages = Json.array();

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /** Agrega un mensaje (registrarMensaje). Reemplaza el nodo completo para que Hibernate detecte el cambio. */
    public void addMessage(String role, String content) {
        ArrayNode copy = messages instanceof ArrayNode a ? a.deepCopy() : Json.array();
        ObjectNode m = Json.object();
        m.put("role", role);
        m.put("content", content);
        m.put("timestamp", Instant.now().toString());
        copy.add(m);
        this.messages = copy;
    }
}
