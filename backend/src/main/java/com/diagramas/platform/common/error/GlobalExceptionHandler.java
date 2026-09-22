package com.diagramas.platform.common.error;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Traduce cualquier excepción al formato de error 7.1 (sin trazas internas). */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.code().status()).body(ErrorResponse.of(ex));
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return handleApi(ApiException.forbidden());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex) {
        return handleApi(new ApiException(ErrorCode.UNAUTHORIZED, "Debe iniciar sesión para continuar."));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleIntegrity(DataIntegrityViolationException ex) {
        return handleApi(fromConstraint(ex));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Error no controlado", ex);
        return handleApi(new ApiException(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado. Intente nuevamente."));
    }

    @Override
    protected ResponseEntity<Object> handleMaxUploadSizeExceededException(
            MaxUploadSizeExceededException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.VALIDATION_ERROR, "El archivo excede el tamaño máximo permitido (5 MB).", Map.of());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Map<String, Object> fields = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors().forEach(f -> fields.put(f.getField(), f.getDefaultMessage()));
        ErrorResponse body = ErrorResponse.of(
                ErrorCode.VALIDATION_ERROR, "Datos inválidos o incompletos.", Map.of("fields", fields));
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ErrorCode code = statusCode.value() == 404 ? ErrorCode.NOT_FOUND : ErrorCode.VALIDATION_ERROR;
        String message = statusCode.value() == 404
                ? "El recurso solicitado no existe."
                : "La solicitud no es válida.";
        return ResponseEntity.status(statusCode).headers(headers).body(ErrorResponse.of(code, message, Map.of()));
    }

    /** Red de seguridad para carreras: la BD rechaza duplicados que la validación previa no vio. */
    public static ApiException fromConstraint(DataIntegrityViolationException ex) {
        String msg = String.valueOf(ex.getMostSpecificCause().getMessage()).toLowerCase();
        if (msg.contains("uq_companies_name") || msg.contains("companies_slug_key")) {
            return new ApiException(ErrorCode.DUPLICATE_COMPANY, "Ya existe una empresa con ese nombre o slug.");
        }
        if (msg.contains("users_company_id_username_key") || msg.contains("users_company_id_email_key")) {
            return new ApiException(ErrorCode.DUPLICATE_USER, "El usuario o el correo ya existe en la empresa.");
        }
        if (msg.contains("uq_projects_company_name")) {
            return new ApiException(ErrorCode.DUPLICATE_PROJECT, "Ya existe un proyecto con ese nombre en la empresa.");
        }
        if (msg.contains("uq_diagrams_project_name")) {
            return new ApiException(ErrorCode.DUPLICATE_DIAGRAM, "Ya existe un diagrama con ese nombre en el proyecto.");
        }
        log.error("Violación de integridad no mapeada", ex);
        return new ApiException(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado. Intente nuevamente.");
    }
}
