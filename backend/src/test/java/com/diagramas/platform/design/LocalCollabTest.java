package com.diagramas.platform.design;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.design.collab.CollabPort.Participant;
import com.diagramas.platform.design.collab.CollabPort.SessionCleanup;
import com.diagramas.platform.design.collab.LocalCollab;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Reglas de presencia y locks comunes a las dos implementaciones de {@code CollabPort}. */
class LocalCollabTest {

    private final LocalCollab collab = new LocalCollab();
    private final UUID diagram = UUID.randomUUID();
    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    @Test
    void elLockEsExclusivoYReentranteParaSuDueno() {
        collab.join(diagram, "s-alice", alice, "alice");
        collab.join(diagram, "s-bob", bob, "bob");

        assertThat(collab.lock("s-alice", diagram, "c1", alice)).isTrue();
        assertThat(collab.lock("s-alice", diagram, "c1", alice)).isFalse(); // ya era suyo

        assertThatThrownBy(() -> collab.lock("s-bob", diagram, "c1", bob))
                .isInstanceOfSatisfying(ApiException.class, e -> {
                    assertThat(e.code()).isEqualTo(ErrorCode.ELEMENT_LOCKED);
                    assertThat(e.getMessage()).contains("alice"); // la UI muestra quién edita
                });
    }

    @Test
    void soloElDuenoLibera() {
        collab.join(diagram, "s-alice", alice, "alice");
        collab.join(diagram, "s-bob", bob, "bob");
        collab.lock("s-alice", diagram, "c1", alice);

        collab.unlock("s-bob", diagram, "c1", bob);
        assertThatThrownBy(() -> collab.lock("s-bob", diagram, "c1", bob)).isInstanceOf(ApiException.class);

        collab.unlock("s-alice", diagram, "c1", alice);
        assertThat(collab.lock("s-bob", diagram, "c1", bob)).isTrue();
    }

    @Test
    void alDesconectarSeLiberanLosLocksYSeQuitaElParticipante() {
        collab.join(diagram, "s-alice", alice, "alice");
        collab.join(diagram, "s-bob", bob, "bob");
        collab.lock("s-alice", diagram, "c1", alice);
        collab.lock("s-alice", diagram, "c2", alice);

        SessionCleanup cleanup = collab.disconnect("s-alice");

        assertThat(cleanup.releasedLocks()).containsExactlyInAnyOrder("c1", "c2");
        assertThat(cleanup.participantGone()).isTrue();
        assertThat(cleanup.diagramId()).isEqualTo(diagram);
        assertThat(collab.participants(diagram)).extracting(Participant::username).containsExactly("bob");
        assertThat(collab.lock("s-bob", diagram, "c1", bob)).isTrue();
    }

    /**
     * El canal STOMP entrante se procesa con varios hilos, así que un lock puede ejecutarse antes que su
     * join. Aun así debe liberarse al desconectarse la sesión, o el elemento quedaría bloqueado.
     */
    @Test
    void unLockTomadoAntesDelJoinTambienSeLibera() {
        collab.join(diagram, "s-bob", bob, "bob");
        collab.lock("s-alice", diagram, "c1", alice); // todavía sin join

        SessionCleanup cleanup = collab.disconnect("s-alice");

        assertThat(cleanup).isNotNull();
        assertThat(cleanup.releasedLocks()).containsExactly("c1");
        assertThat(cleanup.diagramId()).isEqualTo(diagram);
        assertThat(cleanup.participantGone()).isFalse(); // nunca llegó a anunciarse como participante
        assertThat(collab.lock("s-bob", diagram, "c1", bob)).isTrue();
    }

    @Test
    void elJoinQueLlegaDespuesDelLockNoLoPierde() {
        collab.lock("s-alice", diagram, "c1", alice);
        collab.join(diagram, "s-alice", alice, "alice"); // el join llega tarde

        SessionCleanup cleanup = collab.disconnect("s-alice");

        assertThat(cleanup.releasedLocks()).containsExactly("c1");
        assertThat(cleanup.participantGone()).isTrue();
    }

    @Test
    void moverLaSesionAOtroDiagramaSueltaLoDelAnterior() {
        UUID otro = UUID.randomUUID();
        collab.join(diagram, "s-alice", alice, "alice");
        collab.lock("s-alice", diagram, "c1", alice);

        collab.join(otro, "s-alice", alice, "alice");

        assertThat(collab.participants(diagram)).isEmpty();
        assertThat(collab.participants(otro)).extracting(Participant::username).containsExactly("alice");
        assertThat(collab.lock("s-bob", diagram, "c1", bob)).isTrue(); // el lock del diagrama anterior se soltó
    }

    @Test
    void conDosSesionesDelMismoUsuarioElParticipanteSiguePresente() {
        collab.join(diagram, "s1", alice, "alice");
        collab.join(diagram, "s2", alice, "alice"); // por ejemplo, dos pestañas

        SessionCleanup cleanup = collab.disconnect("s1");

        assertThat(cleanup.participantGone()).isFalse();
        assertThat(collab.participants(diagram)).extracting(Participant::username).containsExactly("alice");
    }

    @Test
    void ensurePresenceRegistraLaSesionSinDuplicarla() {
        collab.ensurePresence(diagram, "s-alice", alice, "alice");
        collab.ensurePresence(diagram, "s-alice", alice, "alice");

        assertThat(collab.participants(diagram)).extracting(Participant::username).containsExactly("alice");
        assertThat(collab.disconnect("s-alice").participantGone()).isTrue();
    }

    @Test
    void ensurePresenceNoPisaElDiagramaDeUnaSesionYaUnida() {
        collab.join(diagram, "s-alice", alice, "alice");
        collab.lock("s-alice", diagram, "c1", alice);

        collab.ensurePresence(UUID.randomUUID(), "s-alice", alice, "alice");

        SessionCleanup cleanup = collab.disconnect("s-alice");
        assertThat(cleanup.diagramId()).isEqualTo(diagram);
        assertThat(cleanup.releasedLocks()).containsExactly("c1");
    }

    @Test
    void desconectarUnaSesionDesconocidaNoHaceNada() {
        assertThat(collab.disconnect("no-existe")).isNull();
    }
}
