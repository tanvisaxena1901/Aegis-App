package com.aegis.platform;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/platform")
@RequiredArgsConstructor
public class PlatformController {

    private final PlatformSafetyService platformSafetyService;

    @GetMapping("/safety")
    public PlatformSafetyStatus safety() {
        return platformSafetyService.status();
    }
}
