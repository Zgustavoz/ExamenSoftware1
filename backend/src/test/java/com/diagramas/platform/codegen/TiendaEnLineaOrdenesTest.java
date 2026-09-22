package com.diagramas.platform.codegen;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.codegen.generator.JavaSpringBootGenerator;
import com.diagramas.platform.codegen.service.GeneratedAppCommand;
import com.diagramas.platform.codegen.service.GeneratedAppCommand.Entity;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Las órdenes que se dictan desde el móvil contra el diagrama de ejemplo «Tienda en línea»
 * ({@code diagramas/tienda-en-linea.json}), el que se usa para la demostración.
 */
class TiendaEnLineaOrdenesTest {

    private static List<Entity> entidades;

    @BeforeAll
    static void cargar() throws IOException {
        JsonNode diagrama;
        try (var in = TiendaEnLineaOrdenesTest.class.getResourceAsStream("/diagramas/tienda-en-linea.json")) {
            diagrama = new ObjectMapper().readTree(in);
        }
        entidades = GeneratedAppCommand.entitiesOf(diagrama, nombre -> "/api/" + JavaSpringBootGenerator.resourcePath(nombre));
    }

    @Test
    void exponeLasCuatroEntidadesConRutasFacilesDeDictar() {
        assertThat(entidades).extracting(Entity::path)
                .containsExactlyInAnyOrder("/api/clientes", "/api/pedidos", "/api/productos", "/api/pagos");
    }

    @Test
    void registraUnClienteConNombreEmailYTelefono() {
        var call = GeneratedAppCommand.parse(
                "registra un cliente con nombre Juan Pérez y email juan@ejemplo.com y telefono 76543210", entidades);

        assertThat(call).isNotNull();
        assertThat(call.method()).isEqualTo("POST");
        assertThat(call.path()).isEqualTo("/api/clientes");
        assertThat(call.body())
                .containsEntry("nombre", "Juan Pérez")
                .containsEntry("email", "juan@ejemplo.com")
                .containsEntry("telefono", "76543210");
    }

    @Test
    void registraUnProductoConPrecioDecimalYStockEntero() {
        var call = GeneratedAppCommand.parse("crea un producto con nombre Teclado y precio 150,5 y stock 10", entidades);

        assertThat(call).isNotNull();
        assertThat(call.path()).isEqualTo("/api/productos");
        assertThat(call.body())
                .containsEntry("nombre", "Teclado")
                .containsEntry("precio", 150.5)
                .containsEntry("stock", 10);
    }

    @Test
    void registraUnPedidoYUnPago() {
        var pedido = GeneratedAppCommand.parse("crea un pedido con total 150 y estado PENDIENTE", entidades);
        assertThat(pedido).isNotNull();
        assertThat(pedido.path()).isEqualTo("/api/pedidos");
        assertThat(pedido.body()).containsEntry("total", 150.0).containsEntry("estado", "PENDIENTE");

        var pago = GeneratedAppCommand.parse("registra un pago con monto 150 y metodo tarjeta", entidades);
        assertThat(pago).isNotNull();
        assertThat(pago.path()).isEqualTo("/api/pagos");
        assertThat(pago.body()).containsEntry("monto", 150.0).containsEntry("metodo", "tarjeta");
    }

    @Test
    void listaCadaEntidad() {
        for (var par : List.of("clientes", "pedidos", "productos", "pagos")) {
            var call = GeneratedAppCommand.parse("lista los " + par, entidades);
            assertThat(call).as(par).isNotNull();
            assertThat(call.method()).isEqualTo("GET");
            assertThat(call.path()).isEqualTo("/api/" + par);
        }
    }
}
