package com.diagramas.platform.access.web;

import com.diagramas.platform.access.dto.AccessDtos.LoginRequest;
import com.diagramas.platform.access.dto.AccessDtos.LoginResponse;
import com.diagramas.platform.access.dto.AccessDtos.RegisterCompanyRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.service.AuthService;
import com.diagramas.platform.common.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req) {
        return auth.login(req.companySlug(), req.username(), req.password());
    }

    /** CU-22: alta pública de una empresa; devuelve la sesión ya iniciada. */
    @PostMapping("/register-company")
    public LoginResponse registerCompany(@Valid @RequestBody RegisterCompanyRequest req) {
        return auth.registerCompany(req);
    }

    @GetMapping("/me")
    public UserDto me() {
        return auth.me(CurrentUser.get());
    }
}
