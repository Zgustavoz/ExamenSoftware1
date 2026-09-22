package com.diagramas.platform.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

/** Error de negocio con código estable y mensaje en español apto para el usuario. */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> details;

    public ApiException(ErrorCode code, String message) {
        this(code, message, Map.of());
    }

    public ApiException(ErrorCode code, String message, Map<String, Object> details) {
        super(message);
        this.code = code;
        this.details = details == null ? Map.of() : new LinkedHashMap<>(details);
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }

    public static ApiException notFound(String message) {
        return new ApiException(ErrorCode.NOT_FOUND, message);
    }

    public static ApiException validation(String message) {
        return new ApiException(ErrorCode.VALIDATION_ERROR, message);
    }

    public static ApiException forbidden() {
        return new ApiException(ErrorCode.FORBIDDEN, "No tiene permisos para realizar esta operación.");
    }
}
