package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.codegen.service.GeneratedAppCommand;
import com.diagramas.platform.codegen.service.GeneratedAppCommand.Entity;
import com.diagramas.platform.common.util.Json;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Intérprete local de órdenes habladas: el respaldo cuando el asistente no está disponible. */
class GeneratedAppCommandTest {

    private static final List<Entity> ENTIDADES = List.of(
            new Entity("Cliente", "/api/clientes", List.of(
                    new Entity.Field("nombre", "String"),
                    new Entity.Field("email", "String"),
                    new Entity.Field("edad", "int"))),
            new Entity("Pedido", "/api/pedidos", List.of(new Entity.Field("total", "BigDecimal"))));

    @Test
    void registraUnClienteConSusCampos() {
        var call = GeneratedAppCommand.parse(
                "registra un cliente con nombre Juan Perez y email juan@ejemplo.com", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/clientes");
        assertThat(call.body()).containsEntry("nombre", "Juan Perez").containsEntry("email", "juan@ejemplo.com");
    }

    @Test
    void entiendeLosAcentosYElPluralDeLaEntidad() {
        var call = GeneratedAppCommand.parse("Registrá un Cliente con nombre Ana", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.path()).isEqualTo("/api/clientes");
        assertThat(call.body()).containsEntry("nombre", "Ana");
    }

    @Test
    void convierteLosNumerosSegunElTipoDelAtributo() {
        var call = GeneratedAppCommand.parse("crea un cliente con nombre Ana y edad 30", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.body()).containsEntry("edad", 30);
    }

    @Test
    void listarConsultaSinCuerpo() {
        var call = GeneratedAppCommand.parse("lista los clientes", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.path()).isEqualTo("/api/clientes");
        assertThat(call.body()).isEmpty();
    }

    @Test
    void eligeLaEntidadQueSeNombra() {
        var call = GeneratedAppCommand.parse("registra un pedido con total 150", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.path()).isEqualTo("/api/pedidos");
        assertThat(call.body()).containsEntry("total", 150.0);
    }

    @Test
    void descartaLosCamposQueNoExistenEnLaEntidad() {
        var call = GeneratedAppCommand.parse("registra un cliente con nombre Ana y color azul", ENTIDADES);

        assertThat(call).isNotNull();
        assertThat(call.body()).containsOnlyKeys("nombre");
    }

    @Test
    void sinEntidadReconocibleNoInventaNada() {
        assertThat(GeneratedAppCommand.parse("registra una factura con total 10", ENTIDADES)).isNull();
    }

    @Test
    void sinVerboReconocibleNoInventaNada() {
        assertThat(GeneratedAppCommand.parse("el cliente Juan es importante", ENTIDADES)).isNull();
    }

    @Test
    void lasEntidadesSalenDelDiagramaSinInterfacesNiEnumsNiAbstractas() throws Exception {
        JsonNode content = Json.MAPPER.readTree("""
                {"type":"CLASS","classes":[
                  {"name":"Cliente","stereotype":null,"attributes":[
                     {"name":"id","type":"UUID"},{"name":"nombre","type":"String"}]},
                  {"name":"Pagable","stereotype":"interface","attributes":[]},
                  {"name":"Estado","stereotype":"enum","attributes":[]},
                  {"name":"Base","stereotype":"abstract","attributes":[]}],
                 "relationships":[]}
                """);

        var entidades = GeneratedAppCommand.entitiesOf(content, n -> "/api/" + n.toLowerCase() + "s");

        assertThat(entidades).hasSize(1);
        assertThat(entidades.get(0).name()).isEqualTo("Cliente");
        // El id lo pone el backend generado: no es un campo que se dicte.
        assertThat(entidades.get(0).fields()).extracting(Entity.Field::name).containsExactly("nombre");
    }
}
