package com.diagramas.platform.codegen.web;

import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.service.CodeGenerationService;
import com.diagramas.platform.codegen.service.TaskService;
import com.diagramas.platform.codegen.service.TaskService.EditTask;
import com.diagramas.platform.codegen.service.TaskService.NewTask;
import com.diagramas.platform.common.security.CurrentUser;
import com.diagramas.platform.common.util.Json;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** Frontera GraphQL de generación de código: CU-14 (DESIGNER, DEVELOPER). */
@Controller
public class CodegenGraphQlController {

    public record TaskDto(
            UUID id, UUID diagramId, String type, String title, String description, String status, Object resultJson,
            UUID assignedTo, UUID createdBy, String createdAt, String startedAt, String completedAt) {

        static TaskDto of(Task t) {
            return new TaskDto(t.getId(), t.getDiagramId(), t.getType(), t.getTitle(), t.getDescription(), t.getStatus(),
                    Json.plain(t.getResultJson()), t.getAssignedTo(), t.getCreatedBy(), String.valueOf(t.getCreatedAt()),
                    t.getStartedAt() == null ? null : t.getStartedAt().toString(),
                    t.getCompletedAt() == null ? null : t.getCompletedAt().toString());
        }
    }

    /** Datos que llegan desde GraphQL para crear una tarea manual (CU-21). */
    public record NewTaskInput(UUID diagramId, UUID assignedTo, String title, String description) {}

    /** Datos que llegan desde GraphQL para editar una tarea manual (CU-21). */
    public record EditTaskInput(UUID assignedTo, String title, String description) {}

    private final CodeGenerationService codegen;
    private final TaskService taskService;

    public CodegenGraphQlController(CodeGenerationService codegen, TaskService taskService) {
        this.codegen = codegen;
        this.taskService = taskService;
    }

    /** CU-14: generar código es cosa de quien diseña o desarrolla. */
    @MutationMapping
    @PreAuthorize("hasAnyRole('DESIGNER', 'DEVELOPER')")
    public TaskDto generateBackendCode(@Argument UUID diagramId, @Argument String language) {
        return TaskDto.of(codegen.generate(CurrentUser.get(), diagramId, language));
    }

    @QueryMapping
    public List<TaskDto> tasks(@Argument UUID diagramId, @Argument String status) {
        return taskService.list(CurrentUser.get(), diagramId, status).stream().map(TaskDto::of).toList();
    }

    /** CU-21: lo que le toca al usuario del token. */
    @QueryMapping
    public List<TaskDto> myTasks(@Argument String status) {
        return taskService.myTasks(CurrentUser.get(), status).stream().map(TaskDto::of).toList();
    }

    @QueryMapping
    public List<TaskDto> tasksCreatedByMe() {
        return taskService.createdByMe(CurrentUser.get()).stream().map(TaskDto::of).toList();
    }

    @MutationMapping
    public TaskDto createTask(@Argument NewTaskInput input) {
        return TaskDto.of(taskService.create(
                CurrentUser.get(),
                new NewTask(input.diagramId(), input.assignedTo(), input.title(), input.description())));
    }

    @MutationMapping
    public TaskDto updateTaskStatus(@Argument UUID id, @Argument String status) {
        return TaskDto.of(taskService.updateStatus(CurrentUser.get(), id, status));
    }

    @MutationMapping
    public TaskDto updateTask(@Argument UUID id, @Argument EditTaskInput input) {
        return TaskDto.of(taskService.update(
                CurrentUser.get(), id, new EditTask(input.assignedTo(), input.title(), input.description())));
    }

    /** Devuelve el id de la tarea eliminada para que el cliente sepa cuál quitar. */
    @MutationMapping
    public UUID deleteTask(@Argument UUID id) {
        taskService.delete(CurrentUser.get(), id);
        return id;
    }

    /**
     * Nombre de la persona asignada y de quien creó la tarea. Se resuelven aquí y solo si el cliente los
     * pide: la tarea guarda los identificadores, no los nombres.
     */
    @SchemaMapping(typeName = "Task")
    public String assignedToName(TaskDto task) {
        return taskService.displayName(CurrentUser.get(), task.assignedTo());
    }

    @SchemaMapping(typeName = "Task")
    public String createdByName(TaskDto task) {
        return taskService.displayName(CurrentUser.get(), task.createdBy());
    }

    @QueryMapping
    public TaskDto task(@Argument UUID id) {
        return TaskDto.of(codegen.get(CurrentUser.get(), id));
    }

    // Las consultas y el cambio de estado de tareas los puede usar cualquier rol de la empresa: el servicio
    // comprueba quién puede tocar cada tarea.
}
