package com.redator.corretor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.BaselineComparisonService;
import com.redator.corretor.service.DatasetMetricsCalculator;
import com.redator.corretor.service.DatasetReportGenerator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EvalCli {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            printUsage();
            return;
        }

        String command = args[0];
        switch (command) {
            case "baseline" -> runBaseline(args);
            case "compare" -> runCompare(args);
            default -> {
                System.err.println("Comando desconhecido: " + command);
                printUsage();
            }
        }
    }

    private static void runBaseline(String[] args) throws Exception {
        String dataPath = null;
        String kind = null;
        String outputDir = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input" -> dataPath = args[++i];
                case "--kind" -> kind = args[++i];
                case "--output" -> outputDir = args[++i];
                default -> {
                    System.err.println("Argumento desconhecido: " + arg);
                    printUsage();
                    return;
                }
            }
        }

        if (dataPath == null || kind == null) {
            System.err.println("baseline exige --input e --kind");
            printUsage();
            return;
        }

        Map<String, Object> payload = MAPPER.readValue(Files.readString(Path.of(dataPath), StandardCharsets.UTF_8), Map.class);
        List<Integer> goldScores = readScores(payload, "gold");
        List<Integer> predictedScores = switch (kind.toUpperCase()) {
            case "B0" -> BaselineComparisonService.generateB0(goldScores);
            case "B1" -> BaselineComparisonService.generateB1(goldScores);
            default -> throw new IllegalArgumentException("kind deve ser B0 ou B1");
        };

        DatasetMetricsCalculator.MetricsSummary summary = DatasetMetricsCalculator.calculate(goldScores, predictedScores);
        Path reportDir = outputDir == null ? Path.of("eval", "reports", "baseline-" + kind.toLowerCase()) : Path.of(outputDir);
        Path generatedDir = new DatasetReportGenerator().generate(reportDir, goldScores, predictedScores);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("kind", kind.toUpperCase());
        result.put("reportDir", generatedDir.toAbsolutePath().toString());
        result.put("metrics", summary);
        result.put("predicted", predictedScores);
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static void runCompare(String[] args) throws Exception {
        String inputPath = null;
        String outputDir = null;

        for (int i = 1; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--input" -> inputPath = args[++i];
                case "--output" -> outputDir = args[++i];
                default -> {
                    System.err.println("Argumento desconhecido: " + arg);
                    printUsage();
                    return;
                }
            }
        }

        if (inputPath == null) {
            System.err.println("compare exige --input");
            printUsage();
            return;
        }

        Map<String, Object> payload = MAPPER.readValue(Files.readString(Path.of(inputPath), StandardCharsets.UTF_8), Map.class);
        List<Integer> goldScores = readScores(payload, "gold");
        Map<String, List<Integer>> predictionsByBaseline = new LinkedHashMap<>();

        for (String key : List.of("B0", "B1", "B2")) {
            if (payload.containsKey(key)) {
                predictionsByBaseline.put(key, readScores(payload, key));
            }
        }

        if (predictionsByBaseline.isEmpty()) {
            throw new IllegalArgumentException("O arquivo informado deve conter pelo menos uma baseline em B0/B1/B2");
        }

        Map<String, Object> result = BaselineComparisonService.compareWithDelta(goldScores, predictionsByBaseline);
        if (outputDir != null) {
            Path dir = Path.of(outputDir);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("baseline-comparison.json"), MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
        }

        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(result));
    }

    private static List<Integer> readScores(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> list) {
            List<Integer> result = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number number) {
                    result.add(number.intValue());
                }
            }
            if (!result.isEmpty()) {
                return result;
            }
        }
        throw new IllegalArgumentException("Campo '" + key + "' deve ser uma lista numerica");
    }

    private static void printUsage() {
        System.out.println("Uso:");
        System.out.println("  EvalCli baseline --input <dataset.json> --kind B0|B1 [--output <dir>]");
        System.out.println("  EvalCli compare --input <dataset.json> [--output <dir>]");
    }
}
