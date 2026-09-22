package com.diagramas.platform.codegen.service;

import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.repository.TaskRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramService;
import com.diagramas.platform.support.notification.NotificationService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-21 Gestionar tareas. Las tareas manuales las crea una persona y se asignan a otra; las automáticas
 * ({@code CODE_GENERATION}, {@code XMI_*}) las crea el sistema y aquí solo se consultan.
 */
@Service
public class TaskService {

    /** Única secuencia admitida: quien empieza una tarea la pone en curso, y luego la termina. */
    private static final Map<String, String> SIGUIENTE_ESTADO =
            Map.of(Task.PENDING, Task.IN_PROGRESS, Task.IN_PROGRESS, Task.COMPLETED);

    private final TaskRepository tasks;
    private final UserRepository users;
    private final DiagramService diagrams;
    private final NotificationService notifications;

    public TaskService(
            TaskRepository tasks, UserRepository users, DiagramService diagrams, NotificationService notifications) {
        this.tasks = tasks;
        this.users = users;
        this.diagrams = diagrams;
        this.notifications = notifications;
    }

    /** Datos de una tarea manual nueva. */
    public record NewTask(UUID diagramId, UUID assignedTo, String title, String description) {}

    @Transactional
    public Task create(AuthPrincipal p, NewTask input) {
        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw ApiException.validation("El título de la tarea es obligatorio.");
        }
        if (title.length() > 200) {
            throw ApiException.validation("El título no puede superar los 200 caracteres.");
        }
        if (input.diagramId() == null) {
            throw ApiException.validation("Debe indicar el diagrama de la tarea.");
        }
        if (input.assignedTo() == null) {
            throw ApiException.validation("Debe indicar a quién se asigna la tarea.");
        }
        // Lanza NOT_FOUND si el diagrama es de otra empresa: no se revela que existe.
        Diagram diagram = diagrams.get(p, input.diagramId());
        assertAssignable(p, input.assignedTo());

        Task task = new Task();
        task.setCompanyId(p.companyId());
        task.setDiagramId(diagram.getId());
        task.setAssignedTo(input.assignedTo());
        task.setCreatedBy(p.userId());
        task.setType(Task.MANUAL);
        task.setTitle(title);
        task.setDescription(input.description());
        task.setStatus(Task.PENDING);
        Task saved = tasks.saveAndFlush(task);

