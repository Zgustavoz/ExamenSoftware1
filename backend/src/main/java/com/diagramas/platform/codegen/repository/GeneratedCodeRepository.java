package com.diagramas.platform.codegen.repository;

import com.diagramas.platform.codegen.domain.GeneratedCode;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GeneratedCodeRepository extends JpaRepository<GeneratedCode, UUID> {

    List<GeneratedCode> findByTaskIdOrderByFileNameAsc(UUID taskId);

    List<GeneratedCode> findByDiagramIdOrderByCreatedAtDescFileNameAsc(UUID diagramId);
}
