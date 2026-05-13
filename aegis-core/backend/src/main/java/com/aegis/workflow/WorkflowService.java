package com.aegis.workflow;

import com.aegis.events.WorkflowCreatedEvent;
import com.aegis.execution.ExecutionLog;
import com.aegis.execution.ExecutionLogRepository;
import com.aegis.task.Task;
import com.aegis.task.TaskRepository;
import com.aegis.task.TaskResponse;
import com.aegis.task.TaskType;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class WorkflowService {

    private final WorkflowRepository workflowRepository;
    private final TaskRepository taskRepository;
    private final ExecutionLogRepository executionLogRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public WorkflowResponse createWorkflow(String request) {
        Workflow workflow = workflowRepository.save(new Workflow(request));

        List<Task> tasks = Arrays.stream(TaskType.values())
                .map(type -> new Task(workflow.getId(), type, request))
                .toList();
        taskRepository.saveAll(tasks);

        executionLogRepository.save(new ExecutionLog(
                workflow.getId(),
                null,
                "INFO",
                "Workflow created with " + tasks.size() + " planned tasks"
        ));

        eventPublisher.publishEvent(new WorkflowCreatedEvent(workflow.getId(), request, Instant.now()));
        return WorkflowResponse.from(workflow);
    }

    @Transactional(readOnly = true)
    public List<WorkflowResponse> listWorkflows() {
        return workflowRepository.findAll().stream()
                .map(WorkflowResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public WorkflowResponse getWorkflow(UUID workflowId) {
        return workflowRepository.findById(workflowId)
                .map(WorkflowResponse::from)
                .orElseThrow(() -> new WorkflowNotFoundException(workflowId));
    }

    @Transactional(readOnly = true)
    public List<TaskResponse> getWorkflowTasks(UUID workflowId) {
        if (!workflowRepository.existsById(workflowId)) {
            throw new WorkflowNotFoundException(workflowId);
        }

        return taskRepository.findByWorkflowIdOrderByCreatedAtAsc(workflowId).stream()
                .map(TaskResponse::from)
                .toList();
    }
}
