package com.diagramas.platform.common.config;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Detiene el arranque en el perfil {@code prod} si la configuración no es segura (ver {@link ProductionSettings}). */
@Component
@Profile("prod")
class ProductionSettingsValidator {

    ProductionSettingsValidator(AppProperties props, @Value("${spring.datasource.password:}") String dbPassword) {
        List<String> problems = ProductionSettings.problems(props, dbPassword);
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "Configuración de producción no válida:\n - " + String.join("\n - ", problems));
        }
    }
}
