package com.aegis.remediation;

import com.aegis.incident.IncidentSeverity;
import org.springframework.stereotype.Component;

@Component
public class RemediationPolicy {

    public boolean requiresHumanApproval(IncidentSeverity severity) {
        return severity == IncidentSeverity.HIGH || severity == IncidentSeverity.CRITICAL;
    }
}
