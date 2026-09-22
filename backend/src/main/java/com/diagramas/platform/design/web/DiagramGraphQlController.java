package com.diagramas.platform.design.web;

import com.diagramas.platform.common.security.CurrentUser;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.dto.DiagramDto;
import com.diagramas.platform.design.service.DiagramService;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** Frontera GraphQL de Diseño de Diagramas: CU-06, CU-10/18, CU-11. */
@Controller
public class DiagramGraphQlController {

    private final DiagramService diagrams;

    public DiagramGraphQlController(DiagramService diagrams) {
        this.diagrams = diagrams;
    }

    @QueryMapping
    public List<DiagramDto> diagrams(@Argument UUID projectId) {
        return diagrams.list(CurrentUser.get(), projectId).stream().map(DiagramDto::of).toList();
    }

    @QueryMapping
    public DiagramDto diagram(@Argument UUID id) {
        return DiagramDto.of(diagrams.get(CurrentUser.get(), id));
    }

    @MutationMapping
    @PreAuthorize("hasRole('DESIGNER')")
    public DiagramDto createDiagram(@Argument UUID projectId, @Argument String name, @Argument String description) {
        return DiagramDto.of(diagrams.create(CurrentUser.get(), projectId, name, description));
    }

    /** CU-10 (y CU-18 al sincronizar): guardado con control optimista por baseVersion. */
    @MutationMapping
    @PreAuthorize("hasRole('DESIGNER')")
    public DiagramDto saveDiagram(@Argument UUID id, @Argument Object contentJson, @Argument int baseVersion) {
        return DiagramDto.of(diagrams.saveContent(CurrentUser.get(), id, Json.fromPlain(contentJson), baseVersion));
    }
}
