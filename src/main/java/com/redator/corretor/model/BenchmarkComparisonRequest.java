package com.redator.corretor.model;

import jakarta.validation.constraints.NotNull;
import java.util.List;

public record BenchmarkComparisonRequest(
        @NotNull Long temaId,
        @NotNull List<String> texts,
        @NotNull List<String> profiles
) {
}
