package com.redator.corretor.service;

import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.model.Tema;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Provider boundary. Fake remains offline-only; Ollama performs one call per essay. */
@Component
public class LlmGateway {
    private final String provider;
    private final OllamaLlmProvider ollama;
    private final LocalEvaluationContract contract;
    private final Map<String, EvaluationOutput> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean activeInference = new AtomicBoolean();
    private final AtomicLong cacheHitCount = new AtomicLong();

    public LlmGateway(@Value("${app.llm.provider:fake}") String provider,
                      OllamaLlmProvider ollama,
                      LocalEvaluationContract contract) {
        this.provider = provider == null ? "fake" : provider.trim().toLowerCase();
        if (!List.of("fake", "ollama").contains(this.provider)) {
            throw new IllegalArgumentException("app.llm.provider deve ser fake ou ollama");
        }
        this.ollama = ollama;
        this.contract = contract;
    }

    public EvaluationOutput evaluate(String essayText, Tema theme, long revision,
                                     EvaluationProfile profile,
                                     Map<String, CompetenceResult> deterministicBaseline) {
        if ("fake".equals(provider)) {
            String key = LocalEvaluationContract.hash("fake|" + contract.promptHash() + "|"
                    + contract.rubricHash() + "|" + contract.schemaHash() + "|"
                    + profile.name() + "|" + theme + "|" + essayText);
            EvaluationOutput cached = cache.get(key);
            if (cached != null) {
                cacheHitCount.incrementAndGet();
                return cached.withCacheHit(true);
            }
            Map<String, Object> audit = new LinkedHashMap<>();
            audit.put("provider", "fake");
            audit.put("model", "deterministic-baseline");
            audit.put("cacheKey", key);
            audit.put("promptHash", contract.promptHash());
            audit.put("rubricHash", contract.rubricHash());
            audit.put("schemaHash", contract.schemaHash());
            audit.put("costUsd", 0.0);
            audit.put("wallMs", 0.0);
            EvaluationOutput output = new EvaluationOutput(deterministicBaseline,
                    deterministicBaseline.values().stream().anyMatch(CompetenceResult::needsReview),
                    List.of("FAKE_PROVIDER"),
                    List.of("Provider fake: resultado determinístico para testes; não é correção por LLM."),
                    audit, false);
            cache.put(key, output);
            return output;
        }

        OllamaLlmProvider.Prepared prepared = ollama.prepare(essayText, theme, revision);
        EvaluationOutput cached = cache.get(prepared.cacheKey());
        if (cached != null) {
            cacheHitCount.incrementAndGet();
            return cached.withCacheHit(true);
        }
        if (!activeInference.compareAndSet(false, true)) {
            throw new EvaluationFailure(HttpStatus.CONFLICT, "EVALUATION_BUSY",
                    "Já existe uma inferência local em andamento; tente novamente após sua conclusão.");
        }
        try {
            OllamaLlmProvider.Generated generated = ollama.generate(prepared, essayText);
            LocalEvaluationContract.Validated validated = generated.result();
            EvaluationOutput output = new EvaluationOutput(validated.competences(), validated.needsReview(),
                    validated.reviewReasons(), validated.limitations(), generated.audit(), false);
            cache.put(prepared.cacheKey(), output);
            return output;
        } finally {
            activeInference.set(false);
        }
    }

    public Map<String, Object> runtime() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("provider", provider);
        result.put("experimental", true);
        result.put("synchronous", true);
        result.put("profilesAliases", true);
        if ("ollama".equals(provider)) result.putAll(ollama.configuration());
        else {
            result.put("model", "deterministic-baseline");
            result.put("timeoutSeconds", 0);
        }
        return result;
    }

    public long cacheHitCount() { return cacheHitCount.get(); }
    public int cacheSize() { return cache.size(); }

    public record EvaluationOutput(Map<String, CompetenceResult> competences,
                                   boolean needsReview,
                                   List<String> reviewReasons,
                                   List<String> limitations,
                                   Map<String, Object> audit,
                                   boolean cacheHit) {
        EvaluationOutput withCacheHit(boolean value) {
            return new EvaluationOutput(competences, needsReview, reviewReasons, limitations, audit, value);
        }
    }
}
