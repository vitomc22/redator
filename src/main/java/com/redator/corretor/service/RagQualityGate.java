package com.redator.corretor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic release gate comparing RAG against the same frozen no-RAG benchmark. */
public final class RagQualityGate {
    private static final List<String> CODES = List.of("C1", "C2", "C3", "C4", "C5");
    private RagQualityGate() { }

    public static Result evaluate(String datasetFingerprint, Map<String, List<Integer>> gold,
                                  Map<String, List<Integer>> baseline,
                                  Map<String, List<Integer>> candidate) {
        if (datasetFingerprint == null || !datasetFingerprint.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("fingerprint congelado inválido");
        Map<String, CompetenceGate> gates = new LinkedHashMap<>();
        List<Integer> allGold = new ArrayList<>(), allBaseline = new ArrayList<>(), allCandidate = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (String code : CODES) {
            List<Integer> expected = required(gold, code);
            List<Integer> before = required(baseline, code);
            List<Integer> after = required(candidate, code);
            if (expected.size() != before.size() || expected.size() != after.size() || expected.isEmpty())
                throw new IllegalArgumentException(code + " possui quantidades incompatíveis");
            var base = DatasetMetricsCalculator.calculateCompetence(expected, before);
            var rag = DatasetMetricsCalculator.calculateCompetence(expected, after);
            boolean passed = rag.mae() <= base.mae() + 1e-9;
            if (!passed) failures.add(code + " regrediu MAE de " + base.mae() + " para " + rag.mae());
            gates.put(code, new CompetenceGate(base.mae(), rag.mae(), base.qwk(), rag.qwk(), passed));
            allGold.addAll(expected); allBaseline.addAll(before); allCandidate.addAll(after);
        }
        double baselineQwk = DatasetMetricsCalculator.calculateCompetence(allGold, allBaseline).qwk();
        double candidateQwk = DatasetMetricsCalculator.calculateCompetence(allGold, allCandidate).qwk();
        if (!Double.isFinite(baselineQwk) || !Double.isFinite(candidateQwk))
            failures.add("QWK agregado não calculável; o benchmark precisa de variação nos seis níveis");
        else if (candidateQwk + 1e-9 < baselineQwk)
            failures.add("QWK agregado regrediu de " + baselineQwk + " para " + candidateQwk);
        return new Result(datasetFingerprint, failures.isEmpty(), baselineQwk, candidateQwk,
                Map.copyOf(gates), List.copyOf(failures));
    }

    public static Result requirePassing(String datasetFingerprint, Map<String, List<Integer>> gold,
                                        Map<String, List<Integer>> baseline,
                                        Map<String, List<Integer>> candidate) {
        Result result = evaluate(datasetFingerprint, gold, baseline, candidate);
        if (!result.passed()) throw new IllegalStateException("Gate RAG reprovado: " + String.join("; ", result.failures()));
        return result;
    }

    private static List<Integer> required(Map<String, List<Integer>> values, String code) {
        List<Integer> result = values == null ? null : values.get(code);
        if (result == null) throw new IllegalArgumentException(code + " ausente");
        return result;
    }

    public record CompetenceGate(double baselineMae, double candidateMae, double baselineQwk,
                                 double candidateQwk, boolean passed) { }
    public record Result(String datasetFingerprint, boolean passed, double baselineQwk,
                         double candidateQwk, Map<String, CompetenceGate> competences,
                         List<String> failures) { }
}
