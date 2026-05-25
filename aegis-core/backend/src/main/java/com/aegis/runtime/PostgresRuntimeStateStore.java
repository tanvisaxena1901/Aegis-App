package com.aegis.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "aegis.runtime.state-store", havingValue = "postgres")
public class PostgresRuntimeStateStore implements RuntimeStateStorePort {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;

    public PostgresRuntimeStateStore(DatabaseClient runtimeDatabaseClient, ObjectMapper objectMapper) {
        this.databaseClient = runtimeDatabaseClient;
        this.objectMapper = objectMapper;
        initializeSchema();
    }

    @Override
    public String backendLabel() {
        return "POSTGRES_R2DBC_READY";
    }

    @Override
    public WorkflowExecution save(WorkflowExecution workflow) {
        upsertWorkflow(workflow);
        deleteChildren(workflow.workflowId());
        insertSteps(workflow);
        insertAgentExecutions(workflow);
        insertRetries(workflow);
        return workflow;
    }

    @Override
    public Optional<WorkflowExecution> find(String workflowId) {
        return loadWorkflow(workflowId);
    }

    @Override
    public List<WorkflowExecution> recent(int limit) {
        return databaseClient.sql("""
                select workflow_id
                from workflow_execution
                order by updated_at desc
                limit :limit
                """)
                .bind("limit", limit)
                .map((row, metadata) -> row.get("workflow_id", String.class))
                .all()
                .collectList()
                .blockOptional()
                .orElse(List.of())
                .stream()
                .map(this::loadWorkflow)
                .flatMap(Optional::stream)
                .toList();
    }

    @Override
    public List<WorkflowExecution> resumable() {
        return databaseClient.sql("""
                select workflow_id
                from workflow_execution
                where status in ('PENDING', 'RUNNING', 'RETRYING')
                order by updated_at asc
                """)
                .map((row, metadata) -> row.get("workflow_id", String.class))
                .all()
                .collectList()
                .blockOptional()
                .orElse(List.of())
                .stream()
                .map(this::loadWorkflow)
                .flatMap(Optional::stream)
                .filter(workflow -> workflow.steps().stream().anyMatch(step -> step.status() == WorkflowStepStatus.PENDING
                        || (step.status() == WorkflowStepStatus.FAILED && step.nextAttemptAt() != null)))
                .toList();
    }

    @Override
    public WorkflowExecution updateStep(
            WorkflowExecution workflow,
            WorkflowStepExecution nextStep,
            WorkflowStatus status,
            List<AgentExecution> agentExecutions,
            List<RetryState> retries
    ) {
        List<WorkflowStepExecution> steps = new ArrayList<>();
        for (WorkflowStepExecution step : workflow.steps()) {
            steps.add(step.stepId().equals(nextStep.stepId()) ? nextStep : step);
        }
        WorkflowExecution updated = new WorkflowExecution(
                workflow.workflowId(),
                workflow.incidentId(),
                workflow.service(),
                workflow.namespace(),
                workflow.clusterId(),
                workflow.symptom(),
                workflow.signals(),
                nextCurrentStep(steps),
                status,
                List.copyOf(steps),
                List.copyOf(agentExecutions),
                List.copyOf(retries),
                workflow.createdAt(),
                java.time.Instant.now()
        );
        return save(updated);
    }

    private void initializeSchema() {
        execute("""
                create table if not exists workflow_execution (
                    workflow_id text primary key,
                    incident_id text not null,
                    service text not null,
                    namespace text not null,
                    cluster_id text not null,
                    symptom text not null,
                    signals_json text not null,
                    current_step text not null,
                    status text not null,
                    created_at text not null,
                    updated_at text not null
                )
                """);
        execute("""
                create table if not exists workflow_step (
                    step_id text primary key,
                    workflow_id text not null,
                    step_order integer not null,
                    step_type text not null,
                    agent_type text not null,
                    status text not null,
                    attempt integer not null,
                    max_attempts integer not null,
                    output_text text not null,
                    evidence_json text not null,
                    started_at text,
                    completed_at text,
                    next_attempt_at text
                )
                """);
        execute("""
                create table if not exists agent_execution (
                    execution_id text primary key,
                    workflow_id text not null,
                    step_id text not null,
                    agent_type text not null,
                    status text not null,
                    input_signals_json text not null,
                    output_text text not null,
                    started_at text,
                    completed_at text
                )
                """);
        execute("""
                create table if not exists retry_state (
                    id bigserial primary key,
                    workflow_id text not null,
                    step_id text not null,
                    attempt integer not null,
                    max_attempts integer not null,
                    backoff_millis bigint not null,
                    next_attempt_at text,
                    last_error text not null
                )
                """);
    }

