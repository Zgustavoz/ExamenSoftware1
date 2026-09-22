package com.diagramas.platform.support.notification;

import com.diagramas.platform.common.config.AppProperties;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Selecciona el {@link PushSender}: FCM si hay credenciales de Firebase, ya sea en una sola línea en
 * {@code FCM_CREDENTIALS_JSON} (producción) o en el archivo de {@code FCM_CREDENTIALS_PATH} (desarrollo);
 * si no, un sender que solo registra en log (el flujo principal nunca depende del push).
 */
@Configuration
public class PushConfig {

    private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

    @Bean
    PushSender pushSender(AppProperties props) {
        byte[] credentials;
        try {
            credentials = FcmCredentials.resolve(props.fcm().credentialsJson(), props.fcm().credentialsPath()).orElse(null);
        } catch (IllegalArgumentException e) {
            log.error("Credenciales de Firebase inválidas; push deshabilitado: {}", e.getMessage());
            return (token, title, body, data) -> log.debug("Push omitido (credenciales inválidas)");
        }
        if (credentials == null) {
            log.warn("Sin credenciales de Firebase (FCM_CREDENTIALS_JSON o FCM_CREDENTIALS_PATH): las notificaciones push quedan deshabilitadas");
            return (token, title, body, data) -> log.debug("Push omitido (FCM deshabilitado)");
        }
        try (InputStream in = new ByteArrayInputStream(credentials)) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(in))
                    .build();
            FirebaseApp app = FirebaseApp.getApps().isEmpty() ? FirebaseApp.initializeApp(options) : FirebaseApp.getInstance();
            return new FcmPushSender(FirebaseMessaging.getInstance(app));
        } catch (IOException | RuntimeException e) {
            log.error("No se pudo inicializar Firebase; push deshabilitado: {}", e.getMessage());
            return (token, title, body, data) -> log.debug("Push omitido (FCM no inicializado)");
        }
    }

    static class FcmPushSender implements PushSender {
        private final FirebaseMessaging messaging;

        FcmPushSender(FirebaseMessaging messaging) {
            this.messaging = messaging;
        }

        @Override
        public void send(String token, String title, String body, Map<String, String> data)
                throws InvalidTokenException, FirebaseMessagingException {
            Message message = Message.builder()
                    .setToken(token)
                    .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                    .putAllData(data)
                    .build();
            try {
                messaging.send(message);
            } catch (FirebaseMessagingException e) {
                if (e.getMessagingErrorCode() == MessagingErrorCode.UNREGISTERED
                        || e.getMessagingErrorCode() == MessagingErrorCode.INVALID_ARGUMENT) {
                    throw new InvalidTokenException(e.getMessage());
                }
                throw e;
            }
        }
    }
}
