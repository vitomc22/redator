package com.redator.corretor.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.RagQualityGate;
import com.redator.corretor.service.PairedBenchmarkGate;
import com.redator.corretor.service.TwoRaterBenchmarkGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RagQualityGateCli {
    private RagQualityGateCli() { }

    public static void main(String[] args) throws Exception {
        String releasePolicy = null;
        if (args.length >= 2 && "--release".equals(args[0])) {
            releasePolicy = args[1];
            args = java.util.Arrays.copyOfRange(args, 2, args.length);
        }
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Uso: RagQualityGateCli [--release <política>] <benchmark-congelado.json> <baseline.json> <rag.json> [resultado.json]");
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> benchmark = read(mapper, args[0]);
        Map<String, Object> baseline = read(mapper, args[1]);
        Map<String, Object> candidate = read(mapper, args[2]);
        Object result;
        RagQualityGate.Result quality;
        if (Integer.valueOf(2).equals(benchmark.get("schemaVersion"))) {
            var twoRater = releasePolicy == null
                    ? TwoRaterBenchmarkGate.evaluate(benchmark, baseline, candidate)
                    : TwoRaterBenchmarkGate.evaluateRelease(benchmark, baseline, candidate, releasePolicy);
            result = twoRater; quality = twoRater.qualityGate();
        } else if (Integer.valueOf(1).equals(benchmark.get("schemaVersion"))) {
            quality = releasePolicy == null
                    ? PairedBenchmarkGate.evaluate(benchmark, baseline, candidate)
                    : PairedBenchmarkGate.evaluateRelease(benchmark, baseline, candidate, releasePolicy);
            result = quality;
        } else throw new IllegalArgumentException("schemaVersion do benchmark desconhecida");
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        if (args.length == 4) Files.writeString(Path.of(args[3]), json + "\n", StandardCharsets.UTF_8);
        System.out.println(json);
        if (!quality.passed()) throw new IllegalStateException("Gate RAG reprovado: " + String.join("; ", quality.failures()));
    }

    private static Map<String, Object> read(ObjectMapper mapper, String file) throws Exception {
        return mapper.readValue(Files.readString(Path.of(file)), new TypeReference<>() { });
    }
}
