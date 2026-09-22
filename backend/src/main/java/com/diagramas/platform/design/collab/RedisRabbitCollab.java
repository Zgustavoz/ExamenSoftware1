package com.diagramas.platform.design.collab;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * Implementación distribuida (Ciclo C4): estado efímero en Redis (no en BD). La difusión de mensajes
 * la hace el STOMP broker relay hacia RabbitMQ (ver WebSocketConfig), de modo que varias instancias convergen.
 *
 * <pre>
 * collab:{diagramId}:participants            SET de "userId|username" (con TTL renovable)
 * collab:{diagramId}:lock:{elementId} = userId   SET NX PX 30000
 * </pre>
 */
@Component
@ConditionalOnProperty(name = "app.collab.distributed", havingValue = "true")
public class RedisRabbitCollab extends AbstractCollab {

    private static final Duration PRESENCE_TTL = Duration.ofMinutes(5);
    private static final DefaultRedisScript<Long> RELEASE_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
            Long.class);
    private static final DefaultRedisScript<Long> REFRESH_IF_OWNER = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
            Long.class);

    private final StringRedisTemplate redis;

    public RedisRabbitCollab(StringRedisTemplate redis) {
        this.redis = redis;
    }

    private static String participantsKey(UUID diagramId) {
        return "collab:" + diagramId + ":participants";
    }

    private static String lockKey(UUID diagramId, String elementId) {
        return "collab:" + diagramId + ":lock:" + elementId;
    }

    @Override
    protected void addParticipant(UUID diagramId, Participant p) {
        redis.opsForSet().add(participantsKey(diagramId), p.userId() + "|" + p.username());
        redis.expire(participantsKey(diagramId), PRESENCE_TTL);
    }

    @Override
    protected void removeParticipant(UUID diagramId, Participant p) {
        redis.opsForSet().remove(participantsKey(diagramId), p.userId() + "|" + p.username());
    }

    @Override
    public List<Participant> participants(UUID diagramId) {
        Set<String> raw = redis.opsForSet().members(participantsKey(diagramId));
        List<Participant> result = new ArrayList<>();
        if (raw != null) {
            for (String s : raw) {
                int i = s.indexOf('|');
                result.add(new Participant(UUID.fromString(s.substring(0, i)), s.substring(i + 1)));
            }
        }
        return result;
    }

    @Override
    public void touch(UUID diagramId) {
        redis.expire(participantsKey(diagramId), PRESENCE_TTL);
    }

    @Override
    protected Optional<UUID> lockHolder(UUID diagramId, String elementId) {
        String v = redis.opsForValue().get(lockKey(diagramId, elementId));
        return v == null ? Optional.empty() : Optional.of(UUID.fromString(v));
    }

    @Override
    protected boolean tryAcquire(UUID diagramId, String elementId, UUID userId) {
        Boolean ok = redis.opsForValue()
                .setIfAbsent(lockKey(diagramId, elementId), userId.toString(), Duration.ofMillis(LOCK_TTL_MS));
        return Boolean.TRUE.equals(ok);
    }

    @Override
    protected void refreshLock(UUID diagramId, String elementId, UUID userId) {
        redis.execute(REFRESH_IF_OWNER, List.of(lockKey(diagramId, elementId)), userId.toString(), String.valueOf(LOCK_TTL_MS));
    }

    @Override
    protected void release(UUID diagramId, String elementId, UUID userId) {
        redis.execute(RELEASE_IF_OWNER, List.of(lockKey(diagramId, elementId)), userId.toString());
    }
}
