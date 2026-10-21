package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PairedBenchmarkRunner;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.TwoRaterBenchmarkExporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/paired-benchmark-runner-test.db",
        "app.llm.provider=fake", "app.rag.enabled=false", "app.rag.require-on-startup=false"
})
class PairedBenchmarkRunnerIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @Autowired private PairedBenchmarkRunner runner;
    @TempDir Path temporary;

    @Test
    void writesAuditableBaselineArmAgainstRealSpringAndSqlite() throws Exception {
        Path benchmark = temporary.resolve("benchmark.json");
        Path output = temporary.resolve("baseline.json");
        Files.writeString(benchmark, JSON.writeValueAsString(benchmarkDocument()));

        Map<String, Object> document = runner.run(benchmark, output, "baseline");

        assertThat(output).exists();
        assertThat(document).containsEntry("arm", "baseline").containsEntry("complete", true)
                .containsEntry("completedCount", 2);
        assertThat((List<?>) document.get("results")).allSatisfy(result -> {
            Map<?, ?> item = (Map<?, ?>) result;
            assertThat(item.keySet().stream().map(String::valueOf).toList())
                    .contains("essayHash", "status", "scores", "llmCalls");
        });
    }

    @Test
    void refusesRunningRagArmInBaselineContext() throws Exception {
        Path benchmark = temporary.resolve("benchmark.json");
        Files.writeString(benchmark, JSON.writeValueAsString(benchmarkDocument()));

        assertThatThrownBy(() -> runner.run(benchmark, temporary.resolve("rag.json"), "rag"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("app.rag.enabled=true");
    }

    @Test
    void runsTwoRaterSamplesAgainstRealSqliteWithoutInventingAnAveragedLabel() throws Exception {
        var benchmark = benchmarkDocument();
        var samples = (List<Map<String, Object>>) benchmark.get("samples");
        for (var sample : samples) {
            sample.remove("humanScores"); sample.remove("humanTotal"); sample.remove("graderAggregation");
            sample.put("humanEvaluations", List.of(
                    Map.of("graderId", "fixture_a", "scores", List.of(120, 120, 120, 120, 120), "total", 600),
                    Map.of("graderId", "fixture_b", "scores", List.of(160, 160, 160, 160, 160), "total", 800)));
        }
        benchmark.put("schemaVersion", 2); benchmark.put("humanLabelPolicy", TwoRaterBenchmarkExporter.LABEL_POLICY);
        benchmark.put("annotationCount", samples.size() * 2); benchmark.put("raterIds", List.of("fixture_a", "fixture_b"));
        benchmark.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)));
        Path input = temporary.resolve("two-raters.json"), output = temporary.resolve("two-raters-baseline.json");
        Files.writeString(input, JSON.writeValueAsString(benchmark));
        var result = runner.run(input, output, "baseline");
        assertThat(result).containsEntry("complete", true).containsEntry("completedCount", 2)
                .containsEntry("selectionFingerprint", benchmark.get("selectionFingerprint"));
        assertThat((List<Map<String, Object>>) result.get("results")).allSatisfy(item -> {
            assertThat(item).containsEntry("status", "NEEDS_REVIEW").containsEntry("needsReview", true);
            assertThat((List<String>) item.get("reviewReasons")).contains("FAKE_PROVIDER");
            assertThat((Map<?, ?>) item.get("scores")).hasSize(5);
            assertThat((List<?>) item.get("llmCalls")).hasSize(1);
        });
    }

    private static Map<String, Object> benchmarkDocument() throws Exception {
        List<Map<String, Object>> samples = List.of(sample("a", 80), sample("b", 160));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1); document.put("corpusFingerprint", "c".repeat(64));
        document.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)));
        document.put("split", "test"); document.put("sampleCount", samples.size()); document.put("samples", samples);
        return document;
    }

    private static Map<String, Object> sample(String hash, int score) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5")) scores.put(code, score);
        Map<String, Object> sample = new LinkedHashMap<>();
        String text = "A educação pública brasileira exige investimento contínuo. Além disso, a formação docente e a participação social podem ampliar a cidadania. Portanto, o Ministério da Educação deve financiar escolas e capacitar professores, por meio de programas nacionais, para garantir ensino de qualidade a todos. Identificador " + hash + ".";
        sample.put("essayHash", PublicHumanCorpus.hashText(text)); sample.put("source", "test"); sample.put("sourceId", hash);
        sample.put("license", "MIT"); sample.put("theme", "Desafios da educação pública no Brasil");
        sample.put("text", text);
        sample.put("humanScores", scores); sample.put("humanTotal", score * 5); sample.put("graderAggregation", "human");
        return sample;
    }
}
