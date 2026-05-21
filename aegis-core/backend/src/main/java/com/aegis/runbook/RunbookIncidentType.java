package com.aegis.runbook;

public enum RunbookIncidentType {
    CRASH_LOOP_BACK_OFF,
    IMAGE_PULL_BACK_OFF,
    OOM_KILLED,
    PENDING_PODS,
    FAILED_ROLLOUT,
    NODE_PRESSURE,
    GENERAL_TRIAGE
}
