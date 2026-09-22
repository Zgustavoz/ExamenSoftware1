package com.diagramas.platform.access.repository;

import com.diagramas.platform.access.domain.User;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByCompanyIdAndUsername(UUID companyId, String username);

    Optional<User> findByIdAndCompanyId(UUID id, UUID companyId);

    List<User> findByCompanyIdOrderByUsernameAsc(UUID companyId);

    boolean existsByCompanyIdAndUsernameIgnoreCase(UUID companyId, String username);

    boolean existsByCompanyIdAndEmailIgnoreCase(UUID companyId, String email);
}
