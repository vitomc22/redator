package com.redator.corretor.service;

import com.redator.corretor.model.EvaluationProfile;
import java.util.Locale;
import org.springframework.stereotype.Component;

@Component
public class LlmGateway {

    public LlmCallResult generate(String competence, String essayText, EvaluationProfile profile) {
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

        double cost = switch (profile) {
            case CHEAP -> 0.002;
            case NORMAL -> 0.008;
            case PREMIUM -> 0.015;
        };

        int latencyMs = switch (profile) {
            case CHEAP -> 250;
            case NORMAL -> 650;
            case PREMIUM -> 1200;
        };

        String promptHash = Integer.toHexString((competence + essayText + profile.name()).hashCode());
        String schemaHash = Integer.toHexString(("essay-eval-v1" + competence).hashCode());
        return new LlmCallResult(
                provider,
                model,
                promptHash,
                schemaHash,
                cost,
                latencyMs,
                "ok",
                "Competencia " + competence + " validada com perfil " + profile.name().toLowerCase(Locale.ROOT)
        );
    }
}
