package com.diagramas.platform.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.design.service.DiagramOperationApplier;
import com.diagramas.platform.support.xmi.ArchitectAdapter.XmiModel;
import com.diagramas.platform.support.xmi.XmiArchitectAdapter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** CP-07: round-trip fromXmi(toXmi(d)) y seguridad de importación (XXE, tamaño, XMI inválido). */
class XmiAdapterTest {

    private static final String DIAGRAM = """
            {"schemaVersion":1,"type":"CLASS","classes":[
              {"id":"c1","name":"Cliente","stereotype":null,"visibility":"PUBLIC","x":120,"y":80,
               "attributes":[{"id":"a1","name":"nombre","type":"String","visibility":"PRIVATE"},
                             {"id":"a2","name":"pedidos","type":"List<Pedido>","visibility":"PROTECTED"}],
               "methods":[{"id":"m1","name":"comprar","returnType":"boolean","visibility":"PUBLIC",
                           "parameters":[{"name":"p","type":"Pedido"},{"name":"veces","type":"int"}]},
                          {"id":"m2","name":"limpiar","returnType":"void","visibility":"PRIVATE","parameters":[]}]},
              {"id":"c2","name":"Pedido","stereotype":"abstract","visibility":"PUBLIC","x":400,"y":90,
               "attributes":[{"id":"a3","name":"total","type":"BigDecimal","visibility":"PRIVATE"}],"methods":[]},
              {"id":"c3","name":"PedidoWeb","visibility":"PUBLIC","x":400,"y":300,
               "attributes":[{"id":"a4","name":"url","type":"String","visibility":"PRIVATE"}],"methods":[]},
              {"id":"c4","name":"Auditable","stereotype":"interface","visibility":"PUBLIC","x":700,"y":90,
               "attributes":[],"methods":[{"id":"m3","name":"auditar","returnType":"void","visibility":"PUBLIC","parameters":[]}]},
              {"id":"c5","name":"Estado","stereotype":"enum","visibility":"PUBLIC","x":700,"y":300,
               "attributes":[{"id":"a5","name":"ACTIVO","type":"String","visibility":"PUBLIC"}],"methods":[]},
              {"id":"c6","name":"Linea","visibility":"PUBLIC","x":100,"y":400,
               "attributes":[{"id":"a6","name":"q","type":"int","visibility":"PRIVATE"}],"methods":[]}
            ],"relationships":[
              {"id":"r1","type":"ASSOCIATION","sourceId":"c1","targetId":"c2","sourceMultiplicity":"1","targetMultiplicity":"0..*","sourceRole":"cliente","targetRole":"pedidos","name":"realiza"},
              {"id":"r2","type":"GENERALIZATION","sourceId":"c3","targetId":"c2"},
              {"id":"r3","type":"REALIZATION","sourceId":"c1","targetId":"c4"},
              {"id":"r4","type":"COMPOSITION","sourceId":"c2","targetId":"c6","sourceMultiplicity":"1","targetMultiplicity":"1..*"},
              {"id":"r5","type":"AGGREGATION","sourceId":"c1","targetId":"c6","sourceMultiplicity":"0..1","targetMultiplicity":"2..5"},
              {"id":"r6","type":"DEPENDENCY","sourceId":"c3","targetId":"c1"}
            ]}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final DiagramOperationApplier applier = new DiagramOperationApplier();
    private final XmiArchitectAdapter adapter = new XmiArchitectAdapter(applier);

    private ByteArrayInputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    private ErrorCode codeOf(Runnable r) {
        try {
            r.run();
        } catch (ApiException e) {
            return e.code();
        }
        return null;
    }

    @Test
    void cp07_roundTripConservaEstructuraYLayout() throws Exception {
        JsonNode original = applier.normalizeContent(mapper.readTree(DIAGRAM));
        String xmi = adapter.toXmi(original, "Ventas");
        XmiModel back = adapter.fromXmi(stream(xmi));

        assertThat(back.name()).isEqualTo("Ventas");
        // clases, atributos, métodos, posiciones e IDs idénticos (y en el mismo orden)
        assertThat(back.content().get("classes")).isEqualTo(original.get("classes"));
        // las relaciones son las mismas; XMI no conserva su orden relativo (herencia/realización viven dentro de la clase)
        assertThat(byId(back.content().get("relationships"))).isEqualTo(byId(original.get("relationships")));
        assertThat(back.content().get("relationships")).hasSize(6);
    }

    private java.util.Map<String, JsonNode> byId(JsonNode array) {
        java.util.Map<String, JsonNode> map = new java.util.HashMap<>();
        array.forEach(n -> map.put(n.get("id").asText(), n));
        return map;
    }

    @Test
    void xmiSalidaEsUml251ConLosElementosDelMapeo() throws Exception {
        JsonNode original = applier.normalizeContent(mapper.readTree(DIAGRAM));
        String xmi = adapter.toXmi(original, "Ventas");
        assertThat(xmi).contains("xmi:version=\"2.5.1\"").contains("uml:Model")
                .contains("xmi:type=\"uml:Class\"").contains("xmi:type=\"uml:Interface\"").contains("xmi:type=\"uml:Enumeration\"")
                .contains("isAbstract=\"true\"").contains("ownedAttribute").contains("ownedOperation").contains("ownedParameter")
                .contains("direction=\"return\"").contains("direction=\"in\"")
                .contains("<generalization").contains("<interfaceRealization").contains("xmi:type=\"uml:Association\"")
                .contains("memberEnd").contains("ownedEnd").contains("lowerValue").contains("upperValue")
                .contains("aggregation=\"composite\"").contains("aggregation=\"shared\"").contains("xmi:type=\"uml:Dependency\"")
                .contains("xmi:Extension").contains("layout");
    }

    @Test
    void importarXmiSinLayoutAutoPosiciona() {
        String xmi = """
                <?xmi version="1.0"?>
                <xmi:XMI xmlns:xmi="http://schema.omg.org/spec/XMI/2.1" xmlns:uml="http://www.omg.org/spec/UML/20090901" xmi:version="2.1">
                  <uml:Model xmi:type="uml:Model" name="EA Model" xmi:id="EAID_M">
                    <packagedElement xmi:type="uml:Package" xmi:id="EAID_P" name="pkg">
                      <packagedElement xmi:type="uml:Class" xmi:id="EAID_1" name="Cuenta" visibility="public">
                        <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_A1" name="saldo" visibility="private">
                          <type xmi:idref="EAJava_double"/>
                        </ownedAttribute>
                        <ownedAttribute xmi:type="uml:Property" xmi:id="EAID_A2" name="titular" visibility="private" type="EAID_2"/>
                        <ownedOperation xmi:id="EAID_O1" name="depositar" visibility="public">
                          <ownedParameter xmi:id="EAID_OP1" name="monto" direction="in"><type xmi:idref="EAJava_double"/></ownedParameter>
                        </ownedOperation>
                      </packagedElement>
                      <packagedElement xmi:type="uml:Class" xmi:id="EAID_2" name="Titular">
                        <ownedAttribute xmi:id="EAID_A3" name="nombre" type="EAJava_String"/>
                        <generalization xmi:type="uml:Generalization" xmi:id="EAID_G1" general="EAID_1"/>
                      </packagedElement>
                    </packagedElement>
                    <packagedElement xmi:type="uml:PrimitiveType" xmi:id="EAJava_double" name="double"/>
                    <packagedElement xmi:type="uml:PrimitiveType" xmi:id="EAJava_String" name="String"/>
                  </uml:Model>
                </xmi:XMI>
                """;
        XmiModel m = adapter.fromXmi(stream(xmi));
        assertThat(m.name()).isEqualTo("EA Model");
        JsonNode classes = m.content().get("classes");
        assertThat(classes).hasSize(2);
        assertThat(classes.get(0).get("attributes").get(0).get("type").asText()).isEqualTo("double");
        assertThat(classes.get(0).get("attributes").get(1).get("type").asText()).isEqualTo("Titular");
        assertThat(classes.get(0).get("methods").get(0).get("parameters").get(0).get("name").asText()).isEqualTo("monto");
        assertThat(classes.get(0).get("x").asInt()).isNotEqualTo(classes.get(1).get("x").asInt()); // grilla
        assertThat(m.content().get("relationships").get(0).get("type").asText()).isEqualTo("GENERALIZATION");
    }

    @Test
    void xxeSeRechaza() throws Exception {
        Path secret = Files.createTempFile("secret", ".txt");
        Files.writeString(secret, "TOP-SECRET");
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">]>"
                + "<xmi:XMI xmlns:xmi=\"http://www.omg.org/spec/XMI/20131001\" xmlns:uml=\"http://www.omg.org/spec/UML/20161101\">"
                + "<uml:Model name=\"&xxe;\"/></xmi:XMI>";
        try {
            adapter.fromXmi(stream(xxe));
            throw new AssertionError("debió rechazarse");
        } catch (ApiException e) {
            assertThat(e.code()).isEqualTo(ErrorCode.XMI_INVALID);
            assertThat(e.getMessage()).doesNotContain("TOP-SECRET");
        }
    }

    @Test
    void billionLaughsYDoctypeSeRechazan() {
        String bomb = "<?xml version=\"1.0\"?><!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;\">]><lolz>&lol2;</lolz>";
        assertThat(codeOf(() -> adapter.fromXmi(stream(bomb)))).isEqualTo(ErrorCode.XMI_INVALID);
    }

    @Test
    void xmiInvalidoOIncompatible() {
        assertThat(codeOf(() -> adapter.fromXmi(stream("esto no es xml")))).isEqualTo(ErrorCode.XMI_INVALID);
        assertThat(codeOf(() -> adapter.fromXmi(stream("<a><b/></a>")))).isEqualTo(ErrorCode.XMI_INVALID); // sin uml:Model
        // relación a una clase inexistente
        String dangling = "<xmi:XMI xmlns:xmi=\"http://www.omg.org/spec/XMI/20131001\" xmlns:uml=\"http://www.omg.org/spec/UML/20161101\">"
                + "<uml:Model name=\"m\"><packagedElement xmi:type=\"uml:Class\" xmi:id=\"a\" name=\"A\">"
                + "<generalization xmi:id=\"g\" general=\"zzz\"/></packagedElement></uml:Model></xmi:XMI>";
        assertThat(codeOf(() -> adapter.fromXmi(stream(dangling)))).isEqualTo(ErrorCode.XMI_INVALID);
        // tipo de dato que no se puede mapear
        String badType = "<xmi:XMI xmlns:xmi=\"http://www.omg.org/spec/XMI/20131001\" xmlns:uml=\"http://www.omg.org/spec/UML/20161101\">"
                + "<uml:Model name=\"m\"><packagedElement xmi:type=\"uml:Class\" xmi:id=\"a\" name=\"A\">"
                + "<ownedAttribute xmi:id=\"p\" name=\"x\" type=\"Tipo_Raro\"/></packagedElement></uml:Model></xmi:XMI>";
        assertThat(codeOf(() -> adapter.fromXmi(stream(badType)))).isEqualTo(ErrorCode.XMI_INVALID);
    }

    @Test
    void archivoDemasiadoGrandeSeRechaza() {
        byte[] big = new byte[6 * 1024 * 1024];
        java.util.Arrays.fill(big, (byte) ' ');
        assertThat(codeOf(() -> adapter.fromXmi(new ByteArrayInputStream(big)))).isEqualTo(ErrorCode.XMI_INVALID);
    }
}
