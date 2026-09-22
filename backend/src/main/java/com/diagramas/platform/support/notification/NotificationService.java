package com.diagramas.platform.support.notification;

import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** CU-19: inserta la notificación y, DESPUÉS del commit, dispara el push por FCM de forma asíncrona. */
@Service
public class NotificationService {

    public static final String TASK_ASSIGNED = "TASK_ASSIGNED";
    public static final String CODE_READY = "CODE_READY";
    public static final String TASK_STATUS_CHANGED = "TASK_STATUS_CHANGED";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository notifications;
    private final UserRepository users;
    private final PushDispatcher push;

    public NotificationService(NotificationRepository notifications, UserRepository users, PushDispatcher push) {
        this.notifications = notifications;
        this.users = users;
        this.push = push;
    }

    public record NotificationDto(
            UUID id, String title, String message, String type, Object payload, boolean read, Instant createdAt) {
        static NotificationDto of(Notification n) {
            return new NotificationDto(n.getId(), n.getTitle(), n.getMessage(), n.getType(),
                    Json.plain(n.getPayloadJson()), n.isRead(), n.getCreatedAt());
        }
    }

    /** Se une a la transacción del llamador; jamás propaga fallos del push. */
    @Transactional
    public void notify(UUID userId, String type, String title, String message, Map<String, Object> payload) {
        User user = users.findById(userId).orElse(null);
        if (user == null) {
            log.warn("No se puede notificar: usuario {} inexistente", userId);
            return;
        }
        Notification n = new Notification();
        n.setCompanyId(user.getCompanyId());
        n.setUserId(userId);
        n.setType(type);
        n.setTitle(title);
        n.setMessage(message);
        n.setPayloadJson(Json.fromPlain(payload == null ? Map.of() : payload));
        notifications.save(n);

        Map<String, String> data = new LinkedHashMap<>();
        data.put("type", type);
        data.put("notificationId", String.valueOf(n.getId()));
        if (payload != null) {
            payload.forEach((k, v) -> data.put(k, String.valueOf(v)));
        }
        Runnable send = () -> push.dispatch(userId, title, message, data);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send.run();
                }
            });
        } else {
            send.run();
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationDto> list(AuthPrincipal p, boolean unreadOnly, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), Sort.by("createdAt").descending());
        Page<Notification> result = unreadOnly
                ? notifications.findByUserIdAndCompanyIdAndReadFalse(p.userId(), p.companyId(), pageable)
                : notifications.findByUserIdAndCompanyId(p.userId(), p.companyId(), pageable);
        return result.map(NotificationDto::of);
    }

    @Transactional
    public NotificationDto markRead(AuthPrincipal p, UUID id) {
        Notification n = notifications.findByIdAndUserIdAndCompanyId(id, p.userId(), p.companyId())
                .orElseThrow(() -> ApiException.notFound("La notificación no existe."));
        n.setRead(true);
        return NotificationDto.of(notifications.save(n));
    }

    @Transactional
    public void updateFcmToken(AuthPrincipal p, String token) {
        User user = users.findByIdAndCompanyId(p.userId(), p.companyId())
                .orElseThrow(() -> ApiException.notFound("El usuario no existe."));
        user.setFcmToken(token.trim());
        user.setFcmUpdatedAt(Instant.now());
        users.save(user);
    }
}
