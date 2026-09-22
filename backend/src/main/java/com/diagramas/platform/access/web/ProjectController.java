package com.diagramas.platform.access.web;

import com.diagramas.platform.access.dto.AccessDtos.PageResponse;
import com.diagramas.platform.access.dto.AccessDtos.ProjectDto;
import com.diagramas.platform.access.dto.AccessDtos.ProjectRequest;
import com.diagramas.platform.access.service.ProjectService;
import com.diagramas.platform.common.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projects;

    public ProjectController(ProjectService projects) {
        this.projects = projects;
    }

    /** CU-05: todos los roles autenticados. */
    @GetMapping
    public PageResponse<ProjectDto> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return projects.search(CurrentUser.get(), q, page, size);
    }

    /** CU-04: COMPANY_ADMIN o DESIGNER. */
    @PostMapping
    @PreAuthorize("hasAnyRole('COMPANY_ADMIN', 'DESIGNER')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectDto create(@Valid @RequestBody ProjectRequest req) {
        return projects.create(CurrentUser.get(), req);
    }

    @GetMapping("/{id}")
    public ProjectDto get(@PathVariable UUID id) {
        return projects.get(CurrentUser.get(), id);
    }

    /** CU-23: el administrador de la empresa o el propietario del proyecto. */
    @PutMapping("/{id}")
    public ProjectDto update(@PathVariable UUID id, @Valid @RequestBody ProjectRequest req) {
        return projects.update(CurrentUser.get(), id, req);
    }

    /** CU-23: borra también sus diagramas, tareas, código generado y conversaciones con el asistente. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        projects.delete(CurrentUser.get(), id);
    }
}
