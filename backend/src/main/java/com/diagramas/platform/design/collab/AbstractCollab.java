package com.diagramas.platform.design.collab;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Lógica común: seguimiento de sesiones y liberación de locks. Subclases aportan el almacén de estado. */
abstract class AbstractCollab implements CollabPort {

    protected static final long LOCK_TTL_MS = 30_000;

    /** Presencia de una sesión; existe solo si llegó su {@code join}. */
    private record Session(UUID diagramId, UUID userId, String username) {}

    /** Lock tomado por una sesión, con todo lo necesario para liberarlo sin depender del join. */
    private record LockRef(UUID diagramId, String elementId, UUID userId) {}

    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    /**
     * Locks por sesión, independientes de {@link #sessions}: Spring procesa el canal STOMP entrante con
     * varios hilos, así que un {@code lock} puede llegar a ejecutarse antes que su {@code join}. Si los
     * locks colgaran de la sesión, los de ese caso no se liberarían nunca al desconectarse.
     */
    private final Map<String, Set<LockRef>> sessionLocks = new ConcurrentHashMap<>();

    // ---- primitivas del almacén

    protected abstract void addParticipant(UUID diagramId, Participant participant);

    protected abstract void removeParticipant(UUID diagramId, Participant participant);

    /** Devuelve el usuario que sostiene el lock, si existe y no expiró. */
    protected abstract Optional<UUID> lockHolder(UUID diagramId, String elementId);

    /** Intenta tomar el lock (SET NX PX). @return true si se tomó ahora. */
    protected abstract boolean tryAcquire(UUID diagramId, String elementId, UUID userId);

    protected abstract void refreshLock(UUID diagramId, String elementId, UUID userId);

    protected abstract void release(UUID diagramId, String elementId, UUID userId);

    // ---- CollabPort

    @Override
    public List<Participant> join(UUID diagramId, String sessionId, UUID userId, String username) {
        Session previous = sessions.get(sessionId);
        if (previous != null && !previous.diagramId().equals(diagramId)) {
            leave(sessionId); // la sesión se movió a otro diagrama: suelta lo que tenía en el anterior
        }
        sessions.put(sessionId, new Session(diagramId, userId, username));
        addParticipant(diagramId, new Participant(userId, username));
        return participants(diagramId);
    }

    @Override
    public void ensurePresence(UUID diagramId, String sessionId, UUID userId, String username) {
        if (sessionId != null && !sessions.containsKey(sessionId)) {
            join(diagramId, sessionId, userId, username);
        }
    }

    @Override
    public SessionCleanup leave(String sessionId) {
        Session s = sessions.remove(sessionId);
        Set<LockRef> locks = sessionLocks.remove(sessionId);

        List<String> released = new ArrayList<>();
        UUID diagramId = s == null ? null : s.diagramId();
        UUID userId = s == null ? null : s.userId();
        if (locks != null) {
            for (LockRef ref : locks) {
                release(ref.diagramId(), ref.elementId(), ref.userId());
                released.add(ref.elementId());
                if (diagramId == null) {
                    diagramId = ref.diagramId();
                    userId = ref.userId();
                }
            }
        }
        if (s == null && released.isEmpty()) {
            return null; // la sesión no estaba unida ni tenía locks: nada que anunciar
        }

        boolean otherSession = s != null && hasOtherSession(sessionId, s);
        Participant participant = new Participant(userId, s == null ? null : s.username());
        if (s != null && !otherSession) {
            removeParticipant(s.diagramId(), participant);
        }
        return new SessionCleanup(diagramId, participant, s != null && !otherSession, released);
    }

    private boolean hasOtherSession(String sessionId, Session gone) {
        return sessions.entrySet().stream()
                .anyMatch(e -> !e.getKey().equals(sessionId)
                        && e.getValue().diagramId().equals(gone.diagramId())
                        && e.getValue().userId().equals(gone.userId()));
    }

    @Override
    public boolean lock(String sessionId, UUID diagramId, String elementId, UUID userId) {
        boolean acquired = tryAcquire(diagramId, elementId, userId);
        if (!acquired) {
            Optional<UUID> holder = lockHolder(diagramId, elementId);
            if (holder.isEmpty()) {
                acquired = tryAcquire(diagramId, elementId, userId); // expiró entre ambos pasos
            } else if (!holder.get().equals(userId)) {
                throw locked(diagramId, elementId, holder.get());
            } else {
                refreshLock(diagramId, elementId, userId); // mismo usuario: renueva el TTL
            }
        }
        if (!acquired && lockHolder(diagramId, elementId).filter(userId::equals).isEmpty()) {
            throw new ApiException(ErrorCode.ELEMENT_LOCKED, "El elemento está siendo editado por otro usuario.");
        }
        if (sessionId != null) {
            sessionLocks.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet())
                    .add(new LockRef(diagramId, elementId, userId));
        }
        return acquired;
    }

    private ApiException locked(UUID diagramId, String elementId, UUID holder) {
        String who = participants(diagramId).stream()
                .filter(p -> p.userId().equals(holder))
                .map(Participant::username)
                .findFirst()
                .orElse("otro usuario");
        return new ApiException(
                ErrorCode.ELEMENT_LOCKED,
                "El elemento está siendo editado por " + who + ".",
                Map.of("elementId", elementId, "lockedBy", holder.toString(), "lockedByName", who));
    }

    @Override
    public void unlock(String sessionId, UUID diagramId, String elementId, UUID userId) {
        release(diagramId, elementId, userId);
        Set<LockRef> locks = sessionId == null ? null : sessionLocks.get(sessionId);
        if (locks != null) {
            locks.remove(new LockRef(diagramId, elementId, userId));
        }
    }
}
