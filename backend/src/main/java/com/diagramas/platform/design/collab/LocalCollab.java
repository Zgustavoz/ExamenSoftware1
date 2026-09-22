package com.diagramas.platform.design.collab;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Implementación en memoria (un solo nodo, sin Redis ni RabbitMQ): Ciclo C2 y desarrollo local. */
@Component
@ConditionalOnProperty(name = "app.collab.distributed", havingValue = "false", matchIfMissing = true)
public class LocalCollab extends AbstractCollab {

    private record Lock(UUID userId, long expiresAt) {}

    private final Map<UUID, Set<Participant>> participants = new ConcurrentHashMap<>();
    private final Map<String, Lock> locks = new ConcurrentHashMap<>();

    @Override
    protected void addParticipant(UUID diagramId, Participant participant) {
        participants.computeIfAbsent(diagramId, k -> ConcurrentHashMap.newKeySet()).add(participant);
    }

    @Override
    protected void removeParticipant(UUID diagramId, Participant participant) {
        Set<Participant> set = participants.get(diagramId);
        if (set != null) {
            set.remove(participant);
        }
    }

    @Override
    public List<Participant> participants(UUID diagramId) {
        return new ArrayList<>(participants.getOrDefault(diagramId, Set.of()));
    }

    @Override
    public void touch(UUID diagramId) {
        // sin TTL de presencia en memoria: la sesión se limpia al desconectarse
    }

    private static String key(UUID diagramId, String elementId) {
        return diagramId + ":" + elementId;
    }

    @Override
    protected Optional<UUID> lockHolder(UUID diagramId, String elementId) {
        Lock l = locks.get(key(diagramId, elementId));
        return l == null || l.expiresAt() < System.currentTimeMillis() ? Optional.empty() : Optional.of(l.userId());
    }

    @Override
    protected boolean tryAcquire(UUID diagramId, String elementId, UUID userId) {
        long now = System.currentTimeMillis();
        boolean[] acquired = {false};
        locks.compute(key(diagramId, elementId), (k, current) -> {
            if (current == null || current.expiresAt() < now) {
                acquired[0] = true;
                return new Lock(userId, now + LOCK_TTL_MS);
            }
            return current;
        });
        return acquired[0];
    }

    @Override
    protected void refreshLock(UUID diagramId, String elementId, UUID userId) {
        locks.computeIfPresent(key(diagramId, elementId),
                (k, l) -> l.userId().equals(userId) ? new Lock(userId, System.currentTimeMillis() + LOCK_TTL_MS) : l);
    }

    @Override
    protected void release(UUID diagramId, String elementId, UUID userId) {
        locks.computeIfPresent(key(diagramId, elementId), (k, l) -> l.userId().equals(userId) ? null : l);
    }
}
