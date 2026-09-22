package com.diagramas.platform.access.service;

import com.diagramas.platform.access.domain.Project;
import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.dto.AccessDtos.PageResponse;
import com.diagramas.platform.access.dto.AccessDtos.ProjectDto;
import com.diagramas.platform.access.dto.AccessDtos.ProjectRequest;
import com.diagramas.platform.access.repository.ProjectRepository;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CU-04 Crear proyecto y CU-05 Consultar proyectos (siempre de la empresa del JWT). */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final UserRepository users;

    public ProjectService(ProjectRepository projects, UserRepository users) {
        this.projects = projects;
        this.users = users;
    }

    @Transactional
    public ProjectDto create(AuthPrincipal p, ProjectRequest req) {
        String name = req.name().trim();
        if (projects.existsByCompanyIdAndNameIgnoreCase(p.companyId(), name)) {
            throw new ApiException(ErrorCode.DUPLICATE_PROJECT, "Ya existe un proyecto con ese nombre en la empresa.");
        }
        Project project = new Project();
        project.setCompanyId(p.companyId());
        project.setName(name);
        project.setDescription(req.description());
        project.setOwnerId(p.userId());
        Project saved = projects.saveAndFlush(project);
        return toDto(saved, ownerNames(List.of(saved)));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectDto> search(AuthPrincipal p, String q, int page, int size) {
        String pattern = "%" + (q == null ? "" : q.trim().toLowerCase().replace("%", "\\%").replace("_", "\\_")) + "%";
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("updatedAt").descending());
        Page<Project> result = projects.search(p.companyId(), pattern, pageable);
        Map<UUID, String> owners = ownerNames(result.getContent());
        return PageResponse.of(result, result.getContent().stream().map(pr -> toDto(pr, owners)).toList());
    }

    @Transactional(readOnly = true)
    public ProjectDto get(AuthPrincipal p, UUID id) {
        Project project = find(p, id);
        return toDto(project, ownerNames(List.of(project)));
    }

    /**
     * CU-23 Editar proyecto. Solo el administrador de la empresa o el propietario del proyecto.
     */
    @Transactional
    public ProjectDto update(AuthPrincipal p, UUID id, ProjectRequest req) {
        Project project = find(p, id);
        assertCanManage(p, project);
        String name = req.name().trim();
        // El propio proyecto no cuenta como duplicado de sí mismo.
        if (!project.getName().equalsIgnoreCase(name)
                && projects.existsByCompanyIdAndNameIgnoreCase(p.companyId(), name)) {
            throw new ApiException(ErrorCode.DUPLICATE_PROJECT, "Ya existe un proyecto con ese nombre en la empresa.");
        }
        project.setName(name);
        project.setDescription(req.description());
        Project saved = projects.saveAndFlush(project);
        return toDto(saved, ownerNames(List.of(saved)));
    }

    /**
     * CU-23 Eliminar proyecto. Las claves foráneas en cascada se llevan por delante sus diagramas y, con
     * ellos, sus tareas, el código generado y las conversaciones con el asistente: no quedan huérfanos.
     */
    @Transactional
    public void delete(AuthPrincipal p, UUID id) {
        Project project = find(p, id);
        assertCanManage(p, project);
        projects.delete(project);
    }

    /** Administrar un proyecto es cosa del administrador de la empresa o de quien lo creó. */
    private static void assertCanManage(AuthPrincipal p, Project project) {
        if (!p.hasRole(AuthPrincipal.COMPANY_ADMIN) && !p.userId().equals(project.getOwnerId())) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Solo el administrador de la empresa o el propietario pueden modificar el proyecto.");
        }
    }

    /** Uso interno de otros módulos: valida que el proyecto pertenezca a la empresa del JWT. */
    @Transactional(readOnly = true)
    public Project find(AuthPrincipal p, UUID id) {
        return projects.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("El proyecto no existe."));
    }

    private Map<UUID, String> ownerNames(List<Project> list) {
        var ids = list.stream().map(Project::getOwnerId).filter(java.util.Objects::nonNull).distinct().toList();
        return users.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getFullName() != null && !u.getFullName().isBlank() ? u.getFullName() : u.getUsername(), (a, b) -> a));
    }

    private static ProjectDto toDto(Project pr, Map<UUID, String> owners) {
        return new ProjectDto(pr.getId(), pr.getName(), pr.getDescription(), pr.getOwnerId(),
                owners.get(pr.getOwnerId()), pr.getCreatedAt(), pr.getUpdatedAt());
    }
}
