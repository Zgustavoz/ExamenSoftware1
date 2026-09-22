package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.diagramas.platform.codegen.generator.CodeGenerator;
import com.diagramas.platform.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Rama ALT del flujo 9.3: fallo en la generación → tarea FAILED, error GENERATION_FAILED, sin código. */
class CodegenFailureIT extends AbstractIntegrationTest {

    @MockitoBean CodeGenerator generator;

    @Test
    void falloEnLaGeneracionDejaLaTareaEnFailed() {
        when(generator.language()).thenReturn("JAVA");
        when(generator.generate(any())).thenThrow(new IllegalStateException("boom interno con datos sensibles"));

        Tenant t = newTenant();
        String project = createProject(t.designerToken(), "P");
        String diagram = createDiagram(t.designerToken(), project, "D").get("id").asText();
        saveDiagram(t.designerToken(), diagram, Map.of("type", "CLASS", "classes", List.of(
                Map.of("id", "a", "name", "A", "x", 1, "y", 1, "attributes", List.of(Map.of("name", "n", "type", "int")))),
                "relationships", List.of()), 1);

        JsonNode r = graphql(t.designerToken(),
                "mutation($d: ID!) { generateBackendCode(diagramId: $d, language: \"JAVA\") { id } }", Map.of("d", diagram));
        assertThat(errorCode(r)).isEqualTo("GENERATION_FAILED");
        // sin trazas internas en el mensaje
        assertThat(r.get("errors").get(0).get("message").asText()).doesNotContain("boom").doesNotContain("Exception");

        Map<String, Object> task = jdbc.queryForMap("select status, completed_at from tasks where diagram_id = ?::uuid", diagram);
        assertThat(task.get("status")).isEqualTo("FAILED");
        assertThat(task.get("completed_at")).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from generated_code where diagram_id = ?::uuid", Integer.class, diagram)).isZero();
        // no se notificó CODE_READY
        Integer ready = jdbc.queryForObject("select count(*) from notifications where type = 'CODE_READY' and company_id = ?::uuid",
                Integer.class, t.companyId().toString());
        assertThat(ready).isZero();
    }
}
