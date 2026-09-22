package com.diagramas.platform.access.service;

import com.diagramas.platform.access.domain.Company;
import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.dto.AccessDtos.AdminRequest;
import com.diagramas.platform.access.dto.AccessDtos.CompanyDto;
import com.diagramas.platform.access.dto.AccessDtos.CompanyRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.repository.CompanyRepository;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CU-02 Gestionar empresas. Único servicio que no filtra por company_id (solo SOFTWARE_ADMIN). */
@Service
public class CompanyService {

    public static final String PLATFORM_SLUG = "platform";

    private final CompanyRepository companies;
    private final UserRepository users;
    private final PasswordEncoder encoder;

    public CompanyService(CompanyRepository companies, UserRepository users, PasswordEncoder encoder) {
        this.companies = companies;
        this.users = users;
        this.encoder = encoder;
    }

    @Transactional(readOnly = true)
    public List<CompanyDto> list() {
        return companies.findAll().stream()
                .sorted((a, b) -> a.getName().compareToIgnoreCase(b.getName()))
                .map(CompanyDto::of)
                .toList();
    }

    @Transactional
    public CompanyDto create(CompanyRequest req) {
        String slug = req.slug().trim().toLowerCase();
        String name = req.name().trim();
        assertUnique(name, slug, null);
        Company c = new Company();
        c.setName(name);
        c.setSlug(slug);
        return CompanyDto.of(companies.saveAndFlush(c));
    }

    @Transactional
    public CompanyDto update(UUID id, CompanyRequest req) {
        Company c = find(id);
        String slug = req.slug().trim().toLowerCase();
        String name = req.name().trim();
        assertUnique(name, slug, c);
        if (PLATFORM_SLUG.equals(c.getSlug()) && !PLATFORM_SLUG.equals(slug)) {
            throw ApiException.validation("La empresa de la plataforma no puede cambiar de slug.");
        }
        c.setName(name);
        c.setSlug(slug);
        return CompanyDto.of(companies.saveAndFlush(c));
    }

    @Transactional
    public CompanyDto setActive(UUID id, boolean active) {
        Company c = find(id);
        if (!active && PLATFORM_SLUG.equals(c.getSlug())) {
            throw ApiException.validation("La empresa de la plataforma no puede desactivarse.");
        }
        c.setActive(active);
        return CompanyDto.of(companies.save(c));
    }

    /** Crea el COMPANY_ADMIN de una empresa (el Software Admin «administra los administradores»). */
    @Transactional
    public UserDto createAdmin(UUID companyId, AdminRequest req) {
        Company c = find(companyId);
        String username = req.username().trim();
        String email = req.email().trim();
        if (users.existsByCompanyIdAndUsernameIgnoreCase(c.getId(), username)
                || users.existsByCompanyIdAndEmailIgnoreCase(c.getId(), email)) {
            throw new ApiException(ErrorCode.DUPLICATE_USER, "El usuario o el correo ya existe en la empresa.");
        }
        User u = new User();
        u.setCompanyId(c.getId());
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(req.fullName());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRoles(new ArrayList<>(List.of(AuthPrincipal.COMPANY_ADMIN)));
        return UserDto.of(users.saveAndFlush(u));
    }

    private Company find(UUID id) {
        return companies.findById(id).orElseThrow(() -> ApiException.notFound("La empresa no existe."));
    }

    private void assertUnique(String name, String slug, Company current) {
        boolean sameName = current != null && current.getName().equalsIgnoreCase(name);
        boolean sameSlug = current != null && current.getSlug().equals(slug);
        if ((!sameName && companies.existsByNameIgnoreCase(name)) || (!sameSlug && companies.existsBySlug(slug))) {
            throw new ApiException(ErrorCode.DUPLICATE_COMPANY, "Ya existe una empresa con ese nombre o slug.");
        }
    }
}
