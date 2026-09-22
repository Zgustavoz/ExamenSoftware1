package com.diagramas.platform.access.web;

import com.diagramas.platform.access.dto.AccessDtos.StatusRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.dto.AccessDtos.UserRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserUpdateRequest;
import com.diagramas.platform.access.service.UserService;
import com.diagramas.platform.common.security.CurrentUser;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('COMPANY_ADMIN')")
public class UserController {

    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public List<UserDto> list() {
        return users.list(CurrentUser.get());
    }

    /** CU-21: a quién se le puede asignar una tarea. Lo necesita cualquier rol, no solo el administrador. */
    @GetMapping("/assignable")
    @PreAuthorize("isAuthenticated()")
    public List<UserService.AssignableUser> assignable() {
        return users.assignable(CurrentUser.get());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto create(@Valid @RequestBody UserRequest req) {
        return users.create(CurrentUser.get(), req);
    }

    @PutMapping("/{id}")
    public UserDto update(@PathVariable UUID id, @Valid @RequestBody UserUpdateRequest req) {
        return users.update(CurrentUser.get(), id, req);
    }

    @PatchMapping("/{id}/status")
    public UserDto status(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return users.setActive(CurrentUser.get(), id, req.active());
    }
}
