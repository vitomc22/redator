package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PairedBenchmarkGate;
import com.redator.corretor.service.PublicHumanCorpus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PairedBenchmarkGateTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void comparesByEssayHashEvenWhenArmResultsAreReordered() throws Exception {
        Map<String, Object> benchmark = benchmark();
        Map<String, Object> baseline = arm("baseline", List.of(result("b", 120), result("a", 80)));
        Map<String, Object> candidate = arm("rag", List.of(result("a", 80), result("b", 160)));

        var result = PairedBenchmarkGate.evaluate(benchmark, baseline, candidate);

        assertThat(result.passed()).isTrue();
        assertThat(result.datasetFingerprint()).isEqualTo(benchmark.get("selectionFingerprint"));
    }

    @Test
    void rejectsDuplicateMissingAndUnknownSamples() throws Exception {
        Map<String, Object> benchmark = benchmark();

        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark,
                arm("baseline", List.of(result("a", 80), result("a", 80))),
                arm("rag", List.of(result("a", 80), result("b", 160)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("duplicado");

        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark,
                arm("baseline", List.of(result("a", 80))),
                arm("rag", List.of(result("a", 80), result("b", 160)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cobertura exata");

        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark,
                arm("baseline", List.of(result("a", 80), result("x", 160))),
                arm("rag", List.of(result("a", 80), result("b", 160)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cobertura exata");
    }

    @Test
    void rejectsTamperedBenchmarkAndMismatchedArmFingerprint() throws Exception {
        Map<String, Object> tampered = benchmark();
        ((Map<String, Object>) ((List<?>) tampered.get("samples")).getFirst()).put("text", "adulterado");

        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(tampered,
                arm("baseline", List.of(result("a", 80), result("b", 120))),
                arm("rag", List.of(result("a", 80), result("b", 160)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("impressão");

        Map<String, Object> benchmark = benchmark();
        Map<String, Object> wrong = arm("baseline", List.of(result("a", 80), result("b", 120)));
        wrong.put("selectionFingerprint", "f".repeat(64));
        Map<String, Object> finalBenchmark = benchmark;
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(finalBenchmark, wrong,
                arm("rag", List.of(result("a", 80), result("b", 160)))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("fingerprint");
    }

    @Test
    void rejectsDifferentModelConfigurationBetweenArms() throws Exception {
        Map<String, Object> benchmark = benchmark();
        Map<String, Object> baseline = arm("baseline", List.of(result("a", 80), result("b", 120)));
        Map<String, Object> candidate = arm("rag", List.of(result("a", 80), result("b", 160)));
        ((Map<String, Object>) candidate.get("runtime")).put("model", "outro-modelo");

        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark, baseline, candidate))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("modelo e parâmetros");
    }

    @Test
    void acceptsTheVersionedRealFrozenArtifactAndItsVectorScores() throws Exception {
        Map<String, Object> benchmark = JSON.readValue(
                Files.readString(Path.of("eval/frozen/public-test-v1.json")), Map.class);
        List<Map<String, Object>> results = new ArrayList<>();
        for (Object raw : (List<?>) benchmark.get("samples")) {
            Map<?, ?> sample = (Map<?, ?>) raw;
            List<?> values = (List<?>) sample.get("humanScores");
            Map<String, Integer> scores = new LinkedHashMap<>();
            for (int index = 0; index < 5; index++) scores.put("C" + (index + 1), ((Number) values.get(index)).intValue());
            results.add(Map.of("essayHash", sample.get("essayHash"), "status", "READY", "scores", scores));
        }
        Map<String, Object> baseline = armFor(benchmark, "baseline", results);
        Map<String, Object> rag = armFor(benchmark, "rag", List.copyOf(results));

        assertThat(PairedBenchmarkGate.evaluate(benchmark, baseline, rag).passed()).isTrue();
    }

    @Test
    void rejectsIncompleteArmEvenWithEverySamplePresent() throws Exception {
        var benchmark = benchmark();
        var baseline = arm("baseline", List.of(result("a", 80), result("b", 120)));
        var rag = arm("rag", List.of(result("a", 80), result("b", 160)));
        rag.put("complete", false);
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark, baseline, rag))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("concluído");
    }

    @Test
    void rejectsFractionalScoreInsteadOfTruncatingItToAnOfficialLevel() throws Exception {
        var benchmark = benchmark();
        var baseline = arm("baseline", List.of(result("a", 80), result("b", 120)));
        Map<String, Object> fractional = new LinkedHashMap<>(result("b", 160));
        fractional.put("scores", Map.of("C1", 160.5, "C2", 160, "C3", 160, "C4", 160, "C5", 160));
        var rag = arm("rag", List.of(result("a", 80), fractional));
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark, baseline, rag))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inteira");
    }

    @Test
    void rejectsCandidateFromAnotherCorpusEvenIfSelectionMatches() throws Exception {
        var benchmark = benchmark();
        var baseline = arm("baseline", List.of(result("a", 80), result("b", 120)));
        var rag = arm("rag", List.of(result("a", 80), result("b", 160)));
        rag.put("rag", Map.of("enabled", true, "available", true, "fingerprint", "e".repeat(64)));
        assertThatThrownBy(() -> PairedBenchmarkGate.evaluate(benchmark, baseline, rag))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corpus congelado");
    }

    private static Map<String, Object> benchmark() throws Exception {
        List<Map<String, Object>> samples = new ArrayList<>();
        samples.add(sample("a", 80));
        samples.add(sample("b", 160));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1);
        document.put("corpusFingerprint", "c".repeat(64));
        document.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)));
        document.put("split", "test");
        document.put("sampleCount", samples.size());
        document.put("samples", samples);
        return document;
    }

    private static Map<String, Object> sample(String hash, int score) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("essayHash", PublicHumanCorpus.hashText("Texto " + hash));
        sample.put("source", "test"); sample.put("sourceId", hash); sample.put("license", "MIT");
        sample.put("theme", "Tema"); sample.put("text", "Texto " + hash);
        sample.put("humanScores", scores(score)); sample.put("humanTotal", score * 5);
        sample.put("graderAggregation", "human");
        return sample;
    }

    private static Map<String, Object> arm(String arm, List<Map<String, Object>> results) throws Exception {
        Map<String, Object> benchmark = benchmark();
        return armFor(benchmark, arm, results);
    }

    private static Map<String, Object> armFor(Map<String, Object> benchmark, String arm,
                                               List<Map<String, Object>> results) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1); document.put("arm", arm);
        document.put("selectionFingerprint", benchmark.get("selectionFingerprint"));
        document.put("complete", true); document.put("completedCount", results.size());
        document.put("runtime", new LinkedHashMap<>(Map.of("provider", "fake", "model", "deterministic-baseline")));
        document.put("rag", arm.equals("rag")
                ? Map.of("enabled", true, "available", true, "fingerprint", benchmark.get("corpusFingerprint"))
                : Map.of("enabled", false, "available", false));
        document.put("results", results);
        return document;
    }

    private static Map<String, Object> result(String hash, int score) {
        return Map.of("essayHash", PublicHumanCorpus.hashText("Texto " + hash), "status", "READY", "scores", scores(score));
    }

    private static Map<String, Integer> scores(int value) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5")) scores.put(code, value);
        return scores;
    }
}
