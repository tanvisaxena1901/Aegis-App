package com.aegis.execution;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExecutionLogRepository extends JpaRepository<ExecutionLog, UUID> {

    List<ExecutionLog> findByWorkflowIdOrderByCreatedAtAsc(UUID workflowId);
}
