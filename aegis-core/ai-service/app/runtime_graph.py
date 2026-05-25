from typing import List, TypedDict

from langgraph.graph import END, StateGraph

from app.llm import complete_chat
from app.models import RuntimeAgentTaskRequest, RuntimeAgentTaskResponse


class RuntimeAgentState(TypedDict):
    request: RuntimeAgentTaskRequest
    evidence: List[str]
    memory: List[str]
    analysis: str
    memory_writes: List[str]
    model_available: bool


def normalize_evidence(state: RuntimeAgentState) -> RuntimeAgentState:
    request = state["request"]
    evidence: List[str] = []
    evidence.extend(request.signals[:24])
    evidence.extend([f"memory: {item}" for item in request.memory[:8]])
    if not evidence:
        evidence.append(request.symptom)
    return {**state, "evidence": evidence[:32], "memory": request.memory[:8]}


def retrieve_operational_memory(state: RuntimeAgentState) -> RuntimeAgentState:
    request = state["request"]
    memory = list(state["memory"])
    if not memory:
        memory.append(f"No prior memory matched {request.service} in {request.namespace}.")
    return {**state, "memory": memory}


def reason_about_step(state: RuntimeAgentState) -> RuntimeAgentState:
    request = state["request"]
    evidence = state["evidence"]
    memory = state["memory"]
    prompt = (
        "You are an Aegis autonomous infrastructure runtime agent. "
        "Use deterministic Kubernetes evidence first, then operational memory. "
        "Do not claim remediation was executed. Keep the answer specific and concise.\n\n"
        f"Workflow: {request.workflowId}\n"
        f"Incident: {request.incidentId}\n"
        f"Agent: {request.agentType}\n"
        f"Step: {request.step}\n"
        f"Service: {request.service}\n"
        f"Namespace: {request.namespace}\n"
        f"Symptom: {request.symptom}\n\n"
        "Signals:\n"
        + "\n".join(f"- {item}" for item in evidence[:18])
        + "\n\nOperational memory:\n"
        + "\n".join(f"- {item}" for item in memory[:8])
    )
    result = complete_chat(
        [
            {
                "role": "system",
                "content": (
                    "You are an autonomous Kubernetes incident runtime agent. "
                    "Reason over workflows, retries, deployment state, and memory graph context. "
                    "Recommend only approval-gated actions."
                ),
            },
            {"role": "user", "content": prompt},
        ],
        max_tokens=360,
        temperature=0.2,
    )
    if result["available"]:
        return {
            **state,
            "analysis": str(result["answer"])[:3000],
            "model_available": True,
        }

    return {
        **state,
        "analysis": deterministic_runtime_answer(request, evidence, memory),
        "model_available": False,
    }


def write_memory(state: RuntimeAgentState) -> RuntimeAgentState:
    request = state["request"]
    writes = [
        f"{request.step} completed for {request.namespace}/{request.service}: {state['analysis'][:180]}",
    ]
    if any(has_memory_pressure_signal(item) for item in state["evidence"]):
        writes.append(f"{request.service} has memory-pressure incident evidence in workflow {request.workflowId}.")
    if any("probe" in item.lower() or "unhealthy" in item.lower() for item in state["evidence"]):
        writes.append(f"{request.service} has probe-failure evidence in workflow {request.workflowId}.")
    return {**state, "memory_writes": writes}


def build_runtime_graph():
    graph = StateGraph(RuntimeAgentState)
    graph.add_node("normalize_evidence", normalize_evidence)
    graph.add_node("retrieve_operational_memory", retrieve_operational_memory)
    graph.add_node("reason_about_step", reason_about_step)
    graph.add_node("write_memory", write_memory)
    graph.set_entry_point("normalize_evidence")
    graph.add_edge("normalize_evidence", "retrieve_operational_memory")
    graph.add_edge("retrieve_operational_memory", "reason_about_step")
    graph.add_edge("reason_about_step", "write_memory")
    graph.add_edge("write_memory", END)
    return graph.compile()


RUNTIME_GRAPH = build_runtime_graph()


def execute_runtime_task(request: RuntimeAgentTaskRequest) -> RuntimeAgentTaskResponse:
    state = RUNTIME_GRAPH.invoke(
        {
            "request": request,
            "evidence": [],
            "memory": [],
            "analysis": "",
            "memory_writes": [],
            "model_available": False,
        }
    )
    return RuntimeAgentTaskResponse(
        workflowId=request.workflowId,
        step=request.step,
        agentType=request.agentType,
        status="SUCCEEDED",
        output=state["analysis"],
        evidence=state["evidence"],
        memoryWrites=state["memory_writes"],
        modelAvailable=state["model_available"],
    )


def deterministic_runtime_answer(
    request: RuntimeAgentTaskRequest,
    evidence: List[str],
    memory: List[str],
) -> str:
    text = " ".join(evidence + memory).lower()
    if request.step == "ANALYZE_DEPLOYMENT":
        if "imagepull" in text or "errimagepull" in text:
            return f"{request.service} analysis: deployment is likely blocked on image pull or registry authentication. Verify image tag, pull secret, and rollout events before rollback."
        if has_memory_pressure_signal(text):
            return f"{request.service} analysis: deployment has memory-pressure evidence. Compare current memory limits with the previous deployment and recent OOMKilled events."
        if "probe" in text or "unhealthy" in text:
            return f"{request.service} analysis: deployment appears probe-related. Compare readiness/liveness paths, timeouts, and startup behavior before remediation."
        return f"{request.service} analysis: no single deterministic deployment cause is confirmed; correlate rollout, events, logs, and memory graph neighbors."
    if request.step == "GENERATE_RCA":
        if "crashloopbackoff" in text:
            return f"{request.service} RCA: pod restart loop is the leading cause. Previous logs and deployment diff should be checked before restart or rollback."
        if "failedscheduling" in text or "pending" in text:
            return f"{request.service} RCA: scheduling failure is likely. Check node capacity, taints, selectors, PVCs, and namespace quotas."
        return f"{request.service} RCA: evidence is incomplete. Runtime should continue collecting logs, events, deployment state, and related memory before proposing action."
    return f"{request.agentType} completed {request.step} for {request.namespace}/{request.service} using deterministic runtime reasoning."


def has_memory_pressure_signal(value: str) -> bool:
    text = value.lower()
    return (
        "oom" in text
        or "out of memory" in text
        or "memory limit" in text
        or "memory-pressure" in text
        or "memorypressure" in text
    )
