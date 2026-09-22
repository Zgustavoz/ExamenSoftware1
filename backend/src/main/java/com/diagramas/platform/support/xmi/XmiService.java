package com.diagramas.platform.support.xmi;

import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.repository.TaskRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramService;
import com.diagramas.platform.support.storage.StorageService;
import com.diagramas.platform.support.xmi.ArchitectAdapter.XmiModel;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** CU-16 Exportar / Importar XMI (flujo 9.4). Guardar en almacenamiento y registrar la tarea es best-effort. */
@Service
public class XmiService {

    private static final Logger log = LoggerFactory.getLogger(XmiService.class);
    private static final Set<String> CONTENT_TYPES = Set.of(
            "application/xml", "text/xml", "application/xmi+xml", "application/vnd.xmi+xml", "application/octet-stream");

    public record XmiExport(String fileName, byte[] content) {}

    private final DiagramService diagrams;
    private final ArchitectAdapter adapter;
    private final StorageService storage;
    private final TaskRepository tasks;

    public XmiService(DiagramService diagrams, ArchitectAdapter adapter, StorageService storage, TaskRepository tasks) {
        this.diagrams = diagrams;
        this.adapter = adapter;
        this.storage = storage;
        this.tasks = tasks;
    }

    public XmiExport export(AuthPrincipal p, UUID diagramId) {
        Diagram d = diagrams.get(p, diagramId);
        if (!Diagram.TYPE_CLASS.equals(d.getType())) {
            throw new ApiException(ErrorCode.XMI_INVALID, "Solo se pueden exportar diagramas de clases a XMI.");
        }
        byte[] bytes = adapter.toXmi(d.getContentJson(), d.getName()).getBytes(StandardCharsets.UTF_8);
        String fileName = d.getName().replaceAll("[^\\p{L}\\p{N}._-]+", "_") + ".xmi";
        String key = store(p, "xmi/" + p.companyId() + "/" + d.getId() + "/" + Instant.now().toEpochMilli() + ".xmi", bytes);
        registerTask(p, d.getId(), Task.XMI_EXPORT, "Exportar XMI – " + d.getName(), key);
        return new XmiExport(fileName, bytes);
    }

    public Diagram importXmi(AuthPrincipal p, UUID projectId, String originalName, String contentType, InputStream in) {
        String lower = originalName == null ? "" : originalName.toLowerCase(Locale.ROOT);
        if (!(lower.endsWith(".xmi") || lower.endsWith(".xml"))) {
            throw new ApiException(ErrorCode.XMI_INVALID, "El archivo debe tener extensión .xmi o .xml.");
        }
        if (contentType != null && !contentType.isBlank()
                && !CONTENT_TYPES.contains(contentType.split(";")[0].trim().toLowerCase(Locale.ROOT))) {
            throw new ApiException(ErrorCode.XMI_INVALID, "El tipo de archivo no es válido para XMI.");
        }
        XmiModel model = adapter.fromXmi(in);
        Diagram imported = diagrams.createWithContent(p, projectId, model.name(), Diagram.TYPE_CLASS, model.content(), null);
        registerTask(p, imported.getId(), Task.XMI_IMPORT, "Importar XMI – " + imported.getName(), null);
        return imported;
    }

    private String store(AuthPrincipal p, String key, byte[] bytes) {
        try {
            return storage.put(key, bytes, "application/xml");
        } catch (RuntimeException e) {
            log.warn("No se pudo guardar el XMI en el almacenamiento: {}", e.getMessage());
            return null;
        }
    }

    private void registerTask(AuthPrincipal p, UUID diagramId, String type, String title, String storageKey) {
        try {
            Task t = new Task();
            t.setCompanyId(p.companyId());
            t.setDiagramId(diagramId);
            t.setCreatedBy(p.userId());
            t.setAssignedTo(p.userId());
            t.setType(type);
            t.setTitle(title.length() > 200 ? title.substring(0, 200) : title);
            t.setStatus(Task.COMPLETED);
            t.setStartedAt(Instant.now());
            t.setCompletedAt(Instant.now());
            if (storageKey != null) {
                ObjectNode result = Json.object();
                result.put("storageKey", storageKey);
                t.setResultJson(result);
            }
            tasks.save(t);
        } catch (RuntimeException e) {
            log.warn("No se pudo registrar la tarea {}: {}", type, e.getMessage());
        }
    }
}