    private void upsertWorkflow(WorkflowExecution workflow) {
        execute("""
                insert into workflow_execution (
                    workflow_id, incident_id, service, namespace, cluster_id, symptom,
                    signals_json, current_step, status, created_at, updated_at
                ) values (
                    :workflowId, :incidentId, :service, :namespace, :clusterId, :symptom,
                    :signalsJson, :currentStep, :status, :createdAt, :updatedAt
                )
                on conflict (workflow_id) do update set
                    incident_id = excluded.incident_id,
                    service = excluded.service,
                    namespace = excluded.namespace,
                    cluster_id = excluded.cluster_id,
                    symptom = excluded.symptom,
                    signals_json = excluded.signals_json,
                    current_step = excluded.current_step,
                    status = excluded.status,
                    created_at = excluded.created_at,
                    updated_at = excluded.updated_at
                """, bindMap(
                "workflowId", workflow.workflowId(),
                "incidentId", workflow.incidentId(),
                "service", workflow.service(),
                "namespace", workflow.namespace(),
                "clusterId", workflow.clusterId(),
                "symptom", workflow.symptom(),
                "signalsJson", toJson(workflow.signals()),
                "currentStep", workflow.currentStep(),
                "status", workflow.status().name(),
                "createdAt", workflow.createdAt().toString(),
                "updatedAt", workflow.updatedAt().toString()
        ));
    }

    private void deleteChildren(String workflowId) {
        execute("delete from workflow_step where workflow_id = :workflowId", Map.of("workflowId", workflowId));
        execute("delete from agent_execution where workflow_id = :workflowId", Map.of("workflowId", workflowId));
        execute("delete from retry_state where workflow_id = :workflowId", Map.of("workflowId", workflowId));
    }

    private void insertSteps(WorkflowExecution workflow) {
        int index = 0;
        for (WorkflowStepExecution step : workflow.steps()) {
            execute("""
                    insert into workflow_step (
                        step_id, workflow_id, step_order, step_type, agent_type, status,
                        attempt, max_attempts, output_text, evidence_json, started_at, completed_at, next_attempt_at
                    ) values (
                        :stepId, :workflowId, :stepOrder, :stepType, :agentType, :status,
                        :attempt, :maxAttempts, :outputText, :evidenceJson, :startedAt, :completedAt, :nextAttemptAt
                    )
                """, bindMap(
                    "stepId", step.stepId(),
                    "workflowId", workflow.workflowId(),
                    "stepOrder", index++,
                    "stepType", step.stepType().name(),
                    "agentType", step.agentType().name(),
                    "status", step.status().name(),
                    "attempt", step.attempt(),
                    "maxAttempts", step.maxAttempts(),
                    "outputText", step.output(),
                    "evidenceJson", toJson(step.evidence()),
                    "startedAt", text(step.startedAt()),
                    "completedAt", text(step.completedAt()),
                    "nextAttemptAt", text(step.nextAttemptAt())
            ));
        }
    }

