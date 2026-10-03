package com.redator.corretor.service;

public record LlmCallResult(
        String provider,
        String model,
        String promptHash,
        String schemaHash,
        double costUsd,
        int latencyMs,
        String status,
        String summary
) {
}
