package com.diagramas.platform.codegen.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "generated_code")
@Getter
@Setter
public class GeneratedCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "diagram_id")
    private UUID diagramId;

    @Column(name = "task_id")
    private UUID taskId;

    @Column(name = "user_id")
    private UUID userId;

    private String language;

    @Column(name = "code_content")
    private String codeContent;

    @Column(name = "file_name")
    private String fileName;

    private String status = "SUCCESS";

    @Column(name = "created_at")
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
