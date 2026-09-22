package com.diagramas.platform.support.notification;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserIdAndCompanyId(UUID userId, UUID companyId, Pageable pageable);

    Page<Notification> findByUserIdAndCompanyIdAndReadFalse(UUID userId, UUID companyId, Pageable pageable);

    Optional<Notification> findByIdAndUserIdAndCompanyId(UUID id, UUID userId, UUID companyId);
}
