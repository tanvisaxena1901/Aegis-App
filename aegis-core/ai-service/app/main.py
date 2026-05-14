from fastapi import FastAPI

from app.graph import investigate
from app.models import IncidentInvestigationRequest, IncidentInvestigationResponse

app = FastAPI(
    title="Aegis AI Service",
    description="LangGraph-powered Kubernetes RCA and remediation planning service.",
    version="0.2.0",
)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/v1/rca/investigate", response_model=IncidentInvestigationResponse)
def investigate_incident(request: IncidentInvestigationRequest) -> IncidentInvestigationResponse:
    return investigate(request)
