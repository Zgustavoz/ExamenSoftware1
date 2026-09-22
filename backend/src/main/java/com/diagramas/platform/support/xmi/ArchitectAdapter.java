package com.diagramas.platform.support.xmi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.InputStream;

/**
 * Conversión JSON interno ⇄ XMI 2.5.1 / UML 2.5.1 legible por Enterprise Architect (D-05: módulo interno,
 * extraíble a microservicio más adelante).
 */
public interface ArchitectAdapter {

    record XmiModel(String name, ObjectNode content) {}

    /** @throws com.diagramas.platform.common.error.ApiException XMI_INVALID si no se puede convertir */
    String toXmi(JsonNode contentJson, String modelName);

    /** @throws com.diagramas.platform.common.error.ApiException XMI_INVALID si el XMI es inválido o incompatible */
    XmiModel fromXmi(InputStream xmi);
}
