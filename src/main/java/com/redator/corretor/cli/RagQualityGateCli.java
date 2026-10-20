package com.redator.corretor.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.RagQualityGate;
import com.redator.corretor.service.PairedBenchmarkGate;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

public final class RagQualityGateCli {
    private RagQualityGateCli() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3 || args.length > 4) {
            throw new IllegalArgumentException("Uso: RagQualityGateCli <benchmark-congelado.json> <baseline.json> <rag.json> [resultado.json]");
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> benchmark = read(mapper, args[0]);
        Map<String, Object> baseline = read(mapper, args[1]);
        Map<String, Object> candidate = read(mapper, args[2]);
        RagQualityGate.Result result = PairedBenchmarkGate.evaluate(benchmark, baseline, candidate);
        String json = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result);
        if (args.length == 4) Files.writeString(Path.of(args[3]), json + "\n", StandardCharsets.UTF_8);
        System.out.println(json);
        if (!result.passed()) throw new IllegalStateException("Gate RAG reprovado: " + String.join("; ", result.failures()));
    }

    private static Map<String, Object> read(ObjectMapper mapper, String file) throws Exception {
        return mapper.readValue(Files.readString(Path.of(file)), new TypeReference<>() { });
    }
}
