package com.diagramas.platform.codegen.generator;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/** Transforma un diagrama de clases (content_json) en archivos de código. Debe ser determinista. */
public interface CodeGenerator {

    record GeneratedFile(String path, String content) {}

    String language();

    List<GeneratedFile> generate(JsonNode contentJson);
}
