package com.aegis.runtime;

import java.util.List;

public interface OperationalMemoryGraphPort {

    String backendLabel();

    void ingestIncident(RuntimeIncidentRequest request, String incidentId, String workflowId);

    void ingestMemoryWrites(String workflowId, List<String> memoryWrites);

    void ensureWorkflowNode(String workflowId, String service);

    List<String> retrieve(String service, String namespace, String symptom);

    OperationalMemoryGraph snapshot();
}
