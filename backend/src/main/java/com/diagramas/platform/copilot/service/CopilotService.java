package com.diagramas.platform.copilot.service;

import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * CU-12 Generar o modificar diagrama con IA (flujo 9.2). Deliberadamente NO es transaccional: el mensaje del
 * usuario debe quedar registrado aunque la IA falle, y no se mantiene una transacción abierta durante la llamada.
 */
@Service
public class CopilotService {

    private static final Set<String> INPUT_TYPES = Set.of("TEXTO", "VOZ");
    private static final int MAX_INSTRUCTION = 4000;

    public record AiOutcome(String explanation, List<ObjectNode> operations, Diagram diagram) {}

    private final DiagramService diagrams;
    private final AiChatService chats;
    private final AiClient ai;
    private final OperationNormalizer normalizer;

    public CopilotService(DiagramService diagrams, AiChatService chats, AiClient ai, OperationNormalizer normalizer) {
        this.diagrams = diagrams;
        this.chats = chats;
        this.ai = ai;
        this.normalizer = normalizer;
    }

    public AiOutcome sendInstruction(AuthPrincipal p, UUID diagramId, String instruction, String inputType) {
        // validarInstruccion
        if (instruction == null || instruction.isBlank()) {
            throw ApiException.validation("La instrucción no puede estar vacía.");
        }
        if (instruction.length() > MAX_INSTRUCTION) {
            throw ApiException.validation("La instrucción excede " + MAX_INSTRUCTION + " caracteres.");
        }
        if (inputType == null || !INPUT_TYPES.contains(inputType)) {
            throw ApiException.validation("El tipo de entrada debe ser TEXTO o VOZ.");
        }
        Diagram current = diagrams.get(p, diagramId);
        if (!Diagram.TYPE_CLASS.equals(current.getType())) {
            throw ApiException.validation("El asistente solo trabaja sobre diagramas de clases.");
        }

        // registrarMensajeUsuario (después de tomar el historial previo, que es el contexto)
        ArrayNode history = chats.history(p, diagramId);
        chats.append(p, diagramId, "user", instruction);

        // invocarMotorIA: cualquier fallo (timeout/no disponible/inválida) deja el diagrama intacto (CP-04)
        JsonNode response = ai.interpret(instruction, inputType, current.getContentJson(), history);
        String explanation = response.path("explanation").asText("").trim();
        JsonNode operations = response.get("operations");

        // normalizarRespuesta + aplicarCambiosAlDiagrama (el applier revalida todo; atómico)
        Diagram latest = diagrams.get(p, diagramId);
        List<ObjectNode> normalized = normalizer.normalize(operations, latest.getContentJson());
        Diagram updated;
        try {
            updated = diagrams.applyOperations(p, diagramId, normalized);
        } catch (ApiException e) {
            if (e.code() == ErrorCode.INTERNAL_ERROR) {
                throw e;
            }
            throw new ApiException(
                    ErrorCode.AI_INVALID_RESPONSE,
                    "El asistente propuso cambios que no son válidos para este diagrama: " + e.getMessage(),
                    Map.of("reason", e.code().name()));
        }

        // registrarMensajeIA
        chats.append(p, diagramId, "assistant", explanation.isEmpty() ? "Cambios aplicados al diagrama." : explanation);
        return new AiOutcome(explanation, normalized, updated);
    }

    /** confirmarCambios: los cambios ya se aplicaron (D-07), por lo que es idempotente. */
    public Diagram confirm(AuthPrincipal p, UUID diagramId) {
        return diagrams.get(p, diagramId);
    }

    public static ArrayNode toArray(List<ObjectNode> ops) {
        ArrayNode arr = Json.array();
        ops.forEach(arr::add);
        return arr;
    }
}
