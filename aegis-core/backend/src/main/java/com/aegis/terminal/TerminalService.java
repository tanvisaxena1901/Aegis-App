package com.aegis.terminal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class TerminalService {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);
    private static final Set<String> READ_ONLY_VERBS = Set.of("get", "describe", "logs", "top", "config", "version");
    private static final Set<String> BLOCKED_VERBS = Set.of(
            "apply", "delete", "exec", "port-forward", "cp", "scale", "patch", "replace",
            "create", "edit", "annotate", "label", "cordon", "drain", "taint", "rollout"
    );

    public Mono<TerminalResponse> run(TerminalRequest request) {
        return Mono.fromCallable(() -> runBlocking(request.command()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private TerminalResponse runBlocking(String command) throws IOException, InterruptedException {
        long started = System.nanoTime();
        List<String> tokens = tokenize(command);
        validate(tokens, command);

        Process process = new ProcessBuilder(tokens)
                .redirectErrorStream(true)
                .start();
        boolean finished = process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroyForcibly();
            return response(command, "Command timed out after " + TIMEOUT.toSeconds() + "s.", 124, started);
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return response(command, output.isBlank() ? "(no output)" : output.stripTrailing(), process.exitValue(), started);
    }

    private TerminalResponse response(String command, String output, int exitCode, long started) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        return new TerminalResponse(command, output, exitCode, durationMs, Instant.now());
    }

    private List<String> tokenize(String command) {
        String trimmed = command == null ? "" : command.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Enter a command.");
        }
        if (trimmed.matches(".*[;&|><`$()\\n\\r].*")) {
            throw new IllegalArgumentException("Shell operators are not supported.");
        }
        return new ArrayList<>(List.of(trimmed.split("\\s+")));
    }

    private void validate(List<String> tokens, String command) {
        if (!"kubectl".equals(tokens.get(0))) {
            throw new IllegalArgumentException("Only read-only kubectl commands are supported.");
        }
        if (tokens.size() < 2) {
            throw new IllegalArgumentException("Provide a kubectl verb, for example `kubectl get pods -n aegis`.");
        }
        String verb = tokens.get(1).toLowerCase(Locale.ROOT);
        if (BLOCKED_VERBS.contains(verb) || !READ_ONLY_VERBS.contains(verb)) {
            throw new IllegalArgumentException("Only kubectl get, describe, logs, top, config, and version are allowed.");
        }
        if ("config".equals(verb) && tokens.size() > 2
                && !Set.of("current-context", "get-contexts", "view").contains(tokens.get(2))) {
            throw new IllegalArgumentException("Only read-only kubectl config commands are allowed.");
        }
        if ("logs".equals(verb) && command.contains(" -f")) {
            throw new IllegalArgumentException("Streaming logs are not supported. Use a non-following logs command.");
        }
    }
}
