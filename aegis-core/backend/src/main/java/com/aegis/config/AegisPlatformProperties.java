package com.aegis.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "aegis.platform")
public record AegisPlatformProperties(
        AegisSafetyMode safetyMode,
        long watcherRefreshMs
) {
    public AegisPlatformProperties {
        safetyMode = safetyMode == null ? AegisSafetyMode.APPROVAL_REQUIRED : safetyMode;
        watcherRefreshMs = watcherRefreshMs <= 0 ? 30000 : watcherRefreshMs;
    }
}
