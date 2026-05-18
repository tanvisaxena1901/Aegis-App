from datetime import datetime, timezone
from enum import Enum
from typing import List
from uuid import uuid4

from pydantic import BaseModel, Field


class IncidentSeverity(str, Enum):
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


class IncidentInvestigationRequest(BaseModel):
    namespace: str
    resourceKind: str
    resourceName: str
    symptom: str
    events: List[str] = Field(default_factory=list)
    logs: List[str] = Field(default_factory=list)
    metrics: List[str] = Field(default_factory=list)


class IncidentInvestigationResponse(BaseModel):
    incidentId: str = Field(default_factory=lambda: str(uuid4()))
    severity: IncidentSeverity
    probableCause: str
    summary: str
    evidence: List[str]
    recommendedActions: List[str]
    humanApprovalRequired: bool
    generatedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class ChatMessage(BaseModel):
    role: str
    content: str


class GeneralChatRequest(BaseModel):
    message: str
    history: List[ChatMessage] = Field(default_factory=list)
    evidence: List[str] = Field(default_factory=list)
    metrics: List[str] = Field(default_factory=list)


class GeneralChatResponse(BaseModel):
    answer: str
    model: str
    ollamaAvailable: bool
    generatedAt: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))
