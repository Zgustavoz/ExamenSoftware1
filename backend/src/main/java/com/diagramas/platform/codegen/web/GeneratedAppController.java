package com.diagramas.platform.codegen.web;

import com.diagramas.platform.codegen.service.GeneratedAppCommand.Entity;
import com.diagramas.platform.codegen.service.GeneratedAppService;
import com.diagramas.platform.common.security.CurrentUser;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Órdenes habladas contra el backend generado a partir de un diagrama. Lo usa el cliente móvil, que es el
 * que demuestra que ese backend funciona: se dicta «registra un cliente…» y aquí se traduce y se ejecuta.
 */
@RestController
@RequestMapping("/api/generated-app")
public class GeneratedAppController {

    public record CommandRequest(
            @NotBlank(message = "La orden es obligatoria") @Size(max = 2000) String instruction) {}

    private final GeneratedAppService service;

    public GeneratedAppController(GeneratedAppService service) {
        this.service = service;
    }

    /** Lo que el backend generado expone para ese diagrama, para saber qué se le puede pedir. */
    @GetMapping("/{diagramId}/entities")
    public List<Entity> entities(@PathVariable UUID diagramId) {
        return service.entities(CurrentUser.get(), diagramId);
    }

    @PostMapping("/{diagramId}/command")
    public ObjectNode command(@PathVariable UUID diagramId, @Valid @RequestBody CommandRequest req) {
        return GeneratedAppService.describe(service.execute(CurrentUser.get(), diagramId, req.instruction()));
    }
}
