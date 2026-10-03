package com.redator.corretor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.DatasetMetricsCalculator;
import com.redator.corretor.service.DatasetReportGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class DatasetEvaluationCli {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Uso: DatasetEvaluationCli <caminho-do-dataset.json> [diretorio-do-relatorio]");
            return;
        }

        Path sourcePath = Path.of(args[0]);
        Path outputDir = args.length > 1 ? Path.of(args[1]) : Path.of("eval", "reports", "run-" + System.currentTimeMillis());
        Map<String, Object> payload = new ObjectMapper().readValue(Files.readString(sourcePath), Map.class);

        List<Integer> goldScores = readScores(payload, "gold");
        List<Integer> predictedScores = readScores(payload, "predicted");

        DatasetMetricsCalculator.MetricsSummary summary = DatasetMetricsCalculator.calculate(goldScores, predictedScores);
        Path reportDir = new DatasetReportGenerator().generate(outputDir, goldScores, predictedScores);

        System.out.println("Relatorio gerado em: " + reportDir.toAbsolutePath());
        System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
    }

    private static List<Integer> readScores(Map<String, Object> payload, String key) {
        Object value = payload.get(key);
        if (value instanceof List<?> list) {
            List<Integer> numbers = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof Number number) {
                    numbers.add(number.intValue());
                }
            }
            return numbers;
        }
        throw new IllegalArgumentException("Campo '" + key + "' deve ser uma lista numerica");
    }
}
