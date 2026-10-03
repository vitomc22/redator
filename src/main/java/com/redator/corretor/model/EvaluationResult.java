package com.redator.corretor.model;

import java.util.List;
import java.util.Map;

public record EvaluationResult(
        Long runId,
        int total,
        Map<String, CompetenceResult> competences,
        boolean needsReview,
        List<String> problems,
        String profile
) {
}
