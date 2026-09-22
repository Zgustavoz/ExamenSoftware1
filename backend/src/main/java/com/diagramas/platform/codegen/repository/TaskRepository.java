package com.diagramas.platform.codegen.repository;

import com.diagramas.platform.codegen.domain.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

    Optional<Task> findByIdAndCompanyId(UUID id, UUID companyId);

    List<Task> findByCompanyIdOrderByCreatedAtDesc(UUID companyId);

    List<Task> findByCompanyIdAndDiagramIdOrderByCreatedAtDesc(UUID companyId, UUID diagramId);

    List<Task> findByCompanyIdAndStatusOrderByCreatedAtDesc(UUID companyId, String status);

    List<Task> findByCompanyIdAndDiagramIdAndStatusOrderByCreatedAtDesc(UUID companyId, UUID diagramId, String status);

    List<Task> findByCompanyIdAndCreatedByOrderByCreatedAtDesc(UUID companyId, UUID createdBy);

    /** CU-21 «mis tareas»: las que me asignaron, más las automáticas que lancé yo. */
    @Query("""
            select t from Task t
            where t.companyId = :companyId
              and (t.assignedTo = :userId or (t.createdBy = :userId and t.type <> 'MANUAL'))
            order by t.createdAt desc
            """)
    List<Task> findMine(@Param("companyId") UUID companyId, @Param("userId") UUID userId);

    @Query("""
            select t from Task t
            where t.companyId = :companyId
              and t.status = :status
              and (t.assignedTo = :userId or (t.createdBy = :userId and t.type <> 'MANUAL'))
            order by t.createdAt desc
            """)
    List<Task> findMineByStatus(
            @Param("companyId") UUID companyId, @Param("userId") UUID userId, @Param("status") String status);
}
