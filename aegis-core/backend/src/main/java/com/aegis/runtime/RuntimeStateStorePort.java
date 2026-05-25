package com.aegis.runtime;

import java.util.List;
import java.util.Optional;

public interface RuntimeStateStorePort {

    String backendLabel();

    WorkflowExecution save(WorkflowExecution workflow);

    Optional<WorkflowExecution> find(String workflowId);

    List<WorkflowExecution> recent(int limit);

    List<WorkflowExecution> resumable();

    WorkflowExecution updateStep(
            WorkflowExecution workflow,
            WorkflowStepExecution nextStep,
            WorkflowStatus status,
            List<AgentExecution> agentExecutions,
            List<RetryState> retries
    );
}
