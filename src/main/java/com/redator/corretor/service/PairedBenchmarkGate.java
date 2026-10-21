package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and compares two model arms against the exact same frozen human benchmark. */
public final class PairedBenchmarkGate {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COMPETENCES = List.of("C1", "C2", "C3", "C4", "C5");

    private PairedBenchmarkGate() { }

    /** Release requires held-out test evidence from the active Gemma policy and current contract. */
    public static RagQualityGate.Result evaluateRelease(Map<String, Object> benchmark,
                                                        Map<String, Object> baseline,
                                                        Map<String, Object> candidate,
                                                        String expectedPolicy) {
        validateReleaseEvidence(benchmark, baseline, candidate, expectedPolicy);
        return evaluate(benchmark, baseline, candidate);
    }

    static void validateReleaseEvidence(Map<String, Object> benchmark, Map<String, Object> baseline,
                                        Map<String, Object> candidate, String expectedPolicy) {
        if (!"test".equals(benchmark.get("split")))
            throw new IllegalArgumentException("release exige benchmark reservado no split test");
        if (!List.of(RagService.LEGACY_POLICY, RagService.CALIBRATION_POLICY).contains(expectedPolicy))
            throw new IllegalArgumentException("política de release desconhecida");
        Map<String, Object> candidateRag = objectMap(candidate.get("rag"), "rag candidato");
        String policy = String.valueOf(candidateRag.getOrDefault("policy", RagService.LEGACY_POLICY));
        if (!expectedPolicy.equals(policy))
            throw new IllegalArgumentException("política do artefato RAG difere da política de release");

        var frozen = indexed(objectList(benchmark.get("samples"), "samples"), "benchmark");
        var contract = new LocalEvaluationContract();
        Map<String, Object> identity = null;
        for (var arm : List.of(baseline, candidate)) {
            Map<String, Object> runtime = objectMap(arm.get("runtime"), "runtime");
            if (!"ollama".equals(runtime.get("provider")) || !requiredText(runtime, "model").startsWith("gemma"))
                throw new IllegalArgumentException("release exige ollama com Gemma local nos dois braços");
            boolean enabled = arm == candidate;
            for (var result : objectList(arm.get("results"), "results")) {
                var sample = frozen.get(requiredText(result, "essayHash"));
                if (sample == null) throw new IllegalArgumentException("amostra de release não pertence ao benchmark");
                Map<String, Object> audit = releaseAudit(result, sample, runtime, enabled, policy,
                        requiredText(benchmark, "corpusFingerprint"), frozen.keySet(), contract);
                Map<String, Object> currentIdentity = new LinkedHashMap<>();
                for (String key : List.of("provider", "model", "modelDigest", "runtimeVersion", "promptHash",
                        "rubricHash", "schemaVersionHash", "generationParameters", "thinkingControl", "keepAlive")) {
                    Object value = key.equals("generationParameters")
                            ? objectMap(audit.get(key), key) : requiredText(audit, key);
                    currentIdentity.put(key, value);
                    if (identity != null) equal(identity.get(key), value, key);
                }
                if (identity == null) identity = currentIdentity;
            }
        }
    }

    private static Map<String, Object> releaseAudit(Map<String, Object> result, Map<String, Object> sample,
                                                     Map<String, Object> runtime, boolean enabled, String policy,
                                                     String corpusFingerprint, Set<String> heldOut,
                                                     LocalEvaluationContract contract) {
        List<Map<String, Object>> calls = objectList(result.get("llmCalls"), "chamadas llmCalls");
        if (calls.size() != 1) throw new IllegalArgumentException("release exige exatamente uma chamada por redação");
        Map<String, Object> call = calls.getFirst();
        equal("ollama", call.get("provider"), "provider da chamada");
        equal(runtime.get("model"), call.get("model"), "model da chamada");
        equal("ok", call.get("status"), "status da chamada");
        if (integer(call.get("cacheHit"), "cacheHit") != 0)
            throw new IllegalArgumentException("release exige chamada sem reutilização de cache");
        Map<String, Object> audit;
        try { audit = objectMap(JSON.readValue(requiredText(call, "auditJson"), Map.class), "auditoria"); }
        catch (Exception error) { throw new IllegalArgumentException("auditoria JSON inválida", error); }
        equal("ollama", audit.get("provider"), "provider");
        equal(runtime.get("model"), audit.get("model"), "model");
        hash(audit.get("modelDigest"), "modelDigest");
        equal(contract.promptHash(), audit.get("promptHash"), "promptHash");
        equal(contract.rubricHash(), audit.get("rubricHash"), "rubricHash");
        equal(contract.schemaHash(), audit.get("schemaVersionHash"), "schemaVersionHash");
        String text = requiredText(sample, "text");
        equal(LocalEvaluationContract.hash(text), audit.get("textHash"), "textHash");
        equal(LocalEvaluationContract.hash(contract.json(contract.schemaForText(text))), audit.get("schemaHash"), "schemaHash");
        equal(objectMap(runtime.get("options"), "options"), audit.get("generationParameters"), "generationParameters");
        equal(requiredText(runtime, "keep_alive"), audit.get("keepAlive"), "keepAlive");
        equal("stop", audit.get("doneReason"), "doneReason");
        equal(enabled, audit.get("ragEnabled"), "ragEnabled");
        equal(enabled ? policy : "disabled", audit.getOrDefault("ragPolicy", enabled ? RagService.LEGACY_POLICY : "disabled"), "ragPolicy");
        equal(enabled ? corpusFingerprint : "disabled", audit.get("ragCorpusFingerprint"), "ragCorpusFingerprint");
        if (enabled) {
            List<Map<String, Object>> references = objectList(audit.get("ragExamples"), "ragExamples");
            if (integer(audit.get("ragExampleCount"), "ragExampleCount") != references.size())
                throw new IllegalArgumentException("ragExampleCount não confere com as referências");
            for (var reference : references) {
                String referenceHash = requiredText(reference, "hash");
                hash(referenceHash, "hash da referência");
                if (heldOut.contains(referenceHash))
                    throw new IllegalArgumentException("referência RAG contém amostra reservada do benchmark");
                if (RagService.CALIBRATION_POLICY.equals(policy)) {
                    hash(reference.get("excerptHash"), "excerptHash");
                    if (!List.of("complete", "beginning-and-end").contains(reference.get("excerptCoverage")))
                        throw new IllegalArgumentException("cobertura do excerto não foi auditada");
                }
            }
            if (RagService.CALIBRATION_POLICY.equals(policy)) hash(audit.get("requestMessagesHash"), "requestMessagesHash");
        }
        return audit;
    }

