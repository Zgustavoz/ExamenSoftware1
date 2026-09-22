package com.diagramas.platform.support.notification;

import com.diagramas.platform.access.repository.UserRepository;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Envía el push de forma asíncrona. Un fallo se registra pero nunca rompe el flujo que lo originó. */
@Component
public class PushDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PushDispatcher.class);

    private final PushSender sender;
    private final UserRepository users;

    public PushDispatcher(PushSender sender, UserRepository users) {
        this.sender = sender;
        this.users = users;
    }

    @Async
    @Transactional
    public void dispatch(UUID userId, String title, String message, Map<String, String> data) {
        try {
            var user = users.findById(userId).orElse(null);
            if (user == null || user.getFcmToken() == null || user.getFcmToken().isBlank()) {
                return;
            }
            try {
                sender.send(user.getFcmToken(), title, message == null ? "" : message, data);
            } catch (PushSender.InvalidTokenException e) {
                user.setFcmToken(null); // token expirado/inválido: se descarta
                user.setFcmUpdatedAt(null);
                users.save(user);
                log.info("Token FCM inválido para el usuario {}: eliminado", userId);
            }
        } catch (Exception e) {
            log.warn("Falló el envío push al usuario {}: {}", userId, e.getMessage());
        }
    }
}
