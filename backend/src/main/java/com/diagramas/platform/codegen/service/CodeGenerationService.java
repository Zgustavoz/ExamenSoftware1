package com.diagramas.platform.codegen.service;

import com.diagramas.platform.codegen.domain.Task;
import com.diagramas.platform.codegen.generator.CodeGenerator;
import com.diagramas.platform.codegen.generator.CodeGenerator.GeneratedFile;
import com.diagramas.platform.codegen.repository.TaskRepository;
import com.diagramas.platform.common.error.ApiException;
import com.diagramas.platform.common.error.ErrorCode;
import com.diagramas.platform.common.security.AuthPrincipal;
import com.diagramas.platform.design.domain.Diagram;
import com.diagramas.platform.design.service.DiagramCompleteness;
import com.diagramas.platform.design.service.DiagramService;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CU-14 Generar código backend (flujo 9.3). No es transaccional a propósito: cada paso persiste por su cuenta
 * para que un fallo deje la tarea en FAILED en lugar de deshacerla.
 */
@Service
public class CodeGenerationService {

    private static final Logger log = LoggerFactory.getLogger(CodeGenerationService.class);
    static final String INCOMPLETE_MESSAGE = "El diagrama debe tener clases con atributos y métodos para poder generar código";

    private final DiagramService diagrams;
    private final CodegenPersistence persistence;
    private final TaskRepository tasks;
    private final List<CodeGenerator> generators;

    public CodeGenerationService(
            DiagramService diagrams, CodegenPersistence persistence, TaskRepository tasks, List<CodeGenerator> generators) {
        this.diagrams = diagrams;
        this.persistence = persistence;
        this.tasks = tasks;
        this.generators = generators;
    }

    public Task generate(AuthPrincipal p, UUID diagramId, String language) {
        String lang = normalizeLanguage(language);
        // validarDiagrama: inexistente (NOT_FOUND) o incompleto (DIAGRAM_INCOMPLETE) → NO se crea tarea (CP-06)
        Diagram diagram = diagrams.get(p, diagramId);
        if (!Diagram.TYPE_CLASS.equals(diagram.getType()) || !DiagramCompleteness.isComplete(diagram.getContentJson())) {
            throw new ApiException(ErrorCode.DIAGRAM_INCOMPLETE, INCOMPLETE_MESSAGE);
        }
        CodeGenerator generator = generators.stream()
                .filter(g -> g.language().equals(lang))
                .findFirst()
                .orElseThrow(() -> ApiException.validation("Lenguaje no soportado: " + language + "."));

        Task task = persistence.createTask(p, diagram, lang);
        persistence.start(task.getId());
        List<GeneratedFile> files;
        try {
            files = generator.generate(diagram.getContentJson());
        } catch (RuntimeException e) {
            log.error("Falló la generación de código de la tarea {}", task.getId(), e);
            persistence.fail(task.getId(), "Error al transformar el diagrama a código.");
            throw new ApiException(ErrorCode.GENERATION_FAILED, "No se pudo generar el código. Revise el diagrama e intente nuevamente.");
        }
        try {
            return persistence.complete(p, task, diagram, lang, files);
        } catch (RuntimeException e) {
            log.error("Falló la persistencia del código de la tarea {}", task.getId(), e);
            persistence.fail(task.getId(), "Error al guardar el código generado.");
            throw new ApiException(ErrorCode.GENERATION_FAILED, "No se pudo guardar el código generado. Intente nuevamente.");
        }
    }

    @Transactional(readOnly = true)
    public List<Task> list(AuthPrincipal p, UUID diagramId) {
        if (diagramId != null) {
            diagrams.get(p, diagramId);
            return tasks.findByCompanyIdAndDiagramIdOrderByCreatedAtDesc(p.companyId(), diagramId);
        }
        return tasks.findByCompanyIdOrderByCreatedAtDesc(p.companyId());
    }

    @Transactional(readOnly = true)
    public Task get(AuthPrincipal p, UUID id) {
        return tasks.findByIdAndCompanyId(id, p.companyId())
                .orElseThrow(() -> ApiException.notFound("La tarea no existe."));
    }

    /** Acepta «JAVA», «java», «Java/Spring Boot», «spring-boot»… (el único destino soportado es Java/Spring Boot). */
    static String normalizeLanguage(String language) {
        String l = language == null ? "" : language.trim().toLowerCase(Locale.ROOT);
        if (l.startsWith("java") || l.contains("spring")) {
            return "JAVA";
        }
        throw ApiException.validation("Lenguaje no soportado: " + language + ". Use Java/Spring Boot.");
    }
}
