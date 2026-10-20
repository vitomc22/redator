package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.cli.PairedBenchmarkCli;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PairedBenchmarkCliTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void createsMissingOperationalDataDirectoryBeforeStartingSqlite() throws Exception {
        String text = "A educação midiática fortalece a cidadania. Portanto, escolas devem promover oficinas de checagem, com professores capacitados, para reduzir a desinformação.";
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("essayHash", PublicHumanCorpus.hashText(text)); sample.put("source", "test");
        sample.put("sourceId", "one"); sample.put("license", "MIT"); sample.put("theme", "Educação midiática");
        sample.put("text", text); sample.put("humanScores", List.of(120, 120, 120, 120, 120));
        sample.put("humanTotal", 600); sample.put("graderAggregation", "human");
        List<Map<String, Object>> samples = List.of(sample);
        Map<String, Object> benchmark = new LinkedHashMap<>();
        benchmark.put("schemaVersion", 1); benchmark.put("corpusFingerprint", "c".repeat(64));
        benchmark.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)));
        benchmark.put("split", "test"); benchmark.put("sampleCount", 1); benchmark.put("samples", samples);
        Path input = temporary.resolve("benchmark.json"); Path output = temporary.resolve("result.json");
        Path missingDataDirectory = temporary.resolve("missing/data");
        Files.writeString(input, JSON.writeValueAsString(benchmark));

        PairedBenchmarkCli.main(new String[] {"--benchmark", input.toString(), "--output", output.toString(),
                "--arm", "baseline", "--data-directory", missingDataDirectory.toString(), "--provider", "fake"});

        assertThat(missingDataDirectory.resolve("redator.db")).isRegularFile();
        assertThat(output).isRegularFile();
    }
}
