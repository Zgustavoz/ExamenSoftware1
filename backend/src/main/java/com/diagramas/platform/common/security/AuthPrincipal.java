package com.diagramas.platform.common.security;

import java.security.Principal;
import java.util.List;
import java.util.UUID;

/** Identidad autenticada tomada del JWT. El {@code companyId} SIEMPRE sale de aquí (multi-tenant). */
public record AuthPrincipal(UUID userId, UUID companyId, List<String> roles) implements Principal {

    public static final String SOFTWARE_ADMIN = "SOFTWARE_ADMIN";
    public static final String COMPANY_ADMIN = "COMPANY_ADMIN";
    public static final String DESIGNER = "DESIGNER";
    public static final String DEVELOPER = "DEVELOPER";

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
