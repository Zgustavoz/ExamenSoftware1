package com.diagramas.platform.common.error;

import java.time.Instant;
import java.util.Map;

public record ErrorResponse(String code, String message, Map<String, Object> details, Instant timestamp) {

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details) {
        return new ErrorResponse(code.name(), message, details == null ? Map.of() : details, Instant.now());
    }

    public static ErrorResponse of(ApiException ex) {
        return of(ex.code(), ex.getMessage(), ex.details());
    }
}
