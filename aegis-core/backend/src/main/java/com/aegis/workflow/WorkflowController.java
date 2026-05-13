package com.aegis.workflow;

import com.aegis.task.TaskResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workflows")
@RequiredArgsConstructor
public class WorkflowController {

    private final WorkflowService workflowService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkflowResponse createWorkflow(@Valid @RequestBody CreateWorkflowRequest request) {
        return workflowService.createWorkflow(request.request());
    }

    @GetMapping
    public List<WorkflowResponse> listWorkflows() {
        return workflowService.listWorkflows();
    }

    @GetMapping("/{workflowId}")
    public WorkflowResponse getWorkflow(@PathVariable UUID workflowId) {
        return workflowService.getWorkflow(workflowId);
    }

    @GetMapping("/{workflowId}/tasks")
    public List<TaskResponse> getWorkflowTasks(@PathVariable UUID workflowId) {
        return workflowService.getWorkflowTasks(workflowId);
    }
}
