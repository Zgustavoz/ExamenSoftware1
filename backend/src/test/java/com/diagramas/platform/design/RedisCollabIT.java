package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.design.collab.CollabPort;
import com.diagramas.platform.design.collab.RedisRabbitCollab;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/** Estado efímero real en Redis (10.5): SET NX PX 30000, presencia, liberación al desconectar y visibilidad entre instancias. */
class RedisCollabIT {

    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate template;

    @BeforeAll
    static void start() {
        redis.start();
        factory = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
        factory.afterPropertiesSet();
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        factory.destroy();
        redis.stop();
    }

    private final UUID diagram = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void lockExclusivoConTtlYReentranteParaElMismoUsuario() {
        RedisRabbitCollab collab = new RedisRabbitCollab(template);
        collab.join(diagram, "s-alice", alice, "alice");
        collab.join(diagram, "s-bob", bob, "bob");

        assertThat(collab.lock("s-alice", diagram, "c1", alice)).isTrue();
        assertThat(collab.lock("s-alice", diagram, "c1", alice)).isFalse(); // ya era suyo

        Long ttl = template.getExpire("collab:" + diagram + ":lock:c1", java.util.concurrent.TimeUnit.MILLISECONDS);
        assertThat(ttl).isBetween(1L, 30_000L);
        assertThat(template.opsForValue().get("collab:" + diagram + ":lock:c1")).isEqualTo(alice.toString());

        assertThatThrownBy(() -> collab.lock("s-bob", diagram, "c1", bob))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ELEMENT_LOCKED);
                    assertThat(e.getMessage()).contains("alice");
                });

        // bob no puede liberar un lock ajeno
        collab.unlock("s-bob", diagram, "c1", bob);
        assertThat(template.hasKey("collab:" + diagram + ":lock:c1")).isTrue();
        collab.unlock("s-alice", diagram, "c1", alice);
        assertThat(collab.lock("s-bob", diagram, "c1", bob)).isTrue();
    }

    @Test
    void presenciaYLiberacionAlDesconectar() {
        RedisRabbitCollab collab = new RedisRabbitCollab(template);
        collab.join(diagram, "s1", alice, "alice");
        collab.join(diagram, "s2", bob, "bob");
        assertThat(collab.participants(diagram)).extracting(CollabPort.Participant::username).containsExactlyInAnyOrder("alice", "bob");
        assertThat(template.opsForSet().members("collab:" + diagram + ":participants")).hasSize(2);

        collab.lock("s1", diagram, "c1", alice);
        collab.lock("s1", diagram, "c2", alice);
        CollabPort.SessionCleanup cleanup = collab.disconnect("s1");
        assertThat(cleanup.releasedLocks()).containsExactlyInAnyOrder("c1", "c2");
        assertThat(cleanup.participantGone()).isTrue();
        assertThat(template.hasKey("collab:" + diagram + ":lock:c1")).isFalse();
        assertThat(collab.participants(diagram)).extracting(CollabPort.Participant::username).containsExactly("bob");
    }

    @Test
    void dosInstanciasDelBackendCompartenElEstado() {
        RedisRabbitCollab nodeA = new RedisRabbitCollab(template);
        RedisRabbitCollab nodeB = new RedisRabbitCollab(template);
        nodeA.join(diagram, "a1", alice, "alice");
        nodeB.join(diagram, "b1", bob, "bob");
        assertThat(nodeA.participants(diagram)).hasSize(2);
        assertThat(nodeB.participants(diagram)).hasSize(2);

        nodeA.lock("a1", diagram, "c9", alice);
        assertThatThrownBy(() -> nodeB.lock("b1", diagram, "c9", bob))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.ELEMENT_LOCKED));
        nodeA.disconnect("a1");
        assertThat(nodeB.lock("b1", diagram, "c9", bob)).isTrue();
    }
}
