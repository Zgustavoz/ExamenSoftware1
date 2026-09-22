package com.diagramas.platform.access.service;

import com.diagramas.platform.access.domain.Company;
import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.dto.AccessDtos.AdminAccount;
import com.diagramas.platform.access.dto.AccessDtos.LoginResponse;
import com.diagramas.platform.access.dto.AccessDtos.RegisterCompanyRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.repository.CompanyRepository;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.security.JwtService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CU-01 Iniciar sesión. */
@Service
public class AuthService {

    private static final String BAD_CREDENTIALS = "Empresa, usuario o contraseña incorrectos.";

    private final CompanyRepository companies;
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtService jwt;

    public AuthService(CompanyRepository companies, UserRepository users, PasswordEncoder encoder, JwtService jwt) {
        this.companies = companies;
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
    }

    @Transactional(readOnly = true)
    public LoginResponse login(String companySlug, String username, String password) {
        Company company = companies.findBySlug(companySlug.trim().toLowerCase())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS));
        User user = users.findByCompanyIdAndUsername(company.getId(), username.trim())
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS));
        // Primero la contraseña: no se revela el estado de las cuentas a quien no la conoce.
        if (!encoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS, BAD_CREDENTIALS);
        }
        if (!company.isActive()) {
            throw new ApiException(ErrorCode.COMPANY_DISABLED, "La empresa está deshabilitada.");
        }
        if (!user.isActive()) {
            throw new ApiException(ErrorCode.USER_INACTIVE, "El usuario está inactivo.");
        }
        String token = jwt.issue(user.getId(), user.getCompanyId(), user.getRoles());
        return new LoginResponse(token, UserDto.of(user));
    }

    /**
     * CU-22 Registrar empresa. Crea la empresa y su primer COMPANY_ADMIN en una sola transacción y deja la
     * sesión iniciada, igual que un login. Es público: no hay ningún usuario autenticado todavía.
     */
    @Transactional
    public LoginResponse registerCompany(RegisterCompanyRequest req) {
        String slug = req.companySlug().trim().toLowerCase();
        String name = req.companyName().trim();
        if (companies.existsBySlug(slug) || companies.existsByNameIgnoreCase(name)) {
            throw new ApiException(ErrorCode.DUPLICATE_COMPANY, "Ya existe una empresa con ese nombre o identificador.");
        }

        Company company = new Company();
        company.setName(name);
        company.setSlug(slug);
        AdminAccount account = req.admin();
        User admin = new User();
        admin.setUsername(account.username().trim());
        admin.setEmail(account.email().trim());
        admin.setFullName(account.fullName());
        admin.setPasswordHash(encoder.encode(account.password()));
        admin.setRoles(new ArrayList<>(List.of(AuthPrincipal.COMPANY_ADMIN)));
        try {
            company = companies.saveAndFlush(company);
            admin.setCompanyId(company.getId());
            admin = users.saveAndFlush(admin);
        } catch (DataIntegrityViolationException e) {
            // Dos registros a la vez con el mismo slug: la restricción de la BD es la que decide.
            throw new ApiException(ErrorCode.DUPLICATE_COMPANY, "Ya existe una empresa con ese nombre o identificador.");
        }
        return new LoginResponse(jwt.issue(admin.getId(), company.getId(), admin.getRoles()), UserDto.of(admin));
    }

    @Transactional(readOnly = true)
    public UserDto me(AuthPrincipal principal) {
        User user = users.findByIdAndCompanyId(principal.userId(), principal.companyId())
                .orElseThrow(() -> ApiException.notFound("El usuario no existe."));
        return UserDto.of(user);
    }
}