    private static void equal(Object expected, Object actual, String label) {
        if (!java.util.Objects.equals(expected, actual))
            throw new IllegalArgumentException("auditoria diverge em " + label);
    }

    private static void hash(Object value, String label) {
        if (!(value instanceof String text) || !text.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException(label + " não possui hash SHA-256 válido");
    }

    public static RagQualityGate.Result evaluate(Map<String, Object> benchmark,
                                                 Map<String, Object> baseline,
                                                 Map<String, Object> candidate) {
        Evidence evidence = validateEvidence(benchmark, baseline, candidate);
        var gold = evidence.gold(); var before = evidence.before(); var after = evidence.after();
        String declaredFingerprint = evidence.fingerprint();
        Map<String, List<Integer>> goldScores = emptyScores();
        Map<String, List<Integer>> baselineScores = emptyScores();
        Map<String, List<Integer>> candidateScores = emptyScores();
        for (String hash : gold.keySet()) {
            appendScores(goldScores, gold.get(hash).get("humanScores"), "humanScores de " + hash);
            appendScores(baselineScores, before.get(hash).get("scores"), "scores baseline de " + hash);
            appendScores(candidateScores, after.get(hash).get("scores"), "scores rag de " + hash);
        }
        return RagQualityGate.evaluate(declaredFingerprint, goldScores, baselineScores, candidateScores);
    }

    record Evidence(String fingerprint, Map<String, Map<String, Object>> gold,
                    Map<String, Map<String, Object>> before, Map<String, Map<String, Object>> after) { }

    static Evidence validateEvidence(Map<String, Object> benchmark, Map<String, Object> baseline,
                                     Map<String, Object> candidate) {
        List<Map<String, Object>> frozen = objectList(benchmark.get("samples"), "samples");
        String declaredFingerprint = requiredText(benchmark, "selectionFingerprint");
        String actualFingerprint;
        try {
            actualFingerprint = DatasetManifestValidator.sha256(JSON.writeValueAsBytes(frozen));
        } catch (Exception error) {
            throw new IllegalArgumentException("não foi possível recalcular a impressão da seleção", error);
        }
        if (!actualFingerprint.equals(declaredFingerprint))
            throw new IllegalArgumentException("impressão da seleção congelada não confere");
        if (integer(benchmark.get("sampleCount"), "sampleCount") != frozen.size())
            throw new IllegalArgumentException("sampleCount não confere com a seleção congelada");

        Map<String, Map<String, Object>> gold = indexed(frozen, "benchmark");
        for (Map.Entry<String, Map<String, Object>> item : gold.entrySet()) {
            String text = requiredText(item.getValue(), "text");
            if (!item.getKey().equals(PublicHumanCorpus.hashText(text)))
                throw new IllegalArgumentException("essayHash não corresponde ao texto congelado: " + item.getKey());
        }
        Map<String, Map<String, Object>> before = validateArm(baseline, "baseline", declaredFingerprint, gold.keySet());
        Map<String, Map<String, Object>> after = validateArm(candidate, "rag", declaredFingerprint, gold.keySet());
        verifyComparableConfiguration(baseline, candidate);
        if (!requiredText(benchmark, "corpusFingerprint").equals(
                objectMap(candidate.get("rag"), "rag candidato").get("fingerprint")))
            throw new IllegalArgumentException("fingerprint RAG não corresponde ao corpus congelado");

        return new Evidence(declaredFingerprint, gold, before, after);
    }

    private static void verifyComparableConfiguration(Map<String, Object> baseline, Map<String, Object> candidate) {
        Map<String, Object> beforeRuntime = objectMap(baseline.get("runtime"), "runtime baseline");
        Map<String, Object> afterRuntime = objectMap(candidate.get("runtime"), "runtime rag");
        Map<String, Object> normalizedBefore = new LinkedHashMap<>(beforeRuntime);
        Map<String, Object> normalizedAfter = new LinkedHashMap<>(afterRuntime);
        normalizedBefore.remove("rag"); normalizedAfter.remove("rag");
        if (!normalizedBefore.equals(normalizedAfter))
            throw new IllegalArgumentException("provider, modelo e parâmetros dos braços devem ser idênticos");
        Map<String, Object> baselineRag = objectMap(baseline.get("rag"), "rag baseline");
        Map<String, Object> candidateRag = objectMap(candidate.get("rag"), "rag candidato");
        if (!Boolean.FALSE.equals(baselineRag.get("enabled")))
            throw new IllegalArgumentException("braço baseline precisa registrar RAG desabilitado");
        if (!Boolean.TRUE.equals(candidateRag.get("enabled")) || !Boolean.TRUE.equals(candidateRag.get("available")))
            throw new IllegalArgumentException("braço rag precisa registrar corpus disponível e habilitado");
        Object fingerprint = candidateRag.get("fingerprint");
        if (!(fingerprint instanceof String value) || !value.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("braço rag não registrou fingerprint válido do corpus");
    }

    private static Map<String, Map<String, Object>> validateArm(Map<String, Object> document, String arm,
                                                                 String fingerprint, Set<String> expected) {
        if (!arm.equals(requiredText(document, "arm")))
            throw new IllegalArgumentException("braço esperado " + arm);
        if (!fingerprint.equals(requiredText(document, "selectionFingerprint")))
            throw new IllegalArgumentException("fingerprint do braço " + arm + " não corresponde ao benchmark");
        if (!Boolean.TRUE.equals(document.get("complete")))
            throw new IllegalArgumentException("braço " + arm + " não está concluído");
        Map<String, Map<String, Object>> indexed = indexed(objectList(document.get("results"), "results"), arm);
        if (integer(document.get("completedCount"), "completedCount") != indexed.size())
            throw new IllegalArgumentException("completedCount não confere com os resultados do braço " + arm);
        if (!indexed.keySet().equals(expected))
            throw new IllegalArgumentException("braço " + arm + " não possui cobertura exata do benchmark");
        for (Map.Entry<String, Map<String, Object>> item : indexed.entrySet()) {
            if (!"READY".equals(item.getValue().get("status")))
                throw new IllegalArgumentException("resultado " + item.getKey() + " do braço " + arm + " não está READY");
        }
        return indexed;
    }

    private static Map<String, Map<String, Object>> indexed(List<Map<String, Object>> samples, String source) {
        Map<String, Map<String, Object>> indexed = new LinkedHashMap<>();
        for (Map<String, Object> sample : samples) {
            String hash = requiredText(sample, "essayHash");
            if (indexed.putIfAbsent(hash, sample) != null)
                throw new IllegalArgumentException("essayHash duplicado em " + source + ": " + hash);
        }
        return indexed;
    }

    static Map<String, List<Integer>> emptyScores() {
        Map<String, List<Integer>> values = new LinkedHashMap<>();
        COMPETENCES.forEach(code -> values.put(code, new ArrayList<>()));
        return values;
    }

    static void appendScores(Map<String, List<Integer>> target, Object raw, String label) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> scores) {
            for (String code : COMPETENCES) normalized.put(code, scores.get(code));
        } else if (raw instanceof List<?> scores && scores.size() == COMPETENCES.size()) {
            for (int index = 0; index < COMPETENCES.size(); index++) normalized.put(COMPETENCES.get(index), scores.get(index));
        } else throw new IllegalArgumentException(label + " ausente");
        for (String code : COMPETENCES) {
            Object value = normalized.get(code);
            int score = integer(value, label + " em " + code);
            if (score < 0 || score > 200 || score % 40 != 0)
                throw new IllegalArgumentException(label + " possui nota inválida em " + code);
            target.get(code).add(score);
        }
    }

    static int integer(Object value, String label) {
        if (value instanceof Number number) {
            try { return new java.math.BigDecimal(number.toString()).intValueExact(); }
            catch (ArithmeticException | NumberFormatException ignored) { }
        }
        throw new IllegalArgumentException(label + " deve ser uma nota ou contagem inteira");
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> objectList(Object raw, String label) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException(label + " deve ser uma lista não vazia");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " contém item inválido");
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    static String requiredText(Map<String, Object> values, String key) {
        Object raw = values == null ? null : values.get(key);
        String value = raw == null ? "" : String.valueOf(raw).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " é obrigatório");
        return value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> objectMap(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " ausente");
        return (Map<String, Object>) map;
    }
}
