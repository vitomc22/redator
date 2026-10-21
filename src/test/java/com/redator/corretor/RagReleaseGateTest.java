package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.cli.RagQualityGateCli;
import com.redator.corretor.service.PairedBenchmarkGate;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.TwoRaterBenchmarkExporter;
import com.redator.corretor.service.TwoRaterBenchmarkGate;
import com.redator.corretor.service.StableJsonEvaluationContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("unchecked")
class RagReleaseGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void acceptsConsistentAuditWithSyntheticPerfectScores() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var rag = read("eval/results/rag-v1.json");
        // Synthetic vectors test the passing path; they are not measured model quality.
        for (var arm : List.of(baseline, rag)) {
            for (var result : results(arm)) {
                var sample = ((List<Map<String, Object>>) benchmark.get("samples")).stream()
                        .filter(s -> s.get("essayHash").equals(result.get("essayHash"))).findFirst().orElseThrow();
                var vector = (List<Integer>) sample.get("humanScores");
                var scores = new LinkedHashMap<String, Integer>();
                for (int i = 0; i < 5; i++) scores.put("C" + (i + 1), vector.get(i));
                result.put("scores", scores);
            }
        }
        assertThat(PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1").passed()).isTrue();
    }

    @Test
    void rejectsValidationAndFakeProviderForRelease() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var rag = read("eval/results/rag-v1.json");
        benchmark.put("split", "validation");
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("test");
        benchmark.put("split", "test");
        ((Map<String, Object>) baseline.get("runtime")).put("provider", "fake");
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ollama");
    }

    @Test
    void rejectsChangedModelDigestDespiteSameTag() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var rag = read("eval/results/rag-v1.json");
        alterAudit(rag, "modelDigest", "0".repeat(64));
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelDigest");
    }

    @Test
    void rejectsMissingCallAndChangedContractOrText() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var missing = read("eval/results/rag-v1.json");
        results(missing).getFirst().put("llmCalls", List.of());
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, missing, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chamada");
        for (String key : List.of("promptHash", "textHash")) {
            var rag = read("eval/results/rag-v1.json");
            alterAudit(rag, key, "0".repeat(64));
            assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining(key);
        }
    }

    @Test
    void rejectsAnotherPolicyAndHeldOutReference() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var rag = read("eval/results/rag-v1.json");
        ((Map<String, Object>) rag.get("rag")).put("policy", "calibration-v2");
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("política");
        ((Map<String, Object>) rag.get("rag")).remove("policy");
        var call = ((List<Map<String, Object>>) results(rag).getFirst().get("llmCalls")).getFirst();
        var audit = JSON.readValue(call.get("auditJson").toString(), Map.class);
        ((List<Map<String, Object>>) audit.get("ragExamples")).getFirst()
                .put("hash", results(rag).getFirst().get("essayHash"));
        call.put("auditJson", JSON.writeValueAsString(audit));
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reservada");
    }

    @Test
    void experimentalStableJsonIdentityCannotQualifyTheLegacyReleaseContract() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json"); var rag = read("eval/results/rag-v1.json");
        for (var arm : List.of(baseline, rag)) alterAudit(arm, "promptHash", new StableJsonEvaluationContract().promptHash());
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("promptHash");
    }

    @Test
    void releaseCliPreservesMeasuredRejectionAndFailsPackaging() throws Exception {
        Path output = temporary.resolve("release-gate.json");
        assertThatThrownBy(() -> RagQualityGateCli.main(new String[] {"--release", "lexical-v1",
                "eval/frozen/public-test-v1.json", "eval/results/baseline-v1.json", "eval/results/rag-v1.json",
                output.toString()})).isInstanceOf(IllegalStateException.class).hasMessageContaining("Gate RAG reprovado");
        assertThat(read(output.toString())).containsEntry("passed", false);
    }

    @Test
    void twoRaterReleaseReusesStrictRuntimeAuditAndPreservesTheMeasuredRejection() throws Exception {
        var benchmark = read("eval/frozen/public-test-v1.json");
        var baseline = read("eval/results/baseline-v1.json");
        var rag = read("eval/results/rag-v1.json");
        var original = PairedBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1");
        var samples = (List<Map<String, Object>>) benchmark.get("samples");
        // A synthetic identical panel isolates protocol dispatch/audit; it is not new human evidence.
        for (var sample : samples) {
            Object vector = sample.remove("humanScores"), total = sample.remove("humanTotal");
            sample.remove("graderAggregation");
            sample.put("humanEvaluations", List.of(
                    Map.of("graderId", "synthetic_a", "scores", vector, "total", total),
                    Map.of("graderId", "synthetic_b", "scores", vector, "total", total)));
        }
        benchmark.put("schemaVersion", 2); benchmark.put("humanLabelPolicy", TwoRaterBenchmarkExporter.LABEL_POLICY);
        benchmark.put("annotationCount", samples.size() * 2); benchmark.put("raterIds", List.of("synthetic_a", "synthetic_b"));
        String fingerprint = DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples));
        benchmark.put("selectionFingerprint", fingerprint);
        baseline.put("selectionFingerprint", fingerprint); rag.put("selectionFingerprint", fingerprint);
        var result = TwoRaterBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1");
        assertThat(result.passed()).isFalse();
        assertThat(result.qualityGate().competences()).isEqualTo(original.competences());
        assertThat(result.qualityGate().baselineQwk()).isEqualTo(original.baselineQwk());
        assertThat(result.qualityGate().candidateQwk()).isEqualTo(original.candidateQwk());
        alterAudit(rag, "modelDigest", "0".repeat(64));
        assertThatThrownBy(() -> TwoRaterBenchmarkGate.evaluateRelease(benchmark, baseline, rag, "lexical-v1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelDigest");
    }

    private static Map<String, Object> read(String path) throws Exception {
        return JSON.readValue(Files.readString(Path.of(path)), Map.class);
    }

    private static List<Map<String, Object>> results(Map<String, Object> arm) {
        return (List<Map<String, Object>>) arm.get("results");
    }

    private static void alterAudit(Map<String, Object> arm, String key, Object value) throws Exception {
        var call = ((List<Map<String, Object>>) results(arm).getFirst().get("llmCalls")).getFirst();
        var audit = JSON.readValue(call.get("auditJson").toString(), Map.class);
        audit.put(key, value);
        call.put("auditJson", JSON.writeValueAsString(audit));
    }
}
