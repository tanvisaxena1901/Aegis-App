package com.aegis.runtime;

public enum WorkflowStatus {
    PENDING,
    RUNNING,
    RETRYING,
    FAILED,
    COMPLETED,
    DEAD_LETTERED
}
