import os

import httpx

from app.models import GeneralChatRequest, GeneralChatResponse

OLLAMA_TIMEOUT_SECONDS = float(os.getenv("OLLAMA_TIMEOUT_SECONDS", "90"))
OPENAI_TIMEOUT_SECONDS = float(os.getenv("OPENAI_TIMEOUT_SECONDS", "35"))
MAX_HISTORY_MESSAGES = int(os.getenv("MAX_HISTORY_MESSAGES", "4"))
MAX_HISTORY_CHARS = int(os.getenv("MAX_HISTORY_CHARS", "900"))

GENERAL_SYSTEM_PROMPT = (
    "You are Aegis, a concise ChatGPT-style assistant. "
    "Answer normal questions directly and naturally in 120 words or fewer unless the user asks for detail."
)

KUBERNETES_SYSTEM_PROMPT = (
    "You are Aegis, a ChatGPT-style assistant embedded in a Kubernetes operations dashboard. "
    "Answer any user question naturally. For Kubernetes questions, use the live evidence exactly as "
    "provided, synthesize it into a clear diagnosis, and cite the source signal in plain text such as "
    "pod namespace/name, event namespace/object, log namespace/pod, deployment namespace/name, or "
    "environment name. Do not dump raw signals unless the user asks for raw logs. Do not invent CPU, "
    "memory, pod, event, deployment, or log data that is not present. If evidence is incomplete, say "
    "what is missing and what command or data would confirm it. For incident questions, prefer this "
    "shape: direct answer, what the evidence shows, likely RCA, next checks, and safe remediation. "
    "For unrelated or conceptual questions, answer like a normal helpful LLM."
)


def chat(request: GeneralChatRequest) -> GeneralChatResponse:
    has_cluster_context = bool(request.evidence or request.metrics)
    messages = [
        {
            "role": "system",
            "content": KUBERNETES_SYSTEM_PROMPT if has_cluster_context else GENERAL_SYSTEM_PROMPT,
        }
    ]
    messages.extend(
        {
            "role": message.role if message.role in {"user", "assistant", "system"} else "user",
            "content": _truncate(message.content, MAX_HISTORY_CHARS),
        }
        for message in request.history[-MAX_HISTORY_MESSAGES:]
        if message.content.strip()
    )

    context = ""
    if request.evidence:
        context += "Live Kubernetes evidence:\n" + "\n".join(f"- {item}" for item in request.evidence[:24])
    if request.metrics:
        context += "\n\nAvailable metrics/context:\n" + "\n".join(f"- {item}" for item in request.metrics[:16])
    if "cpu_metrics=not_collected_yet" in request.metrics:
        context += (
            "\n\nMetric availability rule: CPU usage samples are not available in this snapshot. "
            "For CPU usage, answer that the highest CPU pod cannot be determined from the current data."
        )
    if "memory_metrics=not_collected_yet" in request.metrics:
        context += (
            "\nMetric availability rule: Memory usage samples are not available in this snapshot. "
            "For memory usage, answer that the highest memory pod cannot be determined from the current data."
        )
    user_content = request.message
    if context:
        user_content += "\n\n" + context
    messages.append({"role": "user", "content": user_content})

    token_budget = 90 if not has_cluster_context else 320
    model_override = os.getenv("OLLAMA_GENERAL_MODEL", "qwen2.5:0.5b") if not has_cluster_context else None
    result = complete_chat(messages, max_tokens=token_budget, temperature=0.25, model_override=model_override)
    if not result["available"]:
        result["answer"] = _local_fallback_answer(request, has_cluster_context, result["answer"])
    return GeneralChatResponse(
        answer=result["answer"],
        model=result["model"],
        ollamaAvailable=result["available"],
    )


def complete_chat(
    messages: list[dict[str, str]],
    max_tokens: int = 700,
    temperature: float = 0.25,
    model_override: str | None = None,
) -> dict[str, object]:
    provider = os.getenv("AI_PROVIDER", "auto").strip().lower()
    openai_key = os.getenv("OPENAI_API_KEY", "").strip()

    if provider in {"openai", "chatgpt"} or (provider == "auto" and openai_key):
        if not openai_key:
            return _unavailable("openai", os.getenv("OPENAI_MODEL", "gpt-4.1-mini"), "OPENAI_API_KEY is not set.")
        result = _complete_openai(messages, max_tokens, temperature, openai_key)
        if result["available"] or provider in {"openai", "chatgpt"}:
            return result

    return _complete_ollama(messages, max_tokens, temperature, model_override)


