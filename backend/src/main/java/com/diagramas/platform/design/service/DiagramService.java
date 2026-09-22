package com.diagramas.platform.design.service;

import com.diagramas.platform.access.domain.Project;
import com.diagramas.platform.access.service.ProjectService;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.repository.DiagramRepository;
import com.diagramas.platform.design.service.DiagramOperationApplier.Applied;
import com.diagramas.platform.design.service.DiagramOperationApplier.AppliedBatch;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de diseño: CU-06 crear, CU-10 guardar/versionar, CU-11 consultar, y aplicación de
 * operaciones (CU-07/08/09, usadas por el canal colaborativo y por la IA). Toda consulta filtra por company_id.
 */
@Service
public class DiagramService {

    private final DiagramRepository diagrams;
    private final ProjectService projects;
    private final DiagramOperationApplier applier;

    public DiagramService(DiagramRepository diagrams, ProjectService projects, DiagramOperationApplier applier) {
        this.diagrams = diagrams;
        this.projects = projects;
        this.applier = applier;
    }

    /** Resultado de aplicar una operación: diagrama actualizado + operación normalizada (con IDs). */
    public record OperationResult(Diagram diagram, ObjectNode operation) {}

    // ---- CU-06

    @Transactional
    public Diagram create(AuthPrincipal p, UUID projectId, String name, String description) {
        Project project = projects.find(p, projectId);
        String n = name == null ? "" : name.trim();
        if (n.isEmpty() || n.length() > 100) {
            throw ApiException.validation("El nombre del diagrama es obligatorio (máximo 100 caracteres).");
        }
        if (diagrams.existsByProjectIdAndNameIgnoreCase(project.getId(), n)) {
            throw new ApiException(ErrorCode.DUPLICATE_DIAGRAM, "Ya existe un diagrama con ese nombre en el proyecto.");
        }
        Diagram d = new Diagram();
        d.setCompanyId(p.companyId());
        d.setProjectId(project.getId());
        d.setName(n);
        d.setDescription(description);
        d.setType(Diagram.TYPE_CLASS);
        d.setContentJson(DiagramOperationApplier.emptyClassContent());
        d.setVersion(1);
        d.setCreatedBy(p.userId());
        return save(d);
    }

    /** Crea un diagrama derivado (secuencia, XMI importado...) con contenido ya validado. */
    @Transactional
    public Diagram createWithContent(
            AuthPrincipal p, UUID projectId, String name, String type, JsonNode content, UUID sourceDiagramId) {
        Project project = projects.find(p, projectId);
        Diagram d = new Diagram();
        d.setCompanyId(p.companyId());
        d.setProjectId(project.getId());
        d.setName(uniqueName(project.getId(), name));
        d.setType(type);
        d.setContentJson(content);
        d.setVersion(1);
        d.setSourceDiagramId(sourceDiagramId);
        d.setCreatedBy(p.userId());
        return save(d);
    }

    // ---- CU-11

    @Transactional(readOnly = true)
    public List<Diagram> list(AuthPrincipal p, UUID projectId) {
        projects.find(p, projectId);
        return diagrams.findByProjectIdAndCompanyIdOrderByNameAsc(projectId, p.companyId());
    }

    @Transactional(readOnly = true)
    public Diagram get(AuthPrincipal p, UUID id) {
        return diagrams.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("El diagrama no existe."));
    }

    // ---- CU-10 (y CU-18: sincronización offline usa el mismo control optimista)

    @Transactional
    public Diagram saveContent(AuthPrincipal p, UUID id, JsonNode content, int baseVersion) {
        Diagram d = lock(p, id);
        if (d.getVersion() != baseVersion) {
            throw versionConflict(d);
        }
        ObjectNode normalized = applier.normalizeContent(content);
        if (!d.getType().equals(text(normalized, "type"))) {
            throw new ApiException(ErrorCode.INVALID_JSON, "El contenido no corresponde al tipo del diagrama.");
        }
        d.setContentJson(normalized);
        d.setVersion(d.getVersion() + 1);
        return diagrams.save(d);
    }

    // ---- CU-07: una operación (canal colaborativo)

    @Transactional
    public OperationResult applyOperation(AuthPrincipal p, UUID id, JsonNode operation) {
        Diagram d = lock(p, id);
        Applied applied = applier.apply(d.getContentJson(), operation);
        d.setContentJson(applied.content());
        d.setVersion(d.getVersion() + 1);
        return new OperationResult(diagrams.save(d), applied.operation());
    }

    /**
     * Validación sin efectos (paso «validarElemento» del flujo 9.1).
     *
     * @return la operación normalizada (IDs asignados), lista para aplicarse
     */
    @Transactional(readOnly = true)
    public ObjectNode validateOperation(AuthPrincipal p, UUID id, JsonNode operation) {
        Diagram d = get(p, id);
        return applier.apply(d.getContentJson(), operation).operation();
    }

    // ---- CU-12: varias operaciones de la IA, atómicas

    @Transactional
    public Diagram applyOperations(AuthPrincipal p, UUID id, List<? extends JsonNode> operations) {
        Diagram d = lock(p, id);
        if (operations.isEmpty()) {
            return d;
        }
        AppliedBatch batch = applier.applyAll(d.getContentJson(), operations);
        d.setContentJson(batch.content());
        d.setVersion(d.getVersion() + 1);
        return diagrams.save(d);
    }

    // ---- utilidades

    private Diagram lock(AuthPrincipal p, UUID id) {
        return diagrams.findForUpdate(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("El diagrama no existe."));
    }

    private Diagram save(Diagram d) {
        try {
            return diagrams.saveAndFlush(d);
        } catch (DataIntegrityViolationException e) {
            throw new ApiException(ErrorCode.DUPLICATE_DIAGRAM, "Ya existe un diagrama con ese nombre en el proyecto.");
        }
    }

    private String uniqueName(UUID projectId, String base) {
        String name = base.length() > 90 ? base.substring(0, 90) : base;
        String candidate = name;
        int n = 2;
        while (diagrams.existsByProjectIdAndNameIgnoreCase(projectId, candidate)) {
            candidate = name + " (" + n++ + ")";
        }
        return candidate;
    }

    private static ApiException versionConflict(Diagram current) {
        return new ApiException(
                ErrorCode.VERSION_CONFLICT,
                "El diagrama fue modificado por otro usuario o dispositivo. Se cargó la versión más reciente.",
                Map.of("currentVersion", current.getVersion(), "contentJson", Json.plain(current.getContentJson())));
    }

    private static String text(JsonNode n, String f) {
        return Json.text(n, f);
    }
}
