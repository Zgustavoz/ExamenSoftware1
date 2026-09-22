package com.diagramas.platform.design.repository;

import com.diagramas.platform.design.domain.Diagram;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DiagramRepository extends JpaRepository<Diagram, UUID> {

    Optional<Diagram> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByIdAndCompanyId(UUID id, UUID companyId);

    List<Diagram> findByProjectIdAndCompanyIdOrderByNameAsc(UUID projectId, UUID companyId);

    boolean existsByProjectIdAndNameIgnoreCase(UUID projectId, String name);

    /** Bloqueo de fila: serializa las escrituras por diagrama (orden total por version). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from Diagram d where d.id = :id and d.companyId = :companyId")
    Optional<Diagram> findForUpdate(@Param("id") UUID id, @Param("companyId") UUID companyId);
}
