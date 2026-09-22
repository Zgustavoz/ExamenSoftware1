package com.diagramas.platform.design.collab;

import java.util.List;
import java.util.UUID;

/**
 * Puerto de colaboración (9.1 / 10.5). {@code LocalCollab} (un solo nodo, en memoria) y
 * {@code RedisRabbitCollab} (estado en Redis; difusión por el broker relay de RabbitMQ) lo implementan
 * sin que {@code DiagramService} ni los controladores cambien.
 */
public interface CollabPort {

    record Participant(UUID userId, String username) {}

    /** Resultado de limpiar una sesión que se fue: a quién avisar y qué locks se liberaron. */
    record SessionCleanup(UUID diagramId, Participant participant, boolean participantGone, List<String> releasedLocks) {}

    /** Registra al participante en el diagrama y devuelve los participantes actuales. */
    List<Participant> join(UUID diagramId, String sessionId, UUID userId, String username);

    /**
     * Asegura que la sesión figure como presente, sin anunciar nada. Es necesario porque Spring procesa
     * el canal STOMP entrante con varios hilos y no garantiza el orden dentro de una sesión: un
     * {@code lock} o una operación pueden ejecutarse antes que su {@code join}. Sin esto, la sesión no
     * quedaría registrada y al desconectarse no se anunciaría su salida.
     */
    void ensurePresence(UUID diagramId, String sessionId, UUID userId, String username);

    /** @return la limpieza realizada, o null si la sesión no estaba unida */
    SessionCleanup leave(String sessionId);

    /** Igual que {@link #leave} pero ante una desconexión abrupta. */
    default SessionCleanup disconnect(String sessionId) {
        return leave(sessionId);
    }

    /**
     * Toma el lock del elemento para el usuario.
     *
     * @return true si el lock se adquirió ahora; false si ya lo tenía el mismo usuario
     * @throws com.diagramas.platform.common.error.ApiException ELEMENT_LOCKED si lo tiene otro usuario
     */
    boolean lock(String sessionId, UUID diagramId, String elementId, UUID userId);

    /** Libera el lock si pertenece al usuario. */
    void unlock(String sessionId, UUID diagramId, String elementId, UUID userId);

    List<Participant> participants(UUID diagramId);

    /** Renueva la presencia (heartbeat) del usuario en el diagrama. */
    void touch(UUID diagramId);
}
