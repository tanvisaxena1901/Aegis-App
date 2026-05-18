from typing import List, TypedDict

from langgraph.graph import END, StateGraph

from app.llm import complete_chat
from app.models import IncidentInvestigationRequest, IncidentInvestigationResponse, IncidentSeverity


class InvestigationState(TypedDict):
    request: IncidentInvestigationRequest
    evidence: List[str]
    severity: IncidentSeverity
    probable_cause: str
    actions: List[str]


def collect_evidence(state: InvestigationState) -> InvestigationState:
    request = state["request"]
    evidence: List[str] = []
    evidence.extend(request.events[:16])
    evidence.extend([f"log: {line}" for line in request.logs[-12:] if line and line.strip()])
    evidence.extend(request.metrics[:8])
    evidence = evidence[:24] or [request.symptom]
    return {**state, "evidence": evidence}


def classify_severity(state: InvestigationState) -> InvestigationState:
    text = " ".join(state["evidence"]).lower()
    if any(token in text for token in ["oom", "outofmemory", "crashloopbackoff", "evicted"]):
        severity = IncidentSeverity.HIGH
    elif any(token in text for token in [
        "back-off",
        "failed",
        "unhealthy",
        "connection refused",
        "context deadline",
        "timeout",
        "probe failed",
        "previous termination/error",
    ]):
        severity = IncidentSeverity.MEDIUM
    else:
        severity = IncidentSeverity.LOW
    return {**state, "severity": severity}


def infer_cause(state: InvestigationState) -> InvestigationState:
    text = " ".join(state["evidence"]).lower()
    if "oom" in text or "outofmemory" in text or "memory" in text:
        cause = "The workload likely exceeded its memory limit and entered a restart loop."
    elif any(token in text for token in ["connection refused", "connection reset", "timeout", "context deadline"]):
        cause = "The workload or its health endpoint is timing out or refusing connections, which commonly points to slow startup, wrong port/path, or dependency latency."
    elif any(token in text for token in ["exception", "traceback", "stacktrace", "panic", "fatal"]):
        cause = "Recent logs show application-level errors, so the pod may be restarting or failing probes because the process is unhealthy."
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
    elif "health endpoint" in cause or "application-level" in cause:
        actions = [
            "Check current and previous logs for the selected pod.",
            "Verify the container is listening on the probe port and path.",
            "Restart only after confirming the app is wedged; otherwise fix config/dependency errors first.",
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
    llm_response = ask_model(request, state["evidence"], severity)
    if llm_response:
        return IncidentInvestigationResponse(
            severity=severity,
            probableCause=llm_response,
            summary=f"Aegis analyzed live Kubernetes evidence for: {request.symptom}",
            evidence=state["evidence"],
            recommendedActions=state["actions"],
            humanApprovalRequired=severity in {IncidentSeverity.HIGH, IncidentSeverity.CRITICAL},
        )

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


def ask_model(
    request: IncidentInvestigationRequest,
    evidence: List[str],
    severity: IncidentSeverity,
) -> str:
    prompt = (
        "You are Aegis, a Kubernetes SRE copilot. Answer the user's question directly using only "
        "the provided live Kubernetes evidence. Be specific, mention pod/deployment names, explain "
        "what is healthy or unhealthy, then give RCA and next checks when there is a problem. "
        "If the evidence is insufficient, say exactly what is missing. Do not invent data.\n\n"
        f"Question: {request.symptom}\n"
        f"Namespace: {request.namespace}\n"
        f"Target: {request.resourceKind} {request.resourceName}\n"
        f"Initial severity: {severity.value}\n\n"
        "Evidence:\n"
        + "\n".join(f"- {item}" for item in evidence[:16])
        + "\n\n"
        "Metrics:\n"
        + "\n".join(f"- {item}" for item in request.metrics[:12])
    )

    result = complete_chat(
        [
            {
                "role": "system",
                "content": (
                    "You are Aegis, a Kubernetes SRE copilot. Use only the provided cluster evidence "
                    "for Kubernetes facts. Include RCA, source signals, and next checks. Say what data "
                    "is missing instead of inventing it. Keep the answer compact unless the user asks "
                    "for deep detail."
                ),
            },
            {"role": "user", "content": prompt},
        ],
        max_tokens=380,
        temperature=0.2,
    )
    if not result["available"]:
        return ""
    return str(result["answer"])[:4000]
