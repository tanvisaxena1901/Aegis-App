package com.aegis.execution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "execution_logs")
@Getter
@Setter
@NoArgsConstructor
public class ExecutionLog {

    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID workflowId;

    private UUID taskId;

    @Column(nullable = false)
    private String level;

    @Column(nullable = false, columnDefinition = "text")
    private String message;

    @Column(nullable = false)
    private Instant createdAt;

    public ExecutionLog(UUID workflowId, UUID taskId, String level, String message) {
        this.id = UUID.randomUUID();
        this.workflowId = workflowId;
        this.taskId = taskId;
        this.level = level;
        this.message = message;
    }

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }
}
