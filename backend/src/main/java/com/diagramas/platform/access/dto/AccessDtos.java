package com.diagramas.platform.access.dto;

import com.diagramas.platform.access.domain.Company;
import com.diagramas.platform.access.domain.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;

/** DTOs REST del módulo de acceso y administración. */
public final class AccessDtos {

    private AccessDtos() {}

    public record LoginRequest(
            @NotBlank(message = "La empresa es obligatoria") String companySlug,
            @NotBlank(message = "El usuario es obligatorio") String username,
            @NotBlank(message = "La contraseña es obligatoria") String password) {}

    public record LoginResponse(String token, UserDto user) {}

    public record UserDto(
            UUID id,
            String username,
            String email,
            String fullName,
            List<String> roles,
            UUID companyId,
            boolean active,
            Instant createdAt) {

        public static UserDto of(User u) {
            return new UserDto(
                    u.getId(), u.getUsername(), u.getEmail(), u.getFullName(),
                    List.copyOf(u.getRoles()), u.getCompanyId(), u.isActive(), u.getCreatedAt());
        }
    }

    public record CompanyDto(UUID id, String name, String slug, boolean active, Instant createdAt) {
        public static CompanyDto of(Company c) {
            return new CompanyDto(c.getId(), c.getName(), c.getSlug(), c.isActive(), c.getCreatedAt());
        }
    }

    public record CompanyRequest(
            @NotBlank(message = "El nombre es obligatorio") @Size(max = 100, message = "Máximo 100 caracteres") String name,
            @NotBlank(message = "El slug es obligatorio")
                    @Size(max = 50, message = "Máximo 50 caracteres")
                    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Use minúsculas, números y guiones")
                    String slug) {}

    public record StatusRequest(@NotNull(message = "El estado es obligatorio") Boolean active) {}

    /** CU-22 Registrar empresa: alta pública de una empresa junto con su primer administrador. */
    public record RegisterCompanyRequest(
            @NotBlank(message = "El nombre de la empresa es obligatorio")
                    @Size(max = 100, message = "Máximo 100 caracteres")
                    String companyName,
            @NotBlank(message = "El identificador de la empresa es obligatorio")
                    @Size(max = 50, message = "Máximo 50 caracteres")
                    @Pattern(regexp = "^[a-z0-9]+(-[a-z0-9]+)*$", message = "Use minúsculas, números y guiones")
                    String companySlug,
            @NotNull(message = "Los datos del administrador son obligatorios") @Valid AdminAccount admin) {}

    public record AdminAccount(
            @Size(max = 100, message = "Máximo 100 caracteres") String fullName,
            @NotBlank(message = "El usuario es obligatorio") @Size(max = 50, message = "Máximo 50 caracteres") String username,
            @NotBlank(message = "El correo es obligatorio")
                    @Email(message = "Correo inválido")
                    @Size(max = 100, message = "Máximo 100 caracteres")
                    String email,
            @NotBlank(message = "La contraseña es obligatoria")
                    @Size(min = 8, max = 100, message = "La contraseña debe tener al menos 8 caracteres")
                    String password) {}

    /** Alta de administrador de empresa (CU-02). */
    public record AdminRequest(
            @NotBlank(message = "El usuario es obligatorio") @Size(max = 50) String username,
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo inválido") @Size(max = 100) String email,
            @NotBlank(message = "La contraseña es obligatoria") @Size(min = 8, max = 100, message = "Mínimo 8 caracteres") String password,
            @Size(max = 100) String fullName) {}

    public record UserRequest(
            @NotBlank(message = "El usuario es obligatorio") @Size(max = 50) String username,
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo inválido") @Size(max = 100) String email,
            @NotBlank(message = "La contraseña es obligatoria") @Size(min = 8, max = 100, message = "Mínimo 8 caracteres") String password,
            @Size(max = 100) String fullName,
            @NotNull(message = "Los roles son obligatorios") @Size(min = 1, message = "Asigne al menos un rol") List<String> roles) {}

    /** En la edición la contraseña es opcional (vacía = sin cambio). */
    public record UserUpdateRequest(
            @NotBlank(message = "El usuario es obligatorio") @Size(max = 50) String username,
            @NotBlank(message = "El correo es obligatorio") @Email(message = "Correo inválido") @Size(max = 100) String email,
            @Size(max = 100, message = "Máximo 100 caracteres") String password,
            @Size(max = 100) String fullName,
            @NotNull(message = "Los roles son obligatorios") @Size(min = 1, message = "Asigne al menos un rol") List<String> roles) {}

    public record ProjectRequest(
            @NotBlank(message = "El nombre es obligatorio") @Size(max = 100, message = "Máximo 100 caracteres") String name,
            String description) {}

    public record ProjectDto(
            UUID id, String name, String description, UUID ownerId, String ownerName, Instant createdAt, Instant updatedAt) {}

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
        public static <T> PageResponse<T> of(Page<?> page, List<T> content) {
            return new PageResponse<>(content, page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