    private void insertAgentExecutions(WorkflowExecution workflow) {
        for (AgentExecution execution : workflow.agentExecutions()) {
            execute("""
                    insert into agent_execution (
                        execution_id, workflow_id, step_id, agent_type, status,
                        input_signals_json, output_text, started_at, completed_at
                    ) values (
                        :executionId, :workflowId, :stepId, :agentType, :status,
                        :inputSignalsJson, :outputText, :startedAt, :completedAt
                    )
                """, bindMap(
                    "executionId", execution.executionId(),
                    "workflowId", execution.workflowId(),
                    "stepId", execution.stepId(),
                    "agentType", execution.agentType().name(),
                    "status", execution.status(),
                    "inputSignalsJson", toJson(execution.inputSignals()),
                    "outputText", execution.output(),
                    "startedAt", text(execution.startedAt()),
                    "completedAt", text(execution.completedAt())
            ));
        }
    }

    private void insertRetries(WorkflowExecution workflow) {
        for (RetryState retry : workflow.retries()) {
            execute("""
                    insert into retry_state (
                        workflow_id, step_id, attempt, max_attempts, backoff_millis, next_attempt_at, last_error
                    ) values (
                        :workflowId, :stepId, :attempt, :maxAttempts, :backoffMillis, :nextAttemptAt, :lastError
                    )
                """, bindMap(
                    "workflowId", retry.workflowId(),
                    "stepId", retry.stepId(),
                    "attempt", retry.attempt(),
                    "maxAttempts", retry.maxAttempts(),
                    "backoffMillis", retry.backoffMillis(),
                    "nextAttemptAt", text(retry.nextAttemptAt()),
                    "lastError", retry.lastError()
            ));
        }
    }

    private Optional<WorkflowExecution> loadWorkflow(String workflowId) {
        Map<String, String> workflowRow = databaseClient.sql("""
                select workflow_id, incident_id, service, namespace, cluster_id, symptom,
                       signals_json, current_step, status, created_at, updated_at
                from workflow_execution
                where workflow_id = :workflowId
                """)
                .bind("workflowId", workflowId)
                .map((row, metadata) -> {
                    Map<String, String> values = new HashMap<>();
                    values.put("workflowId", row.get("workflow_id", String.class));
                    values.put("incidentId", row.get("incident_id", String.class));
                    values.put("service", row.get("service", String.class));
                    values.put("namespace", row.get("namespace", String.class));
                    values.put("clusterId", row.get("cluster_id", String.class));
                    values.put("symptom", row.get("symptom", String.class));
                    values.put("signalsJson", row.get("signals_json", String.class));
                    values.put("currentStep", row.get("current_step", String.class));
                    values.put("status", row.get("status", String.class));
                    values.put("createdAt", row.get("created_at", String.class));
                    values.put("updatedAt", row.get("updated_at", String.class));
                    return values;
                })
                .one()
                .blockOptional()
                .orElse(null);
        if (workflowRow == null) {
            return Optional.empty();
        }
        List<WorkflowStepExecution> steps = loadSteps(workflowId);
        List<AgentExecution> agents = loadAgents(workflowId);
        List<RetryState> retries = loadRetries(workflowId);
        return Optional.of(new WorkflowExecution(
                string(workflowRow.get("workflowId")),
                string(workflowRow.get("incidentId")),
                string(workflowRow.get("service")),
                string(workflowRow.get("namespace")),
                string(workflowRow.get("clusterId")),
                string(workflowRow.get("symptom")),
                parseStringList(string(workflowRow.get("signalsJson"))),
                string(workflowRow.get("currentStep")),
                WorkflowStatus.valueOf(string(workflowRow.get("status"))),
                steps,
                agents,
                retries,
                parseInstant(string(workflowRow.get("createdAt"))),
                parseInstant(string(workflowRow.get("updatedAt")))
        ));
    }

