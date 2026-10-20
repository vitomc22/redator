package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.RagQualityGate;
import com.redator.corretor.service.DatasetMetricsCalculator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RagQualityGateTest {
    private static final Map<String, List<Integer>> GOLD = scores(80, 120, 160, 200);

    @Test
    void approvesNonRegressionAcrossAllCompetencesAndFreezesFingerprint() {
        var result = RagQualityGate.evaluate("a".repeat(64), GOLD,
                scores(80, 120, 120, 200), scores(80, 120, 160, 200));

        assertThat(result.passed()).isTrue();
        assertThat(result.datasetFingerprint()).isEqualTo("a".repeat(64));
        assertThat(result.competences()).allSatisfy((code, metrics) ->
                assertThat(metrics.candidateMae()).isLessThanOrEqualTo(metrics.baselineMae()));
    }

    @Test
    void rejectsWhenRagRegressesEvenOneCompetence() {
        Map<String, List<Integer>> candidate = scores(80, 120, 160, 200);
        candidate.put("C3", List.of(0, 0, 0, 0));

        assertThatThrownBy(() -> RagQualityGate.requirePassing("b".repeat(64), GOLD, GOLD, candidate))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C3");
    }

    @Test
    void calculatesQwkOnTheSixCompetenceLevelsInsteadOfTotalScoreBands() {
        Map<String, List<Integer>> gold = scores(0, 40, 80, 120, 160, 200);
        Map<String, List<Integer>> baseline = scores(0, 40, 80, 120, 120, 160);
        Map<String, List<Integer>> candidate = scores(0, 40, 80, 120, 160, 200);

        var result = RagQualityGate.evaluate("c".repeat(64), gold, baseline, candidate);
        double expectedBaseline = DatasetMetricsCalculator.calculateCompetence(
                gold.get("C1"), baseline.get("C1")).qwk();

        assertThat(result.competences().get("C1").baselineQwk()).isEqualTo(expectedBaseline);
        assertThat(result.candidateQwk()).isEqualTo(1.0);
    }

    @Test
    void rejectsBenchmarkWithoutEnoughScoreVariationForQwk() {
        Map<String, List<Integer>> constant = scores(160, 160, 160, 160);

        var result = RagQualityGate.evaluate("d".repeat(64), constant, constant, constant);

        assertThat(result.passed()).isFalse();
        assertThat(result.failures()).anyMatch(message -> message.contains("QWK agregado não calculável"));
    }

    private static Map<String, List<Integer>> scores(int... values) {
        var result = new java.util.LinkedHashMap<String, List<Integer>>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5"))
            result.put(code, java.util.Arrays.stream(values).boxed().toList());
        return result;
    }
}
