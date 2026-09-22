package com.diagramas.platform.codegen.service;

import com.diagramas.platform.codegen.domain.GeneratedCode;
import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.generator.CodeGenerator.GeneratedFile;
import com.diagramas.platform.codegen.repository.GeneratedCodeRepository;
import com.diagramas.platform.codegen.repository.TaskRepository;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.support.notification.NotificationService;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cada operación es su propia transacción: la tarea debe quedar registrada (y marcada FAILED) aunque la
 * generación falle. La notificación CODE_READY se inserta en la misma transacción que marca la tarea COMPLETED.
 */
@Service
public class CodegenPersistence {

    private final TaskRepository tasks;
    private final GeneratedCodeRepository generatedCode;
    private final NotificationService notifications;

    public CodegenPersistence(TaskRepository tasks, GeneratedCodeRepository generatedCode, NotificationService notifications) {
        this.tasks = tasks;
        this.generatedCode = generatedCode;
        this.notifications = notifications;
    }

    /** registrarTarea → Task.crearTarea (PENDING) + notificación TASK_ASSIGNED al asignado. */
    @Transactional
    public Task createTask(AuthPrincipal p, Diagram diagram, String language) {
        Task t = new Task();
        t.setCompanyId(p.companyId());
        t.setDiagramId(diagram.getId());
        t.setAssignedTo(p.userId());
        t.setCreatedBy(p.userId());
        t.setType(Task.CODE_GENERATION);
        t.setTitle("Generar código " + language + " – " + diagram.getName());
        t.setStatus(Task.PENDING);
        Task saved = tasks.saveAndFlush(t);
        notifications.notify(p.userId(), NotificationService.TASK_ASSIGNED, "Tarea asignada", saved.getTitle(),
                Map.of("taskId", saved.getId().toString(), "diagramId", diagram.getId().toString()));
        return saved;
    }

    @Transactional
    public void start(UUID taskId) {
        Task t = tasks.findById(taskId).orElseThrow();
        t.setStatus(Task.IN_PROGRESS);
        t.setStartedAt(Instant.now());
        tasks.save(t);
    }

    /** persistirCodigo (un generated_code por archivo) + actualizarEstadoTarea(COMPLETED) + notificación. */
    @Transactional
    public Task complete(AuthPrincipal p, Task task, Diagram diagram, String language, List<GeneratedFile> files) {
        ArrayNode names = Json.array();
        for (GeneratedFile f : files) {
            GeneratedCode g = new GeneratedCode();
            g.setDiagramId(diagram.getId());
            g.setTaskId(task.getId());
            g.setUserId(p.userId());
            g.setLanguage(language);
            g.setFileName(f.path());
            g.setCodeContent(f.content());
            g.setStatus("SUCCESS");
            generatedCode.save(g);
            names.add(f.path());
        }
        Task t = tasks.findById(task.getId()).orElseThrow();
        ObjectNode result = Json.object();
        result.put("language", language);
        result.put("fileCount", files.size());
        result.set("files", names);
        t.setStatus(Task.COMPLETED);
        t.setCompletedAt(Instant.now());
        t.setResultJson(result);
        Task saved = tasks.saveAndFlush(t);
        notifications.notify(p.userId(), NotificationService.CODE_READY, "Código listo",
                "El código de «" + diagram.getName() + "» está disponible para descargar.",
                Map.of("taskId", saved.getId().toString(), "diagramId", diagram.getId().toString()));
        return saved;
    }

    @Transactional
    public void fail(UUID taskId, String reason) {
        tasks.findById(taskId).ifPresent(t -> {
            ObjectNode result = Json.object();
            result.put("error", reason);
            t.setStatus(Task.FAILED);
            t.setCompletedAt(Instant.now());
            t.setResultJson(result);
            tasks.save(t);
        });
    }
}
