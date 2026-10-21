package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.cli.RagQualityGateCli;
import com.redator.corretor.cli.TwoRaterBenchmarkCli;
import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("unchecked")
class TwoRaterBenchmarkCliTest {
    @TempDir Path temporary;
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void freezesDeterministicallyRefusesReplacementAndAuditsPreviousSelectionExclusion() throws Exception {
        Path database = corpus();
        Path old = temporary.resolve("previous.json"), fresh = temporary.resolve("fresh.json");
        export(database, old, "--limit", "1");
        String content = Files.readString(old);
        export(database, old, "--limit", "1");
        assertThat(Files.readString(old)).isEqualTo(content);
        assertThatThrownBy(() -> export(database, old)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("congelado");
        export(database, fresh, "--exclude-benchmark", old.toString());
        var prior = JSON.readValue(content, Map.class);
        var next = JSON.readValue(Files.readString(fresh), Map.class);
        assertThat(next).containsEntry("sampleCount", 1);
        var oldSample = ((List<Map<String, Object>>) prior.get("samples")).getFirst();
        var newSample = ((List<Map<String, Object>>) next.get("samples")).getFirst();
        assertThat(oldSample.get("essayHash")).isNotEqualTo(newSample.get("essayHash"));
        assertThat(next.get("exclusionSelectionFingerprint")).isEqualTo(prior.get("selectionFingerprint"));
        prior.put("corpusFingerprint", "e".repeat(64));
        JSON.writeValue(old.toFile(), prior);
        assertThatThrownBy(() -> export(database, temporary.resolve("wrong.json"), "--exclude-benchmark", old.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("corpus");
    }

    @Test
    void qualityCliDispatchesNewProtocolAndNeverTreatsFakeCallsAsReleaseEvidence() throws Exception {
        Path database = corpus(), frozen = temporary.resolve("benchmark.json");
        export(database, frozen);
        var benchmark = JSON.readValue(Files.readString(frozen), Map.class);
        var samples = (List<Map<String, Object>>) benchmark.get("samples");
        List<Map<String, Object>> results = new ArrayList<>();
        for (var sample : samples) results.add(Map.of("essayHash", sample.get("essayHash"), "status", "READY",
                "scores", List.of(80, 120, 160, 120, 80)));
        var baseline = arm(benchmark, "baseline", results); var candidate = arm(benchmark, "rag", results);
        Path before = temporary.resolve("baseline.json"), after = temporary.resolve("rag.json"), output = temporary.resolve("gate.json");
        JSON.writeValue(before.toFile(), baseline); JSON.writeValue(after.toFile(), candidate);
        RagQualityGateCli.main(new String[]{frozen.toString(), before.toString(), after.toString(), output.toString()});
        var result = JSON.readValue(Files.readString(output), Map.class);
        assertThat(result).containsEntry("passed", true).containsEntry("essayCount", 2).containsEntry("annotationCount", 4);
        assertThat((Map<String, Object>) result.get("byRater")).containsOnlyKeys("grader_a", "grader_b");
        assertThatThrownBy(() -> RagQualityGateCli.main(new String[]{"--release", "lexical-v1", frozen.toString(), before.toString(), after.toString()}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Gemma");
    }

    private Path corpus() throws Exception {
        Path database = temporary.resolve("corpus.db");
        List<PublicHumanCorpus.ImportEntry> entries = new ArrayList<>();
        for (int i = 0; i < 2; i++) for (String grader : List.of("grader_a", "grader_b")) {
            int score = 80 + 40 * i + (grader.equals("grader_b") ? 40 : 0);
            entries.add(new PublicHumanCorpus.ImportEntry("kamel-usp/aes_enem_dataset", "JBCS2025", i + grader, "test",
                    "theme-test", "Educação", "Redação humana de teste " + i, 2024, "Apache-2.0",
                    "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset", "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset",
                    new PublicHumanCorpus.HumanScore(grader, score, score, score, score, score, score * 5)));
        }
        try (var corpus = new PublicHumanCorpus(database)) { corpus.importEntries(entries, "fixture"); }
        return database;
    }

    private static Map<String, Object> arm(Map<String, Object> benchmark, String name, List<Map<String, Object>> results) {
        var arm = new LinkedHashMap<String, Object>();
        arm.put("arm", name); arm.put("selectionFingerprint", benchmark.get("selectionFingerprint"));
        arm.put("complete", true); arm.put("completedCount", results.size());
        arm.put("runtime", Map.of("provider", "fake", "model", "fixture"));
        arm.put("rag", name.equals("rag")
                ? Map.of("enabled", true, "available", true, "fingerprint", benchmark.get("corpusFingerprint"))
                : Map.of("enabled", false, "available", false));
        arm.put("results", results); return arm;
    }

    private static void export(Path database, Path output, String... extra) throws Exception {
        List<String> args = new ArrayList<>(List.of("--database", database.toString(), "--output", output.toString(), "--split", "test"));
        args.addAll(List.of(extra)); TwoRaterBenchmarkCli.main(args.toArray(String[]::new));
    }
}
