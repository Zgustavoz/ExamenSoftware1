package com.diagramas.platform.codegen.web;

import com.diagramas.platform.codegen.service.GeneratedCodeService;
import com.diagramas.platform.codegen.service.GeneratedCodeService.GenerationSummary;
import com.diagramas.platform.codegen.service.GeneratedCodeService.ZipDownload;
import com.diagramas.platform.common.security.CurrentUser;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** CU-15 Descargar código generado (solo DEVELOPER). */
@RestController
@RequestMapping("/api/generated-code")
@PreAuthorize("hasRole('DEVELOPER')")
public class GeneratedCodeController {

    private final GeneratedCodeService service;

    public GeneratedCodeController(GeneratedCodeService service) {
        this.service = service;
    }

    @GetMapping
    public List<GenerationSummary> history(@RequestParam UUID diagramId) {
        return service.history(CurrentUser.get(), diagramId);
    }

    @GetMapping("/tasks/{taskId}/download")
    public ResponseEntity<byte[]> download(@PathVariable UUID taskId) {
        ZipDownload zip = service.zip(CurrentUser.get(), taskId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(zip.fileName()).build().toString())
                .body(zip.content());
    }
}
