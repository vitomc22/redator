package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Validates and compares two model arms against the exact same frozen human benchmark. */
public final class PairedBenchmarkGate {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> COMPETENCES = List.of("C1", "C2", "C3", "C4", "C5");

    private PairedBenchmarkGate() { }

    public static RagQualityGate.Result evaluate(Map<String, Object> benchmark,
                                                 Map<String, Object> baseline,
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
        Number sampleCount = benchmark.get("sampleCount") instanceof Number number ? number : null;
        if (sampleCount == null || sampleCount.intValue() != frozen.size())
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
        Map<String, Map<String, Object>> indexed = indexed(objectList(document.get("results"), "results"), arm);
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

    private static Map<String, List<Integer>> emptyScores() {
        Map<String, List<Integer>> values = new LinkedHashMap<>();
        COMPETENCES.forEach(code -> values.put(code, new ArrayList<>()));
        return values;
    }

    private static void appendScores(Map<String, List<Integer>> target, Object raw, String label) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> scores) {
            for (String code : COMPETENCES) normalized.put(code, scores.get(code));
        } else if (raw instanceof List<?> scores && scores.size() == COMPETENCES.size()) {
            for (int index = 0; index < COMPETENCES.size(); index++) normalized.put(COMPETENCES.get(index), scores.get(index));
        } else throw new IllegalArgumentException(label + " ausente");
        for (String code : COMPETENCES) {
            Object value = normalized.get(code);
            if (!(value instanceof Number number)) throw new IllegalArgumentException(label + " sem " + code);
            int score = number.intValue();
            if (score < 0 || score > 200 || score % 40 != 0)
                throw new IllegalArgumentException(label + " possui nota inválida em " + code);
            target.get(code).add(score);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectList(Object raw, String label) {
        if (!(raw instanceof List<?> list) || list.isEmpty())
            throw new IllegalArgumentException(label + " deve ser uma lista não vazia");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " contém item inválido");
            result.add((Map<String, Object>) map);
        }
        return result;
    }

    private static String requiredText(Map<String, Object> values, String key) {
        Object raw = values == null ? null : values.get(key);
        String value = raw == null ? "" : String.valueOf(raw).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " é obrigatório");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object raw, String label) {
        if (!(raw instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " ausente");
        return (Map<String, Object>) map;
    }
}