def _complete_openai(
    messages: list[dict[str, str]],
    max_tokens: int,
    temperature: float,
    api_key: str,
) -> dict[str, object]:
    base_url = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1").rstrip("/")
    model = os.getenv("OPENAI_MODEL", "gpt-4.1-mini")

    try:
        with httpx.Client(timeout=httpx.Timeout(OPENAI_TIMEOUT_SECONDS, connect=10.0)) as client:
            response = client.post(
                f"{base_url}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json={
                    "model": model,
                    "messages": messages,
                    "temperature": temperature,
                    "max_tokens": max_tokens,
                },
            )
            response.raise_for_status()
            answer = response.json().get("choices", [{}])[0].get("message", {}).get("content", "").strip()
            return {
                "answer": answer or "OpenAI returned an empty response.",
                "model": model,
                "available": True,
            }
    except Exception as exc:
        return _unavailable("OpenAI", model, f"{exc.__class__.__name__}: {exc}")


def _complete_ollama(
    messages: list[dict[str, str]],
    max_tokens: int,
    temperature: float,
    model_override: str | None = None,
) -> dict[str, object]:
    base_url = os.getenv("OLLAMA_BASE_URL", "http://localhost:11434").rstrip("/")
    model = model_override or os.getenv("OLLAMA_MODEL", "qwen2.5:1.5b")

    try:
        with httpx.Client(timeout=httpx.Timeout(OLLAMA_TIMEOUT_SECONDS, connect=5.0)) as client:
            response = client.post(
                f"{base_url}/api/chat",
                json={
                    "model": model,
                    "messages": messages,
                    "stream": False,
                    "keep_alive": "10m",
                    "options": {"temperature": temperature, "num_predict": max_tokens},
                },
            )
            response.raise_for_status()
            answer = response.json().get("message", {}).get("content", "").strip()
            return {
                "answer": answer or "Ollama returned an empty response.",
                "model": model,
                "available": True,
            }
    except Exception as exc:
        return _unavailable(
            "Ollama",
            model,
            f"{exc.__class__.__name__}: {exc}. Checked {base_url} with a {OLLAMA_TIMEOUT_SECONDS:.0f}s timeout.",
        )


def _unavailable(provider: str, model: str, detail: str) -> dict[str, object]:
    return {
        "answer": (
            f"{provider} is not available for this request, so I cannot generate a model answer right now. "
            f"{detail}"
        ),
        "model": model,
        "available": False,
    }


def _local_fallback_answer(request: GeneralChatRequest, has_cluster_context: bool, provider_detail: str) -> str:
    message = request.message.strip()
    normalized = message.lower()
    provider_note = provider_detail.split(". Checked ", 1)[0]

    if has_cluster_context:
        evidence = request.evidence[:8]
        metrics = request.metrics[:8]
        lines = [
            "I can still help from deterministic Aegis context, but the model provider is offline.",
            "",
            "What I can see:",
        ]
        if evidence:
            lines.extend(f"- {item}" for item in evidence)
        if metrics:
            lines.extend(f"- {item}" for item in metrics)
        if not evidence and not metrics:
            lines.append("- No live Kubernetes evidence was attached to this chat turn.")
        lines.extend(
            [
                "",
                "Next checks:",
                "- Use the runbook steps first: describe the resource, review warning events, and inspect previous logs if restarts are present.",
                "- Do not execute remediation until a specific action is approved.",
                "",
                f"Provider status: {provider_note}.",
            ]
        )
        return "\n".join(lines)

    if any(token in normalized for token in ["hello", "hi", "hey"]):
        return (
            "Hi. Aegis is online, but the local model provider is currently unavailable. "
            "I can still answer Kubernetes dashboard questions using deterministic runbooks, events, pods, rollout state, and logs."
        )
    if "what can you do" in normalized or "help" in normalized:
        return (
            "I can inspect Kubernetes health, summarize failing pods, group noisy events, explain runbook steps, and prepare approval-gated remediation plans. "
            "The free-form LLM provider is offline right now, so answers are deterministic until Ollama or OpenAI is available."
        )
    return (
        "Aegis received your message, but the model provider is offline. "
        "Ask about cluster health, failing pods, warning events, deployment rollout, logs, runbooks, or remediation approvals and I will answer from deterministic platform data. "
        f"Provider status: {provider_note}."
    )


def _truncate(value: str, max_chars: int) -> str:
    if len(value) <= max_chars:
        return value
    return value[:max_chars] + "\n[truncated]"
