package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.cli.FrozenBenchmarkCli;
import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FrozenBenchmarkCliTest {
    @TempDir Path temporaryDirectory;

    @Test
    void exportsSameFrozenSelectionByteForByte() throws Exception {
        Path database = temporaryDirectory.resolve("corpus.db");
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(entry("b", "Texto B reservado."), entry("a", "Texto A reservado.")), "v1");
        }
        Path first = temporaryDirectory.resolve("first.json");
        Path second = temporaryDirectory.resolve("second.json");
        String[] common = {"--database", database.toString(), "--split", "test", "--limit", "2"};
        FrozenBenchmarkCli.main(concat(common, "--output", first.toString()));
        FrozenBenchmarkCli.main(concat(common, "--output", second.toString()));

        assertThat(Files.readString(first)).isEqualTo(Files.readString(second));
        Map<?, ?> document = new ObjectMapper().readValue(Files.readString(first), Map.class);
        assertThat(document.get("sampleCount")).isEqualTo(2);
        assertThat(String.valueOf(document.get("corpusFingerprint"))).hasSize(64);
        assertThat(String.valueOf(document.get("selectionFingerprint"))).hasSize(64);
    }

    private static PublicHumanCorpus.ImportEntry entry(String id, String text) {
        return new PublicHumanCorpus.ImportEntry("fixture", "open", id, "test", "theme", "Tema", text, 2024,
                "MIT", "https://example.test/license", "https://example.test/source",
                new PublicHumanCorpus.HumanScore("human", 120, 160, 120, 160, 200, 760));
    }

    private static String[] concat(String[] source, String key, String value) {
        String[] result = java.util.Arrays.copyOf(source, source.length + 2);
        result[source.length] = key; result[source.length + 1] = value;
        return result;
    }
}
