package com.diagramas.platform.design.dto;

import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import java.util.UUID;

/** Vista de un diagrama para GraphQL y REST. Fechas como ISO-8601 (String) para el esquema GraphQL. */
public record DiagramDto(
        UUID id,
        UUID projectId,
        String name,
        String description,
        String type,
        Object contentJson,
        int version,
        UUID sourceDiagramId,
        UUID createdBy,
        String createdAt,
        String updatedAt) {

    public static DiagramDto of(Diagram d) {
        return new DiagramDto(
                d.getId(),
                d.getProjectId(),
                d.getName(),
                d.getDescription(),
                d.getType(),
                Json.plain(d.getContentJson()),
                d.getVersion(),
                d.getSourceDiagramId(),
                d.getCreatedBy(),
                String.valueOf(d.getCreatedAt()),
                String.valueOf(d.getUpdatedAt()));
    }
}
