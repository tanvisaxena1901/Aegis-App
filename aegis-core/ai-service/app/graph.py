from typing import List, TypedDict

from langgraph.graph import END, StateGraph

from app.models import IncidentInvestigationRequest, IncidentInvestigationResponse, IncidentSeverity


class InvestigationState(TypedDict):
    request: IncidentInvestigationRequest
    evidence: List[str]
    severity: IncidentSeverity
    probable_cause: str
    actions: List[str]


def collect_evidence(state: InvestigationState) -> InvestigationState:
    request = state["request"]
    signals = request.events + request.logs + request.metrics
    evidence = signals[:8] or [request.symptom]
    return {**state, "evidence": evidence}


def classify_severity(state: InvestigationState) -> InvestigationState:
    text = " ".join(state["evidence"]).lower()
    if any(token in text for token in ["oom", "outofmemory", "crashloopbackoff", "evicted"]):
        severity = IncidentSeverity.HIGH
    elif any(token in text for token in ["back-off", "failed", "unhealthy"]):
        severity = IncidentSeverity.MEDIUM
    else:
        severity = IncidentSeverity.LOW
    return {**state, "severity": severity}


def infer_cause(state: InvestigationState) -> InvestigationState:
    text = " ".join(state["evidence"]).lower()
    if "oom" in text or "outofmemory" in text or "memory" in text:
        cause = "The workload likely exceeded its memory limit and entered a restart loop."
    elif "imagepull" in text or "pull" in text:
        cause = "The workload likely cannot pull its container image or authenticate to the registry."
    elif "probe" in text or "unhealthy" in text:
        cause = "The workload is likely failing readiness or liveness probes."
    else:
        cause = "Aegis found an abnormal Kubernetes signal but needs more logs, events, and metrics for confidence."
    return {**state, "probable_cause": cause}


def plan_remediation(state: InvestigationState) -> InvestigationState:
    cause = state["probable_cause"].lower()
    if "memory" in cause:
        actions = [
            "Inspect container memory limits and recent deployment changes.",
            "Compare memory working set against configured limits.",
            "Increase memory limit or roll back the deployment after approval.",
        ]
    elif "image" in cause:
        actions = [
            "Verify image tag exists and registry credentials are valid.",
            "Check imagePullSecrets on the namespace and service account.",
            "Redeploy once registry access is fixed.",
        ]
    elif "probe" in cause:
        actions = [
            "Inspect readiness and liveness probe paths, ports, and initial delays.",
            "Check application startup time against probe thresholds.",
            "Patch probe settings or roll back the failing release after approval.",
        ]
    else:
        actions = [
            "Collect recent pod events, previous container logs, and deployment rollout history.",
            "Check node pressure, resource quotas, and recent configuration changes.",
            "Escalate to a human operator before remediation.",
        ]
    return {**state, "actions": actions}


def build_graph():
    graph = StateGraph(InvestigationState)
    graph.add_node("collect_evidence", collect_evidence)
    graph.add_node("classify_severity", classify_severity)
    graph.add_node("infer_cause", infer_cause)
    graph.add_node("plan_remediation", plan_remediation)

    graph.set_entry_point("collect_evidence")
    graph.add_edge("collect_evidence", "classify_severity")
    graph.add_edge("classify_severity", "infer_cause")
    graph.add_edge("infer_cause", "plan_remediation")
    graph.add_edge("plan_remediation", END)
    return graph.compile()


INVESTIGATION_GRAPH = build_graph()


def investigate(request: IncidentInvestigationRequest) -> IncidentInvestigationResponse:
    state = INVESTIGATION_GRAPH.invoke(
        {
            "request": request,
            "evidence": [],
            "severity": IncidentSeverity.LOW,
            "probable_cause": "",
            "actions": [],
        }
    )

    severity = state["severity"]
    return IncidentInvestigationResponse(
        severity=severity,
        probableCause=state["probable_cause"],
        summary=(
            f"{request.resourceKind} {request.namespace}/{request.resourceName} shows "
            f"{request.symptom}. Aegis classified this as {severity.value}."
        ),
        evidence=state["evidence"],
        recommendedActions=state["actions"],
        humanApprovalRequired=severity in {IncidentSeverity.HIGH, IncidentSeverity.CRITICAL},
    )
