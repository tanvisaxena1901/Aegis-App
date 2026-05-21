package com.aegis.runbook;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runbooks")
@RequiredArgsConstructor
public class RunbookController {

    private final RunbookService runbookService;

    @GetMapping("/catalog")
    public List<RunbookResponse> catalog() {
        return runbookService.catalog();
    }

    @PostMapping("/evaluate")
    public RunbookResponse evaluate(@RequestBody RunbookRequest request) {
        return runbookService.evaluate(request);
    }
}
