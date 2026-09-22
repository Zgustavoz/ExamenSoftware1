package com.diagramas.platform.access.service;

import com.diagramas.platform.access.domain.User;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.dto.AccessDtos.UserRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserUpdateRequest;
import com.diagramas.platform.access.repository.UserRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** CU-03 Gestionar usuarios de empresa: siempre dentro de la empresa del JWT. */
@Service
public class UserService {

    /** Roles asignables por un COMPANY_ADMIN; SOFTWARE_ADMIN nunca. */
    private static final Set<String> ASSIGNABLE_ROLES =
            Set.of(AuthPrincipal.COMPANY_ADMIN, AuthPrincipal.DESIGNER, AuthPrincipal.DEVELOPER);

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public UserService(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
    }

    /**
     * CU-21: a quién se puede asignar una tarea. Cualquier rol necesita esta lista para asignar, así que
     * devuelve solo lo imprescindible para elegir a una persona, no la ficha completa de CU-03.
     */
    @Transactional(readOnly = true)
    public List<AssignableUser> assignable(AuthPrincipal p) {
        return users.findByCompanyIdOrderByUsernameAsc(p.companyId()).stream()
                .filter(User::isActive)
                .map(u -> new AssignableUser(u.getId(), u.getUsername(),
                        u.getFullName() == null || u.getFullName().isBlank() ? u.getUsername() : u.getFullName()))
                .toList();
    }

    public record AssignableUser(java.util.UUID id, String username, String displayName) {}

    @Transactional(readOnly = true)
    public List<UserDto> list(AuthPrincipal p) {
        return users.findByCompanyIdOrderByUsernameAsc(p.companyId()).stream().map(UserDto::of).toList();
    }

    @Transactional
    public UserDto create(AuthPrincipal p, UserRequest req) {
        String username = req.username().trim();
        String email = req.email().trim();
        List<String> roles = validateRoles(req.roles());
        if (users.existsByCompanyIdAndUsernameIgnoreCase(p.companyId(), username)
                || users.existsByCompanyIdAndEmailIgnoreCase(p.companyId(), email)) {
            throw new ApiException(ErrorCode.DUPLICATE_USER, "El usuario o el correo ya existe en la empresa.");
        }
        User u = new User();
        u.setCompanyId(p.companyId());
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(req.fullName());
        u.setPasswordHash(encoder.encode(req.password()));
        u.setRoles(roles);
        return UserDto.of(users.saveAndFlush(u));
    }

    @Transactional
    public UserDto update(AuthPrincipal p, UUID id, UserUpdateRequest req) {
        User u = find(p, id);
        String username = req.username().trim();
        String email = req.email().trim();
        List<String> roles = validateRoles(req.roles());
        if (!u.getUsername().equalsIgnoreCase(username)
                && users.existsByCompanyIdAndUsernameIgnoreCase(p.companyId(), username)) {
            throw new ApiException(ErrorCode.DUPLICATE_USER, "El usuario ya existe en la empresa.");
        }
        if (!u.getEmail().equalsIgnoreCase(email)
                && users.existsByCompanyIdAndEmailIgnoreCase(p.companyId(), email)) {
            throw new ApiException(ErrorCode.DUPLICATE_USER, "El correo ya existe en la empresa.");
        }
        if (req.password() != null && !req.password().isBlank()) {
            if (req.password().length() < 8) {
                throw ApiException.validation("La contraseña debe tener al menos 8 caracteres.");
            }
            u.setPasswordHash(encoder.encode(req.password()));
        }
        u.setUsername(username);
        u.setEmail(email);
        u.setFullName(req.fullName());
        u.setRoles(roles);
        return UserDto.of(users.saveAndFlush(u));
    }

    /** Desactivar = is_active=false; nunca se borra. */
    @Transactional
    public UserDto setActive(AuthPrincipal p, UUID id, boolean active) {
        User u = find(p, id);
        if (!active && u.getId().equals(p.userId())) {
            throw ApiException.validation("No puede desactivar su propio usuario.");
        }
        u.setActive(active);
        return UserDto.of(users.save(u));
    }

    private User find(AuthPrincipal p, UUID id) {
        return users.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("El usuario no existe."));
    }

    private static List<String> validateRoles(List<String> roles) {
        Set<String> clean = new LinkedHashSet<>();
        for (String r : roles) {
            String role = r == null ? "" : r.trim().toUpperCase();
            if (!ASSIGNABLE_ROLES.contains(role)) {
                throw ApiException.validation("Rol no válido: " + r + ". Use COMPANY_ADMIN, DESIGNER o DEVELOPER.");
            }
            clean.add(role);
        }
        return new ArrayList<>(clean);
    }
}
