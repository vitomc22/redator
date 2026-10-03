package com.redator.corretor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BenchmarkRequest(
        @NotNull Long temaId,
        @NotNull List<String> texts,
        String profile
) {
}
