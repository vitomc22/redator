package com.redator.corretor.service;

import com.redator.corretor.model.EvaluationProfile;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

@Component
public class LlmGateway {

    private final BudgetGuard budgetGuard;
    private final ResponseSchemaValidator schemaValidator;
    private final Map<String, LlmCallResult> responseCache = new ConcurrentHashMap<>();
    private final AtomicLong cacheHitCount = new AtomicLong();

    public LlmGateway(BudgetGuard budgetGuard, ResponseSchemaValidator schemaValidator) {
        this.budgetGuard = budgetGuard;
        this.schemaValidator = schemaValidator;
    }

    public LlmCallResult generate(String competence, String essayText, EvaluationProfile profile) {
        String promptHash = Integer.toHexString((competence + essayText + profile.name()).hashCode());
        String schemaHash = Integer.toHexString(("essay-eval-v1" + competence).hashCode());
        String cacheKey = promptHash + ":" + schemaHash;

        LlmCallResult cached = responseCache.get(cacheKey);
        if (cached != null) {
            cacheHitCount.incrementAndGet();
            return new LlmCallResult(
                    cached.provider(),
                    cached.model(),
                    cached.promptHash(),
                    cached.schemaHash(),
                    0.0,
                    0,
                    "cached",
                    cached.summary()
            );
        }

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

        String payload = "{"
                + "\"competencia\":\"" + competence + "\","
                + "\"score\":" + 160 + ","
                + "\"evidence\":[\"" + competence + " validada por modelo simulado\"],"
                + "\"problems\":[],"
                + "\"needsReview\":false"
                + "}";

        schemaValidator.validate(payload);

        LlmCallResult result = new LlmCallResult(
                provider,
                model,
                promptHash,
                schemaHash,
                cost,
                latencyMs,
                "ok",
                payload
        );
        responseCache.put(cacheKey, result);
        return result;
    }

    public long cacheHitCount() {
        return cacheHitCount.get();
    }

    public int cacheSize() {
        return responseCache.size();
    }
}
