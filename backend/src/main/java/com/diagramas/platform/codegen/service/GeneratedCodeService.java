package com.diagramas.platform.codegen.service;

import com.diagramas.platform.codegen.domain.GeneratedCode;
import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.repository.GeneratedCodeRepository;
import com.diagramas.platform.codegen.repository.TaskRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.design.service.DiagramService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CU-15: historial de generaciones y descarga en ZIP. Siempre acotado a la empresa del JWT. */
@Service
public class GeneratedCodeService {

    public record FileInfo(UUID id, String fileName, String status) {}

    public record GenerationSummary(
            UUID taskId, UUID diagramId, String language, String status, Instant createdAt, List<FileInfo> files) {}

    public record ZipDownload(String fileName, byte[] content) {}

    private final GeneratedCodeRepository generatedCode;
    private final TaskRepository tasks;
    private final DiagramService diagrams;

    public GeneratedCodeService(GeneratedCodeRepository generatedCode, TaskRepository tasks, DiagramService diagrams) {
        this.generatedCode = generatedCode;
        this.tasks = tasks;
        this.diagrams = diagrams;
    }

    @Transactional(readOnly = true)
    public List<GenerationSummary> history(AuthPrincipal p, UUID diagramId) {
        diagrams.get(p, diagramId); // NOT_FOUND si el diagrama no es de la empresa
        Map<UUID, List<GeneratedCode>> byTask = new LinkedHashMap<>();
        for (GeneratedCode g : generatedCode.findByDiagramIdOrderByCreatedAtDescFileNameAsc(diagramId)) {
            if (g.getTaskId() != null) {
                byTask.computeIfAbsent(g.getTaskId(), k -> new ArrayList<>()).add(g);
            }
        }
        List<GenerationSummary> result = new ArrayList<>();
        byTask.forEach((taskId, list) -> {
            GeneratedCode first = list.get(0);
            result.add(new GenerationSummary(taskId, diagramId, first.getLanguage(), first.getStatus(), first.getCreatedAt(),
                    list.stream().map(g -> new FileInfo(g.getId(), g.getFileName(), g.getStatus())).toList()));
        });
        return result;
    }

    @Transactional(readOnly = true)
    public ZipDownload zip(AuthPrincipal p, UUID taskId) {
        Task task = tasks.findByIdAndCompanyId(taskId, p.companyId())
                .orElseThrow(() -> ApiException.notFound("El código generado no existe."));
        List<GeneratedCode> files = generatedCode.findByTaskIdOrderByFileNameAsc(task.getId());
        if (files.isEmpty()) {
            throw ApiException.notFound("El código generado no existe.");
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(out)) {
            Set<String> used = new HashSet<>();
            for (GeneratedCode g : files) {
                String entryName = uniqueEntry(safeEntryName(g.getFileName()), used);
                zip.putNextEntry(new ZipEntry(entryName));
                zip.write(g.getCodeContent().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return new ZipDownload("codigo-" + task.getId() + ".zip", out.toByteArray());
        } catch (IOException e) {
            throw new ApiException(com.diagramas.platform.common.error.ErrorCode.INTERNAL_ERROR,
                    "No se pudo preparar la descarga. Intente nuevamente.");
        }
    }

    /**
     * Protección zip-slip: elimina separadores de unidad, rutas absolutas y segmentos «.» / «..», de modo
     * que ninguna entrada pueda salir del directorio de extracción.
     */
    public static String safeEntryName(String fileName) {
        String raw = fileName == null ? "" : fileName.replace('\\', '/');
        List<String> segments = new ArrayList<>();
        for (String seg : raw.split("/")) {
            String s = seg.replace(':', '_').trim();
            if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                continue;
            }
            segments.add(s);
        }
        return segments.isEmpty() ? "archivo.txt" : String.join("/", segments);
    }

    private static String uniqueEntry(String name, Set<String> used) {
        String candidate = name;
        int n = 2;
        while (!used.add(candidate)) {
            candidate = n++ + "_" + name;
        }
        return candidate;
    }
}