        notifications.notify(saved.getAssignedTo(), NotificationService.TASK_ASSIGNED, "Tarea asignada", saved.getTitle(),
                Map.of("taskId", saved.getId().toString(), "diagramId", diagram.getId().toString()));
        return saved;
    }

    /** El asignado debe ser un usuario activo de la misma empresa. */
    private void assertAssignable(AuthPrincipal p, UUID assignedTo) {
        User assignee = users.findByIdAndCompanyId(assignedTo, p.companyId())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.USER_NOT_ELIGIBLE, "El usuario asignado no pertenece a su empresa."));
        if (!assignee.isActive()) {
            throw new ApiException(ErrorCode.USER_NOT_ELIGIBLE, "No se puede asignar una tarea a un usuario inactivo.");
        }
    }

    /**
     * Avanza el estado de una tarea manual. Solo el asignado, quien la creó o el administrador de la empresa,
     * y solo siguiendo la secuencia PENDING → IN_PROGRESS → COMPLETED.
     */
    @Transactional
    public Task updateStatus(AuthPrincipal p, UUID id, String status) {
        Task task = manualTask(p, id);
        boolean puede = p.hasRole(AuthPrincipal.COMPANY_ADMIN)
                || p.userId().equals(task.getAssignedTo())
                || p.userId().equals(task.getCreatedBy());
        if (!puede) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Solo quien tiene la tarea asignada, quien la creó o el administrador pueden cambiarla.");
        }

        String nuevo = status == null ? "" : status.trim().toUpperCase();
        if (!nuevo.equals(SIGUIENTE_ESTADO.get(task.getStatus()))) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE_TRANSITION,
                    "No se puede pasar de " + estadoEnEspanol(task.getStatus()) + " a " + estadoEnEspanol(nuevo) + ".",
                    Map.of("from", task.getStatus(), "to", nuevo));
        }

        task.setStatus(nuevo);
        if (Task.IN_PROGRESS.equals(nuevo)) {
            task.setStartedAt(Instant.now());
        } else {
            task.setCompletedAt(Instant.now());
        }
        Task saved = tasks.saveAndFlush(task);

        // Quien creó la tarea quiere enterarse de que avanza; si se la asignó a sí mismo, no se avisa solo.
        if (saved.getCreatedBy() != null && !saved.getCreatedBy().equals(p.userId())) {
            notifications.notify(saved.getCreatedBy(), NotificationService.TASK_STATUS_CHANGED, "Tarea actualizada",
                    "«" + saved.getTitle() + "» pasó a " + estadoEnEspanol(nuevo) + ".",
                    Map.of("taskId", saved.getId().toString(), "status", nuevo));
        }
        return saved;
    }

    /** Cambios admitidos al editar una tarea manual (CU-21). */
    public record EditTask(UUID assignedTo, String title, String description) {}

    /**
     * Edita una tarea manual: título, descripción y a quién está asignada. Solo quien la creó o el
     * administrador de la empresa; las automáticas no se tocan a mano.
     */
    @Transactional
    public Task update(AuthPrincipal p, UUID id, EditTask input) {
        Task task = manualTask(p, id);
        assertPuedeGestionar(p, task);

        String title = input.title() == null ? "" : input.title().trim();
        if (title.isEmpty()) {
            throw ApiException.validation("El título de la tarea es obligatorio.");
        }
        if (title.length() > 200) {
            throw ApiException.validation("El título no puede superar los 200 caracteres.");
        }
        if (input.assignedTo() == null) {
            throw ApiException.validation("Debe indicar a quién se asigna la tarea.");
        }

        UUID anterior = task.getAssignedTo();
        boolean cambiaAsignado = !input.assignedTo().equals(anterior);
        if (cambiaAsignado) {
            assertAssignable(p, input.assignedTo());
        }

        task.setTitle(title);
        task.setDescription(input.description());
        task.setAssignedTo(input.assignedTo());
        Task saved = tasks.saveAndFlush(task);

        // Solo se avisa a quien la recibe ahora, y no a uno mismo.
        if (cambiaAsignado && !saved.getAssignedTo().equals(p.userId())) {
            notifications.notify(saved.getAssignedTo(), NotificationService.TASK_ASSIGNED, "Tarea asignada",
                    saved.getTitle(),
                    Map.of("taskId", saved.getId().toString(),
                            "diagramId", String.valueOf(saved.getDiagramId())));
        }
        return saved;
    }

    /** Elimina una tarea manual. Solo quien la creó o el administrador de la empresa. */
    @Transactional
    public void delete(AuthPrincipal p, UUID id) {
        Task task = manualTask(p, id);
        assertPuedeGestionar(p, task);
        tasks.delete(task);
    }

    /** La tarea de la empresa del token, siempre que sea manual. */
    private Task manualTask(AuthPrincipal p, UUID id) {
        Task task = tasks.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("La tarea no existe."));
        if (!Task.MANUAL.equals(task.getType())) {
            throw new ApiException(
                    ErrorCode.INVALID_STATE_TRANSITION, "Las tareas automáticas no se pueden cambiar a mano.");
        }
        return task;
    }

    /** Editar y eliminar son cosa de quien la encargó, no de quien la tiene asignada. */
    private void assertPuedeGestionar(AuthPrincipal p, Task task) {
        if (!p.hasRole(AuthPrincipal.COMPANY_ADMIN) && !p.userId().equals(task.getCreatedBy())) {
            throw new ApiException(
                    ErrorCode.FORBIDDEN, "Solo quien creó la tarea o el administrador pueden modificarla.");
        }
    }

    /** Lo que le toca a quien pregunta: las tareas que le asignaron y las automáticas que lanzó él. */
    @Transactional(readOnly = true)
    public List<Task> myTasks(AuthPrincipal p, String status) {
        return status == null || status.isBlank()
                ? tasks.findMine(p.companyId(), p.userId())
                : tasks.findMineByStatus(p.companyId(), p.userId(), status.trim().toUpperCase());
    }

    @Transactional(readOnly = true)
    public List<Task> createdByMe(AuthPrincipal p) {
        return tasks.findByCompanyIdAndCreatedByOrderByCreatedAtDesc(p.companyId(), p.userId());
    }

    @Transactional(readOnly = true)
    public List<Task> list(AuthPrincipal p, UUID diagramId, String status) {
        if (diagramId != null) {
            diagrams.get(p, diagramId); // NOT_FOUND si el diagrama es de otra empresa
        }
        String estado = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        if (diagramId != null && estado != null) {
            return tasks.findByCompanyIdAndDiagramIdAndStatusOrderByCreatedAtDesc(p.companyId(), diagramId, estado);
        }
        if (diagramId != null) {
            return tasks.findByCompanyIdAndDiagramIdOrderByCreatedAtDesc(p.companyId(), diagramId);
        }
        if (estado != null) {
            return tasks.findByCompanyIdAndStatusOrderByCreatedAtDesc(p.companyId(), estado);
        }
        return tasks.findByCompanyIdOrderByCreatedAtDesc(p.companyId());
    }

    /**
     * Nombre con el que mostrar a una persona de la empresa. Devuelve `null` si no se puede resolver (por
     * ejemplo, una tarea automática sin asignado): la interfaz lo trata como «sin asignar».
     */
    @Transactional(readOnly = true)
    public String displayName(AuthPrincipal p, UUID userId) {
        if (userId == null) return null;
        return users.findByIdAndCompanyId(userId, p.companyId())
                .map(u -> u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName())
                .orElse(null);
    }

    private static String estadoEnEspanol(String status) {
        return switch (status) {
            case Task.PENDING -> "pendiente";
            case Task.IN_PROGRESS -> "en curso";
            case Task.COMPLETED -> "completada";
            case Task.FAILED -> "fallida";
            default -> status;
        };
    }
}
