package com.diagramas.platform.access.repository;

import com.diagramas.platform.access.domain.Project;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

    Optional<Project> findByIdAndCompanyId(UUID id, UUID companyId);

    boolean existsByCompanyIdAndNameIgnoreCase(UUID companyId, String name);

    /** {@code pattern} ya viene en minúsculas y con comodines (p. ej. %texto%). */
    @Query("""
            select p from Project p
            where p.companyId = :companyId
              and (lower(p.name) like :pattern or lower(coalesce(p.description, '')) like :pattern)
            """)
    Page<Project> search(@Param("companyId") UUID companyId, @Param("pattern") String pattern, Pageable pageable);
}
