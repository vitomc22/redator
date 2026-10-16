package com.redator.corretor.model;

import java.util.Map;

public record HumanEvaluationRequest(
        Map<String, Integer> scores,
        Map<String, String> comments,
        String mainGuidance,
        String revisionReason
) {
}
