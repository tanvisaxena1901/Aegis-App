package com.aegis.kubernetes;

import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.util.Config;
import java.io.IOException;
import org.springframework.stereotype.Component;

@Component
public class KubernetesClientProvider {

    public ApiClient defaultClient() throws IOException {
        return Config.defaultClient();
    }
}
