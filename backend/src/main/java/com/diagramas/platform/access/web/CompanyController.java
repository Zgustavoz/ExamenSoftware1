package com.diagramas.platform.access.web;

import com.diagramas.platform.access.dto.AccessDtos.AdminRequest;
import com.diagramas.platform.access.dto.AccessDtos.CompanyDto;
import com.diagramas.platform.access.dto.AccessDtos.CompanyRequest;
import com.diagramas.platform.access.dto.AccessDtos.StatusRequest;
import com.diagramas.platform.access.dto.AccessDtos.UserDto;
import com.diagramas.platform.access.service.CompanyService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/companies")
@PreAuthorize("hasRole('SOFTWARE_ADMIN')")
public class CompanyController {

    private final CompanyService companies;

    public CompanyController(CompanyService companies) {
        this.companies = companies;
    }

    @GetMapping
    public List<CompanyDto> list() {
        return companies.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CompanyDto create(@Valid @RequestBody CompanyRequest req) {
        return companies.create(req);
    }

    @PutMapping("/{id}")
    public CompanyDto update(@PathVariable UUID id, @Valid @RequestBody CompanyRequest req) {
        return companies.update(id, req);
    }

    @PatchMapping("/{id}/status")
    public CompanyDto status(@PathVariable UUID id, @Valid @RequestBody StatusRequest req) {
        return companies.setActive(id, req.active());
    }

    @PostMapping("/{id}/admins")
    @ResponseStatus(HttpStatus.CREATED)
    public UserDto createAdmin(@PathVariable UUID id, @Valid @RequestBody AdminRequest req) {
        return companies.createAdmin(id, req);
    }
}
