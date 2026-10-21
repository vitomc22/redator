package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.DatasetMetricsCalculator;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.TwoRaterBenchmarkExporter;
import com.redator.corretor.service.TwoRaterBenchmarkGate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("unchecked")
class TwoRaterBenchmarkGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void averagesMetricsAcrossRatersRatherThanRoundingLabelsOrPoolingRepeatedAnnotations() throws Exception {
        var benchmark = benchmark();
        var baseline = arm(benchmark, "baseline", List.of(40, 120, 120));
        var candidate = arm(benchmark, "rag", List.of(0, 80, 160));
        var result = TwoRaterBenchmarkGate.evaluate(benchmark, baseline, candidate);
        var a = DatasetMetricsCalculator.calculateCompetence(List.of(0, 0, 80), List.of(0, 80, 160));
        var b = DatasetMetricsCalculator.calculateCompetence(List.of(40, 200, 160), List.of(0, 80, 160));
        double pooled = DatasetMetricsCalculator.calculateCompetence(
                List.of(0, 0, 80, 40, 200, 160), List.of(0, 80, 160, 0, 80, 160)).qwk();
        assertThat(result.essayCount()).isEqualTo(3);
        assertThat(result.annotationCount()).isEqualTo(6);
        assertThat(result.byRater()).containsOnlyKeys("grader_a", "grader_b");
        assertThat(result.qualityGate().candidateQwk()).isCloseTo((a.qwk() + b.qwk()) / 2, within(1e-12));
        assertThat(result.qualityGate().candidateQwk()).isNotCloseTo(pooled, within(1e-6));
        assertThat(result.qualityGate().competences().get("C1").candidateMae())
                .isCloseTo((a.mae() + b.mae()) / 2, within(1e-12));
        assertThat(result.humanAgreement().qwk()).isEqualTo(
                DatasetMetricsCalculator.calculateCompetence(List.of(0, 0, 80), List.of(40, 200, 160)).qwk());
    }

    @Test
    void rejectsRegressionAndRetainsBothRaterDiagnostics() throws Exception {
        var benchmark = benchmark();
        var result = TwoRaterBenchmarkGate.evaluate(benchmark,
                arm(benchmark, "baseline", List.of(0, 80, 120)), arm(benchmark, "rag", List.of(200, 0, 0)));
        assertThat(result.passed()).isFalse();
        assertThat(result.qualityGate().failures()).anyMatch(message -> message.contains("MAE"));
        assertThat(result.byRater()).hasSize(2);
    }

    @Test
    void refusesDuplicateRatersWrongTotalsCountsAndFractionalIndividualScores() throws Exception {
        for (String problem : List.of("duplicate", "total", "count", "fraction")) {
            var benchmark = benchmark();
            var sample = ((List<Map<String, Object>>) benchmark.get("samples")).getFirst();
            var ratings = (List<Map<String, Object>>) sample.get("humanEvaluations");
            switch (problem) {
                case "duplicate" -> ratings.getLast().put("graderId", "grader_a");
                case "total" -> ratings.getFirst().put("total", 200);
                case "count" -> benchmark.put("annotationCount", 3);
                case "fraction" -> ratings.getFirst().put("scores", List.of(0.5, 0, 0, 0, 0));
            }
            fingerprint(benchmark);
            assertThatThrownBy(() -> TwoRaterBenchmarkGate.evaluate(benchmark,
                    arm(benchmark, "baseline", List.of(0, 80, 120)), arm(benchmark, "rag", List.of(0, 80, 120))))
                    .as(problem).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void retainsExactSelectionAndRuntimeChecksAndRejectsFakeReleaseEvidence() throws Exception {
        var benchmark = benchmark();
        var baseline = arm(benchmark, "baseline", List.of(0, 80, 120));
        var candidate = arm(benchmark, "rag", List.of(0, 80, 120));
        assertThatThrownBy(() -> TwoRaterBenchmarkGate.evaluateRelease(benchmark, baseline, candidate, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Gemma");
        candidate.put("complete", false);
        assertThatThrownBy(() -> TwoRaterBenchmarkGate.evaluate(benchmark, baseline, candidate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concluído");
        candidate.put("complete", true);
        ((Map<String, Object>) candidate.get("runtime")).put("model", "different");
        assertThatThrownBy(() -> TwoRaterBenchmarkGate.evaluate(benchmark, baseline, candidate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parâmetros");
    }

    @Test
    void cannotPassByDroppingAnUndefinedRaterQwkFromTheMacroAverage() throws Exception {
        var benchmark = benchmark();
        for (var sample : (List<Map<String, Object>>) benchmark.get("samples")) {
            var ratings = (List<Map<String, Object>>) sample.get("humanEvaluations");
            ratings.getFirst().put("scores", List.of(0, 0, 0, 0, 0));
            ratings.getFirst().put("total", 0);
        }
        fingerprint(benchmark);
        var result = TwoRaterBenchmarkGate.evaluate(benchmark,
                arm(benchmark, "baseline", List.of(0, 0, 0)), arm(benchmark, "rag", List.of(0, 0, 0)));
        assertThat(result.byRater().get("grader_a").candidateQwk()).isNaN();
        assertThat(result.byRater().get("grader_b").candidateQwk()).isFinite();
        assertThat(result.qualityGate().candidateQwk()).isNaN();
        assertThat(result.passed()).isFalse();
        assertThat(result.qualityGate().failures()).anyMatch(message -> message.contains("omitido"));
    }

    private static Map<String, Object> benchmark() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        int[] a = {0, 0, 80}, b = {40, 200, 160};
        for (int i = 0; i < 3; i++) {
            var sample = new LinkedHashMap<String, Object>();
            String text = "Texto humano " + i;
            sample.put("essayHash", PublicHumanCorpus.hashText(text)); sample.put("text", text);
            sample.put("humanEvaluations", List.of(rating("grader_a", a[i]), rating("grader_b", b[i])));
            samples.add(sample);
        }
        var benchmark = new LinkedHashMap<String, Object>();
        benchmark.put("schemaVersion", 2); benchmark.put("humanLabelPolicy", TwoRaterBenchmarkExporter.LABEL_POLICY);
        benchmark.put("corpusFingerprint", "c".repeat(64)); benchmark.put("split", "test");
        benchmark.put("sampleCount", 3); benchmark.put("annotationCount", 6);
        benchmark.put("raterIds", List.of("grader_a", "grader_b")); benchmark.put("samples", samples);
        fingerprint(benchmark);
        return benchmark;
    }

    private static Map<String, Object> rating(String grader, int score) {
        var rating = new LinkedHashMap<String, Object>();
        rating.put("graderId", grader); rating.put("scores", List.of(score, score, score, score, score));
        rating.put("total", score * 5); return rating;
    }

    private static void fingerprint(Map<String, Object> benchmark) throws Exception {
        benchmark.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(benchmark.get("samples"))));
    }

    private static Map<String, Object> arm(Map<String, Object> benchmark, String name, List<Integer> scores) {
        var arm = new LinkedHashMap<String, Object>();
        arm.put("arm", name); arm.put("selectionFingerprint", benchmark.get("selectionFingerprint"));
        arm.put("complete", true); arm.put("completedCount", 3);
        arm.put("runtime", new LinkedHashMap<>(Map.of("provider", "fake", "model", "fixture")));
        arm.put("rag", name.equals("rag")
                ? Map.of("enabled", true, "available", true, "fingerprint", "c".repeat(64))
                : Map.of("enabled", false, "available", false));
        var samples = (List<Map<String, Object>>) benchmark.get("samples");
        List<Map<String, Object>> results = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            int score = scores.get(i);
            results.add(Map.of("essayHash", samples.get(i).get("essayHash"), "status", "READY",
                    "scores", List.of(score, score, score, score, score)));
        }
        arm.put("results", results); return arm;
    }
}