    private List<WorkflowStepExecution> loadSteps(String workflowId) {
        return databaseClient.sql("""
                select step_id, step_type, agent_type, status, attempt, max_attempts, output_text,
                       evidence_json, started_at, completed_at, next_attempt_at
                from workflow_step
                where workflow_id = :workflowId
                order by step_order asc
                """)
                .bind("workflowId", workflowId)
                .map((row, metadata) -> new WorkflowStepExecution(
                        row.get("step_id", String.class),
                        WorkflowStepType.valueOf(row.get("step_type", String.class)),
                        AgentType.valueOf(row.get("agent_type", String.class)),
                        WorkflowStepStatus.valueOf(row.get("status", String.class)),
                        row.get("attempt", Integer.class) == null ? 0 : row.get("attempt", Integer.class),
                        row.get("max_attempts", Integer.class) == null ? 0 : row.get("max_attempts", Integer.class),
                        row.get("output_text", String.class),
                        parseStringList(row.get("evidence_json", String.class)),
                        parseInstant(row.get("started_at", String.class)),
                        parseInstant(row.get("completed_at", String.class)),
                        parseInstant(row.get("next_attempt_at", String.class))
                ))
                .all()
                .collectList()
                .blockOptional()
                .orElse(List.of());
    }

    private List<AgentExecution> loadAgents(String workflowId) {
        return databaseClient.sql("""
                select execution_id, step_id, agent_type, status, input_signals_json, output_text, started_at, completed_at
                from agent_execution
                where workflow_id = :workflowId
                order by started_at asc nulls last
                """)
                .bind("workflowId", workflowId)
                .map((row, metadata) -> new AgentExecution(
                        row.get("execution_id", String.class),
                        workflowId,
                        row.get("step_id", String.class),
                        AgentType.valueOf(row.get("agent_type", String.class)),
                        row.get("status", String.class),
                        parseStringList(row.get("input_signals_json", String.class)),
                        row.get("output_text", String.class),
                        parseInstant(row.get("started_at", String.class)),
                        parseInstant(row.get("completed_at", String.class))
                ))
                .all()
                .collectList()
                .blockOptional()
                .orElse(List.of());
    }

    private List<RetryState> loadRetries(String workflowId) {
        return databaseClient.sql("""
                select workflow_id, step_id, attempt, max_attempts, backoff_millis, next_attempt_at, last_error
                from retry_state
                where workflow_id = :workflowId
                order by next_attempt_at asc nulls last
                """)
                .bind("workflowId", workflowId)
                .map((row, metadata) -> new RetryState(
                        row.get("workflow_id", String.class),
                        row.get("step_id", String.class),
                        row.get("attempt", Integer.class) == null ? 0 : row.get("attempt", Integer.class),
                        row.get("max_attempts", Integer.class) == null ? 0 : row.get("max_attempts", Integer.class),
                        row.get("backoff_millis", Long.class) == null ? 0L : row.get("backoff_millis", Long.class),
                        parseInstant(row.get("next_attempt_at", String.class)),
                        row.get("last_error", String.class)
                ))
                .all()
                .collectList()
                .blockOptional()
                .orElse(List.of());
    }

    private void execute(String sql) {
        databaseClient.sql(sql).then().block();
    }

    private void execute(String sql, Map<String, Object> bindings) {
        var spec = databaseClient.sql(sql);
        for (Map.Entry<String, Object> entry : bindings.entrySet()) {
            spec = spec.bind(entry.getKey(), entry.getValue());
        }
        spec.then().block();
    }

    private Map<String, Object> bindMap(Object... keyValues) {
        Map<String, Object> bindings = new HashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            bindings.put(String.valueOf(keyValues[index]), keyValues[index + 1]);
        }
        return bindings;
    }

    private String text(java.time.Instant instant) {
        return instant == null ? null : instant.toString();
    }

    private java.time.Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return java.time.Instant.parse(value);
    }

    private List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception exception) {
            return List.of(json);
        }
    }

    private String toJson(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list == null ? List.of() : list);
        } catch (Exception exception) {
            return "[]";
        }
    }

    private String nextCurrentStep(List<WorkflowStepExecution> steps) {
        return steps.stream()
                .filter(step -> step.status() == WorkflowStepStatus.PENDING
                        || step.status() == WorkflowStepStatus.RUNNING
                        || step.status() == WorkflowStepStatus.FAILED)
                .map(step -> step.stepType().name())
                .findFirst()
                .orElse("COMPLETE");
    }

    private String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
