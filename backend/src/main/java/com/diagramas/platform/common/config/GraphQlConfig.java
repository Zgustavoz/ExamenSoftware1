package com.diagramas.platform.common.config;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.error.GlobalExceptionHandler;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.scalars.ExtendedScalars;
import graphql.schema.DataFetchingEnvironment;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.graphql.execution.DataFetcherExceptionResolverAdapter;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Configuration
public class GraphQlConfig {

    @Bean
    RuntimeWiringConfigurer jsonScalar() {
        return builder -> builder.scalar(ExtendedScalars.Json);
    }

    /** Los errores GraphQL llevan {@code extensions.code} con los códigos de la sección 7.1. */
    @Component
    static class ApiExceptionResolver extends DataFetcherExceptionResolverAdapter {

        private static final Logger log = LoggerFactory.getLogger(ApiExceptionResolver.class);

        @Override
        protected GraphQLError resolveToSingleError(Throwable ex, DataFetchingEnvironment env) {
            ApiException api;
            if (ex instanceof ApiException a) {
                api = a;
            } else if (ex instanceof AccessDeniedException) {
                api = ApiException.forbidden();
            } else if (ex instanceof AuthenticationException) {
                api = new ApiException(ErrorCode.UNAUTHORIZED, "Debe iniciar sesión para continuar.");
            } else if (ex instanceof DataIntegrityViolationException d) {
                api = GlobalExceptionHandler.fromConstraint(d);
            } else {
                log.error("Error no controlado en GraphQL", ex);
                api = new ApiException(ErrorCode.INTERNAL_ERROR, "Ocurrió un error inesperado. Intente nuevamente.");
            }
            Map<String, Object> ext = new LinkedHashMap<>();
            ext.put("code", api.code().name());
            ext.put("details", api.details());
            ext.put("timestamp", Instant.now().toString());
            return GraphqlErrorBuilder.newError(env)
                    .message(api.getMessage())
                    .errorType(errorType(api.code()))
                    .extensions(ext)
                    .build();
        }

        private static ErrorType errorType(ErrorCode code) {
            return switch (code) {
                case UNAUTHORIZED, INVALID_CREDENTIALS -> ErrorType.UNAUTHORIZED;
                case FORBIDDEN, USER_INACTIVE, COMPANY_DISABLED -> ErrorType.FORBIDDEN;
                case NOT_FOUND -> ErrorType.NOT_FOUND;
                case INTERNAL_ERROR, GENERATION_FAILED -> ErrorType.INTERNAL_ERROR;
                default -> ErrorType.BAD_REQUEST;
            };
        }
    }
}
