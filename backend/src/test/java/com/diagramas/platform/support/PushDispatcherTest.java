package com.diagramas.platform.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.support.notification.PushDispatcher;
import com.diagramas.platform.support.notification.PushSender;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** CU-19 excepciones: token FCM expirado → se borra; un fallo de envío nunca rompe el flujo principal. */
class PushDispatcherTest {

    private final PushSender sender = mock(PushSender.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PushDispatcher dispatcher = new PushDispatcher(sender, users);

    private User userWithToken(UUID id) {
        User u = new User();
        u.setId(id);
        u.setFcmToken("tok");
        u.setFcmUpdatedAt(Instant.now());
        when(users.findById(id)).thenReturn(Optional.of(u));
        return u;
    }

    @Test
    void enviaElPushConElTokenDelUsuario() throws Exception {
        UUID id = UUID.randomUUID();
        userWithToken(id);
        dispatcher.dispatch(id, "Título", "Mensaje", Map.of("type", "CODE_READY"));
        verify(sender).send(eq("tok"), eq("Título"), eq("Mensaje"), any());
    }

    @Test
    void tokenExpiradoSeEliminaDelUsuario() throws Exception {
        UUID id = UUID.randomUUID();
        User u = userWithToken(id);
        doThrow(new PushSender.InvalidTokenException("UNREGISTERED")).when(sender).send(any(), any(), any(), any());
        dispatcher.dispatch(id, "t", "m", Map.of());
        assertThat(u.getFcmToken()).isNull();
        verify(users).save(u);
    }

    @Test
    void falloDeEnvioNoPropagaExcepcion() throws Exception {
        UUID id = UUID.randomUUID();
        User u = userWithToken(id);
        doThrow(new RuntimeException("FCM caído")).when(sender).send(any(), any(), any(), any());
        assertThatCode(() -> dispatcher.dispatch(id, "t", "m", Map.of())).doesNotThrowAnyException();
        assertThat(u.getFcmToken()).isEqualTo("tok"); // un fallo transitorio no borra el token
    }

    @Test
    void sinTokenNoEnviaNada() throws Exception {
        UUID id = UUID.randomUUID();
        User u = new User();
        u.setId(id);
        when(users.findById(id)).thenReturn(Optional.of(u));
        dispatcher.dispatch(id, "t", "m", Map.of());
        verify(sender, never()).send(any(), any(), any(), any());
    }
}
