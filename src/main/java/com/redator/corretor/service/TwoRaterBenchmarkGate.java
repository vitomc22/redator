package com.redator.corretor.service;

import static com.redator.corretor.service.PairedBenchmarkGate.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Computes each metric against each expert separately, then macro-averages the two metrics. */
public final class TwoRaterBenchmarkGate {
    private static final List<String> CODES = List.of("C1", "C2", "C3", "C4", "C5");
    private TwoRaterBenchmarkGate() { }

    public static Result evaluateRelease(Map<String, Object> benchmark, Map<String, Object> baseline,
                                         Map<String, Object> candidate, String expectedPolicy) {
        validateReleaseEvidence(benchmark, baseline, candidate, expectedPolicy);
        return evaluate(benchmark, baseline, candidate);
    }

    public static Result evaluate(Map<String, Object> benchmark, Map<String, Object> baseline,
                                   Map<String, Object> candidate) {
        if (integer(benchmark.get("schemaVersion"), "schemaVersion") != 2
                || !TwoRaterBenchmarkExporter.LABEL_POLICY.equals(benchmark.get("humanLabelPolicy")))
            throw new IllegalArgumentException("protocolo humano de dois avaliadores desconhecido");
        Evidence evidence = validateEvidence(benchmark, baseline, candidate);
        Object rawRaters = benchmark.get("raterIds");
        if (!(rawRaters instanceof List<?> raters) || raters.size() != 2
                || !(raters.getFirst() instanceof String first) || first.isBlank()
                || !(raters.getLast() instanceof String second) || second.isBlank() || first.equals(second))
            throw new IllegalArgumentException("raterIds exige dois avaliadores distintos");
        int count = evidence.gold().size();
        if (integer(benchmark.get("annotationCount"), "annotationCount") != count * 2)
            throw new IllegalArgumentException("annotationCount difere do painel de dois avaliadores");
        Map<String, Map<String, List<Integer>>> gold = new LinkedHashMap<>();
        gold.put(first, emptyScores()); gold.put(second, emptyScores());
        var before = emptyScores(); var after = emptyScores();
        for (String hash : evidence.gold().keySet()) {
            var ratings = objectList(evidence.gold().get(hash).get("humanEvaluations"), "humanEvaluations");
            if (ratings.size() != 2) throw new IllegalArgumentException("cada redação exige duas avaliações humanas");
            var seen = new java.util.HashSet<String>();
            for (var rating : ratings) {
                String grader = requiredText(rating, "graderId");
                if (!gold.containsKey(grader) || !seen.add(grader))
                    throw new IllegalArgumentException("avaliador duplicado ou fora do painel congelado");
                var scores = emptyScores();
                appendScores(scores, rating.get("scores"), "nota humana individual");
                int total = scores.values().stream().mapToInt(values -> values.getFirst()).sum();
                if (integer(rating.get("total"), "total humano") != total)
                    throw new IllegalArgumentException("total humano difere da soma C1–C5");
                for (String code : CODES) gold.get(grader).get(code).add(scores.get(code).getFirst());
            }
            appendScores(before, evidence.before().get(hash).get("scores"), "baseline");
            appendScores(after, evidence.after().get(hash).get("scores"), "rag");
        }
        Map<String, RagQualityGate.Result> byRater = new LinkedHashMap<>();
        gold.forEach((grader, values) -> byRater.put(grader,
                RagQualityGate.evaluate(evidence.fingerprint(), values, before, after)));
        var a = byRater.get(first); var b = byRater.get(second);
        List<String> failures = new ArrayList<>();
        Map<String, RagQualityGate.CompetenceGate> competences = new LinkedHashMap<>();
        Map<String, DatasetMetricsCalculator.MetricsSummary> agreement = new LinkedHashMap<>();
        List<Integer> allFirst = new ArrayList<>(), allSecond = new ArrayList<>();
        for (String code : CODES) {
            var ca = a.competences().get(code); var cb = b.competences().get(code);
            double baseMae = mean(ca.baselineMae(), cb.baselineMae());
            double ragMae = mean(ca.candidateMae(), cb.candidateMae());
            boolean passed = ragMae <= baseMae + 1e-9;
            if (!passed) failures.add(code + " regrediu MAE médio de " + baseMae + " para " + ragMae);
            competences.put(code, new RagQualityGate.CompetenceGate(baseMae, ragMae,
                    mean(ca.baselineQwk(), cb.baselineQwk()), mean(ca.candidateQwk(), cb.candidateQwk()), passed));
            agreement.put(code, DatasetMetricsCalculator.calculateCompetence(gold.get(first).get(code), gold.get(second).get(code)));
            allFirst.addAll(gold.get(first).get(code)); allSecond.addAll(gold.get(second).get(code));
        }
        double baseQwk = mean(a.baselineQwk(), b.baselineQwk());
        double ragQwk = mean(a.candidateQwk(), b.candidateQwk());
        if (!Double.isFinite(baseQwk) || !Double.isFinite(ragQwk))
            failures.add("QWK médio não calculável; nenhum avaliador pode ser omitido da média");
        else if (ragQwk + 1e-9 < baseQwk)
            failures.add("QWK médio regrediu de " + baseQwk + " para " + ragQwk);
        var quality = new RagQualityGate.Result(evidence.fingerprint(), failures.isEmpty(), baseQwk, ragQwk,
                Map.copyOf(competences), List.copyOf(failures));
        return new Result(TwoRaterBenchmarkExporter.LABEL_POLICY, count, count * 2, Map.copyOf(byRater),
                DatasetMetricsCalculator.calculateCompetence(allFirst, allSecond), Map.copyOf(agreement), quality, quality.passed());
    }

    private static double mean(double a, double b) { return (a + b) / 2; }

    public record Result(String humanLabelPolicy, int essayCount, int annotationCount,
                         Map<String, RagQualityGate.Result> byRater,
                         DatasetMetricsCalculator.MetricsSummary humanAgreement,
                         Map<String, DatasetMetricsCalculator.MetricsSummary> humanAgreementByCompetence,
                         RagQualityGate.Result qualityGate, boolean passed) { }
}
