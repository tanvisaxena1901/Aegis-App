package com.aegis.observability;

public final class ObservabilityTags {

    public static final String NAMESPACE = "k8s.namespace";
    public static final String RESOURCE_KIND = "k8s.resource.kind";
    public static final String RESOURCE_NAME = "k8s.resource.name";
    public static final String INCIDENT_SEVERITY = "incident.severity";

    private ObservabilityTags() {
    }
}
