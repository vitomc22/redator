package com.redator.corretor.service;

import com.redator.corretor.model.EvaluationProfile;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class LlmGateway {

    private final BudgetGuard budgetGuard;
    private final ResponseSchemaValidator schemaValidator;

    public LlmGateway(BudgetGuard budgetGuard, ResponseSchemaValidator schemaValidator) {
        this.budgetGuard = budgetGuard;
        this.schemaValidator = schemaValidator;
    }

    public LlmCallResult generate(String competence, String essayText, EvaluationProfile profile) {
        double cost = budgetGuard.reserve(profile);

        String provider = switch (profile) {
            case CHEAP -> "fake";
            case NORMAL -> "fake";
            case PREMIUM -> "fake";
        };

        String model = switch (profile) {
            case CHEAP -> "mock-cheap";
            case NORMAL -> "mock-normal";
            case PREMIUM -> "mock-premium";
        };

        int latencyMs = switch (profile) {
            case CHEAP -> 250;
            case NORMAL -> 650;
            case PREMIUM -> 1200;
        };

        String promptHash = Integer.toHexString((competence + essayText + profile.name()).hashCode());
        String schemaHash = Integer.toHexString(("essay-eval-v1" + competence).hashCode());

        String payload = "{"
                + "\"competencia\":\"" + competence + "\","
                + "\"score\":" + 160 + ","
                + "\"evidence\":[\"" + competence + " validada por modelo simulado\"],"
                + "\"problems\":[],"
                + "\"needsReview\":false"
                + "}";

        schemaValidator.validate(payload);

        return new LlmCallResult(
                provider,
                model,
                promptHash,
                schemaHash,
                cost,
                latencyMs,
                "ok",
                payload
        );
    }
}
