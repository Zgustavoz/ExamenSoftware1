package com.diagramas.platform.support.web;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.CurrentUser;
import com.diagramas.platform.design.dto.DiagramDto;
import com.diagramas.platform.support.xmi.XmiService;
import com.diagramas.platform.support.xmi.XmiService.XmiExport;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** CU-16 Exportar/Importar XMI (solo DESIGNER). */
@RestController
@PreAuthorize("hasRole('DESIGNER')")
public class XmiController {

    private static final long MAX_BYTES = 5L * 1024 * 1024;

    private final XmiService xmi;

    public XmiController(XmiService xmi) {
        this.xmi = xmi;
    }

    @GetMapping("/api/diagrams/{id}/xmi")
    public ResponseEntity<byte[]> export(@PathVariable UUID id) {
        XmiExport file = xmi.export(CurrentUser.get(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(file.fileName()).build().toString())
                .body(file.content());
    }

    @PostMapping(value = "/api/diagrams/xmi/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DiagramDto importXmi(@RequestParam("file") MultipartFile file, @RequestParam UUID projectId) throws IOException {
        if (file.isEmpty()) {
            throw new ApiException(ErrorCode.XMI_INVALID, "El archivo está vacío.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ApiException(ErrorCode.XMI_INVALID, "El archivo XMI excede el tamaño máximo permitido (5 MB).");
        }
        return DiagramDto.of(xmi.importXmi(CurrentUser.get(), projectId, file.getOriginalFilename(),
                file.getContentType(), file.getInputStream()));
    }
}
