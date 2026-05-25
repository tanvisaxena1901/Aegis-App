package com.aegis.runtime;

import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class AgentRegistry {

    public List<AgentRegistryEntry> agents() {
        return List.of(
                new AgentRegistryEntry(
                        AgentType.INCIDENT_AGENT,
                        "Incident Agent",
                        List.of(WorkflowStepType.FETCH_LOGS, WorkflowStepType.FETCH_METRICS),
                        "Java runtime + Kubernetes client",
                        "Collects live incident evidence and normalizes it into workflow signals."
                ),
                new AgentRegistryEntry(
                        AgentType.DEPLOYMENT_AGENT,
                        "Deployment Agent",
                        List.of(WorkflowStepType.FETCH_DEPLOYMENT, WorkflowStepType.ANALYZE_DEPLOYMENT),
                        "Python LangGraph",
                        "Compares rollout, deployment, and change signals before RCA."
                ),
                new AgentRegistryEntry(
                        AgentType.RCA_AGENT,
                        "RCA Agent",
                        List.of(WorkflowStepType.RETRIEVE_MEMORY, WorkflowStepType.GENERATE_RCA),
                        "Python LangGraph + operational memory",
                        "Retrieves related incidents and generates causal reasoning."
                ),
                new AgentRegistryEntry(
                        AgentType.RELIABILITY_AGENT,
                        "Reliability Agent",
                        List.of(WorkflowStepType.PROPOSE_REMEDIATION),
                        "Java policy engine",
                        "Converts RCA into approval-gated remediation candidates."
                )
        );
    }

    public AgentType ownerFor(WorkflowStepType stepType) {
        return agents().stream()
                .filter(agent -> agent.ownsSteps().contains(stepType))
                .map(AgentRegistryEntry::agentType)
                .findFirst()
                .orElse(AgentType.INCIDENT_AGENT);
    }
}
