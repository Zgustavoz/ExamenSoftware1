package com.diagramas.platform.copilot.repository;

import com.diagramas.platform.copilot.domain.AiChat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiChatRepository extends JpaRepository<AiChat, UUID> {

    List<AiChat> findByDiagramIdAndUserIdOrderByCreatedAtAsc(UUID diagramId, UUID userId);

    Optional<AiChat> findFirstByDiagramIdAndUserIdOrderByCreatedAtDesc(UUID diagramId, UUID userId);
}
