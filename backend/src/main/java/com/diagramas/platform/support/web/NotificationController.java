package com.diagramas.platform.support.web;

import com.diagramas.platform.access.dto.AccessDtos.PageResponse;
import com.diagramas.platform.common.security.CurrentUser;
import com.diagramas.platform.support.notification.NotificationService;
import com.diagramas.platform.support.notification.NotificationService.NotificationDto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class NotificationController {

    public record FcmTokenRequest(@NotBlank(message = "El token es obligatorio") @Size(max = 500) String token) {}

    private final NotificationService notifications;

    public NotificationController(NotificationService notifications) {
        this.notifications = notifications;
    }

    @GetMapping("/api/notifications")
    public PageResponse<NotificationDto> list(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<NotificationDto> result = notifications.list(CurrentUser.get(), unreadOnly, page, size);
        return PageResponse.of(result, result.getContent());
    }

    @PatchMapping("/api/notifications/{id}/read")
    public NotificationDto read(@PathVariable UUID id) {
        return notifications.markRead(CurrentUser.get(), id);
    }

    @PutMapping("/api/me/fcm-token")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void fcmToken(@Valid @RequestBody FcmTokenRequest req) {
        notifications.updateFcmToken(CurrentUser.get(), req.token());
    }
}
