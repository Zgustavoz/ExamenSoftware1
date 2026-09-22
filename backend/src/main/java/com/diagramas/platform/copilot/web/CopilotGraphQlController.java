package com.diagramas.platform.copilot.web;

import com.diagramas.platform.common.security.CurrentUser;
import com.diagramas.platform.common.util.Json;
import com.diagramas.platform.copilot.domain.AiChat;
import com.diagramas.platform.copilot.service.AiChatService;
import com.diagramas.platform.copilot.service.CopilotService;
import com.diagramas.platform.copilot.service.CopilotService.AiOutcome;
import com.diagramas.platform.copilot.service.SequenceService;
import com.diagramas.platform.design.dto.DiagramDto;
import java.util.List;
import java.util.UUID;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

/** Frontera GraphQL del Copilot IA: CU-12, CU-13 y CU-20. Solo DESIGNER. */
@Controller
@PreAuthorize("hasRole('DESIGNER')")
public class CopilotGraphQlController {

    public record AiChatDto(UUID id, UUID diagramId, String title, Object messages, String createdAt, String updatedAt) {
        static AiChatDto of(AiChat c) {
            return new AiChatDto(c.getId(), c.getDiagramId(), c.getTitle(), Json.plain(c.getMessages()),
                    String.valueOf(c.getCreatedAt()), String.valueOf(c.getUpdatedAt()));
        }
    }

    public record AiResultDto(String explanation, Object operations, DiagramDto diagram) {}

    private final CopilotService copilot;
    private final AiChatService chats;
    private final SequenceService sequences;

    public CopilotGraphQlController(CopilotService copilot, AiChatService chats, SequenceService sequences) {
        this.copilot = copilot;
        this.chats = chats;
        this.sequences = sequences;
    }

    @QueryMapping
    public List<AiChatDto> aiChats(@Argument UUID diagramId) {
        return chats.list(CurrentUser.get(), diagramId).stream().map(AiChatDto::of).toList();
    }

    @MutationMapping
    public AiResultDto sendAiInstruction(@Argument UUID diagramId, @Argument String instruction, @Argument String inputType) {
        AiOutcome out = copilot.sendInstruction(CurrentUser.get(), diagramId, instruction, inputType);
        return new AiResultDto(out.explanation(), Json.plain(CopilotService.toArray(out.operations())), DiagramDto.of(out.diagram()));
    }

    @MutationMapping
    public DiagramDto confirmAiChanges(@Argument UUID diagramId) {
        return DiagramDto.of(copilot.confirm(CurrentUser.get(), diagramId));
    }

    @MutationMapping
    public DiagramDto generateSequenceDiagram(@Argument UUID sourceDiagramId) {
        return DiagramDto.of(sequences.generate(CurrentUser.get(), sourceDiagramId));
    }
}
