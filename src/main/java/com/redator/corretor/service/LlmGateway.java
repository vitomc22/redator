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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Provider boundary. Fake remains offline-only; Ollama performs one call per essay. */
@Component
public class LlmGateway {
    private final String provider;
    private final OllamaLlmProvider ollama;
    private final LocalEvaluationContract contract;
    private final EvaluationCache cache;
    private final Map<String, EvaluationOutput> fallbackCache = new ConcurrentHashMap<>();
    private final AtomicBoolean activeInference = new AtomicBoolean();
    private final AtomicLong cacheHitCount = new AtomicLong();

    @Autowired
    public LlmGateway(@Value("${app.llm.provider:ollama}") String provider,
                      OllamaLlmProvider ollama,
                      LocalEvaluationContract contract,
                      EvaluationCache cache) {
        this.provider = provider == null ? "ollama" : provider.trim().toLowerCase();
        if (!List.of("fake", "ollama").contains(this.provider)) {
            throw new IllegalArgumentException("app.llm.provider deve ser fake ou ollama");
        }
        this.ollama = ollama;
        this.contract = contract;
        this.cache = cache;
    }

    public LlmGateway(String provider, OllamaLlmProvider ollama, LocalEvaluationContract contract) {
        this(provider, ollama, contract, null);
    }

    public EvaluationOutput evaluate(String essayText, Tema theme, long revision,
                                     EvaluationProfile profile,
                                     Map<String, CompetenceResult> deterministicBaseline) {
        if ("fake".equals(provider)) {
            String key = LocalEvaluationContract.hash("fake|" + contract.promptHash() + "|"
                    + contract.rubricHash() + "|" + contract.schemaHash() + "|"
                    + profile.name() + "|" + theme + "|" + essayText);
            EvaluationOutput cached = getCached(key);
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
            putCached(key, output);
            return output;
        }

        OllamaLlmProvider.Prepared prepared = ollama.prepare(essayText, theme, revision);
        EvaluationOutput cached = getCached(prepared.cacheKey());
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
            putCached(prepared.cacheKey(), output);
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
    public int cacheSize() { return cache == null ? fallbackCache.size() : cache.size(); }

    private EvaluationOutput getCached(String key) {
        if (cache == null) return fallbackCache.get(key);
        return cache.get(key).orElse(null);
    }

    private void putCached(String key, EvaluationOutput output) {
        if (cache != null) cache.put(key, output);
        else fallbackCache.put(key, output);
    }

    public record EvaluationOutput(Map<String, CompetenceResult> competences,
                                   boolean needsReview,
                                   List<String> reviewReasons,
                                   List<String> limitations,
                                   Map<String, Object> audit,
                                   boolean cacheHit) {
        public EvaluationOutput withCacheHit(boolean value) {
            return new EvaluationOutput(competences, needsReview, reviewReasons, limitations, audit, value);
        }
    }
}
