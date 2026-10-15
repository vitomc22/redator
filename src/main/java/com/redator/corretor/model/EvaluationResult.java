package com.redator.corretor.model;

import java.util.List;
import java.util.Map;

public record EvaluationResult(
        Long runId,
        Integer total,
        Map<String, CompetenceResult> competences,
        boolean needsReview,
        List<String> problems,
        String profile,
        String status,
        String provider,
        String model,
        List<String> limitations,
        List<String> reviewReasons,
        boolean cacheHit,
        boolean experimental
) {
}
