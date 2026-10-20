package com.redator.corretor.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.RagQualityGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RagQualityGateCli {
    private RagQualityGateCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            throw new IllegalArgumentException("Uso: RagQualityGateCli <benchmark.json> [resultado.json]");
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> payload = mapper.readValue(Files.readString(Path.of(args[0])), new TypeReference<>() { });
        String fingerprint = String.valueOf(payload.get("datasetFingerprint"));
        Map<String, List<Integer>> gold = scores(mapper, payload, "gold");
        Map<String, List<Integer>> baseline = scores(mapper, payload, "baseline");
        Map<String, List<Integer>> candidate = scores(mapper, payload, "candidate");
        RagQualityGate.Result result = RagQualityGate.evaluate(fingerprint, gold, baseline, candidate);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        if (args.length == 2) Files.writeString(Path.of(args[1]), json, StandardCharsets.UTF_8);
        System.out.println(json);
        if (!result.passed()) throw new IllegalStateException("Gate RAG reprovado: " + String.join("; ", result.failures()));
    }

    private static Map<String, List<Integer>> scores(ObjectMapper mapper, Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException(key + " deve conter C1-C5");
        return mapper.convertValue(value, new TypeReference<>() { });
    }
}
