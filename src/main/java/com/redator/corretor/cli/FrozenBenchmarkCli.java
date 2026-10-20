package com.redator.corretor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exporta uma seleção humana reservada, determinística e identificada pelo corpus lógico. */
public final class FrozenBenchmarkCli {
    private FrozenBenchmarkCli() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        Path database = Path.of(required(options, "database"));
        Path output = Path.of(required(options, "output"));
        String split = options.getOrDefault("split", "test");
        int limit = Integer.parseInt(options.getOrDefault("limit", "100"));
        ObjectMapper mapper = new ObjectMapper();
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            List<PublicHumanCorpus.Example> examples = corpus.benchmarkExamples(split, limit);
            if (examples.isEmpty()) throw new IllegalArgumentException("split reservado não possui exemplos elegíveis");
            List<Map<String, Object>> samples = examples.stream().map(FrozenBenchmarkCli::sample).toList();
            String selectionFingerprint = DatasetManifestValidator.sha256(
                    mapper.writeValueAsBytes(samples));
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("schemaVersion", 1);
            document.put("corpusFingerprint", corpus.fingerprint());
            document.put("selectionFingerprint", selectionFingerprint);
            document.put("split", split);
            document.put("sampleCount", samples.size());
            document.put("samples", samples);
            if (output.toAbsolutePath().getParent() != null) Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n",
                    StandardCharsets.UTF_8);
            System.out.println(mapper.writeValueAsString(Map.of("output", output.toAbsolutePath().normalize().toString(),
                    "sampleCount", samples.size(), "selectionFingerprint", selectionFingerprint)));
        }
    }

    private static Map<String, Object> sample(PublicHumanCorpus.Example example) {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("essayHash", example.essayHash());
        sample.put("source", example.source());
        sample.put("sourceId", example.sourceId());
        sample.put("license", example.licenseSpdx());
        sample.put("theme", example.theme());
        sample.put("text", example.essayText());
        sample.put("humanScores", example.scores());
        sample.put("humanTotal", example.total());
        sample.put("graderAggregation", example.graderId());
        return sample;
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length)
                throw new IllegalArgumentException("Uso: --database <db> --output <json> [--split test] [--limit 100]");
            values.put(args[index].substring(2), args[index + 1]);
        }
        return values;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " é obrigatório");
        return value;
    }
}
