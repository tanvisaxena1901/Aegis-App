package com.aegis.terminal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TerminalRequest(
        @NotBlank @Size(max = 240) String command
) {
}
