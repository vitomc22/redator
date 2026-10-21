package com.redator.corretor.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.TwoRaterBenchmarkExporter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Freezes the two human references, optionally reserving essays absent from an earlier benchmark. */
public final class TwoRaterBenchmarkCli {
    private static final ObjectMapper JSON = new ObjectMapper();
    private TwoRaterBenchmarkCli() { }

    public static void main(String[] args) throws Exception {
        var options = options(args);
        Path database = Path.of(required(options, "database"));
        Path output = Path.of(required(options, "output"));
        String split = options.getOrDefault("split", "validation");
        int limit = Integer.parseInt(options.getOrDefault("limit", "10000"));
        Set<String> excluded = new HashSet<>();
        Map<String, Object> prior = null;
        if (options.containsKey("exclude-benchmark")) {
            prior = JSON.readValue(Files.readString(Path.of(options.get("exclude-benchmark"))), new TypeReference<>() { });
            if (!split.equals(prior.get("split"))) throw new IllegalArgumentException("split da exclusão difere da seleção");
            Object raw = prior.get("samples");
            if (!(raw instanceof List<?> samples) || samples.isEmpty()) throw new IllegalArgumentException("exclusão sem samples");
            if (!DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)).equals(prior.get("selectionFingerprint")))
                throw new IllegalArgumentException("fingerprint da seleção de exclusão não confere");
            if (!(prior.get("sampleCount") instanceof Number count) || count.doubleValue() != samples.size())
                throw new IllegalArgumentException("sampleCount da exclusão não confere");
            for (Object item : samples) {
                if (!(item instanceof Map<?, ?> sample) || !(sample.get("text") instanceof String text)
                        || !PublicHumanCorpus.hashText(text).equals(sample.get("essayHash"))
                        || !excluded.add((String) sample.get("essayHash")))
                    throw new IllegalArgumentException("amostra de exclusão inválida ou duplicada");
            }
        }
        var document = new TwoRaterBenchmarkExporter().export(database, split, limit, excluded);
        if (prior != null) {
            if (!document.get("corpusFingerprint").equals(prior.get("corpusFingerprint")))
                throw new IllegalArgumentException("corpus da exclusão difere do corpus exportado");
            document.put("exclusionSelectionFingerprint", prior.get("selectionFingerprint"));
        }
        String content = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
        if (Files.exists(output)) {
            if (!Files.readString(output).equals(content))
                throw new IllegalStateException("artefato congelado já existe com outra seleção: " + output);
        } else {
            Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(output, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        System.out.println(JSON.writeValueAsString(Map.of("output", output.toString(),
                "sampleCount", document.get("sampleCount"), "annotationCount", document.get("annotationCount"),
                "selectionFingerprint", document.get("selectionFingerprint"))));
    }

    private static Map<String, String> options(String[] args) {
        var values = new LinkedHashMap<String, String>();
        Set<String> allowed = Set.of("database", "output", "split", "limit", "exclude-benchmark");
        for (int i = 0; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length || !allowed.contains(args[i].substring(2))
                    || values.putIfAbsent(args[i].substring(2), args[i + 1]) != null)
                throw new IllegalArgumentException("Uso: --database <db> --output <json> [--split validation|test] [--limit N] [--exclude-benchmark <json>]");
        }
        return values;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("--" + name + " é obrigatório");
        return value;
    }
}
