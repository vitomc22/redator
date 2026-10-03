package com.redator.corretor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

public class DatasetReportGenerator {

    public Path generate(Path outputDir, List<Integer> goldScores, List<Integer> predictedScores) throws IOException {
        if (outputDir == null) {
            throw new IllegalArgumentException("outputDir nao pode ser nulo");
        }

        DatasetMetricsCalculator.MetricsSummary summary = DatasetMetricsCalculator.calculate(goldScores, predictedScores);
        Files.createDirectories(outputDir);

        writeMetricsCsv(outputDir.resolve("metrics.csv"), summary);
        writePerEssayCsv(outputDir.resolve("per-essay.csv"), goldScores, predictedScores);
        writeMarkdownReport(outputDir.resolve("report.md"), summary);

        return outputDir;
    }

    private void writeMetricsCsv(Path path, DatasetMetricsCalculator.MetricsSummary summary) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("count,mae,rmse,exactAgreement,adjacentAgreement,meanBias,qwk").append(System.lineSeparator());
        csv.append(summary.count())
                .append(',')
                .append(format(summary.mae()))
                .append(',')
                .append(format(summary.rmse()))
                .append(',')
                .append(format(summary.exactAgreement()))
                .append(',')
                .append(format(summary.adjacentAgreement()))
                .append(',')
                .append(format(summary.meanBias()))
                .append(',')
                .append(format(summary.qwk()))
                .append(System.lineSeparator());
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writePerEssayCsv(Path path, List<Integer> goldScores, List<Integer> predictedScores) throws IOException {
        StringBuilder csv = new StringBuilder();
        csv.append("id,gold,predicted,difference").append(System.lineSeparator());

        for (int i = 0; i < goldScores.size(); i++) {
            int gold = goldScores.get(i);
            int predicted = predictedScores.get(i);
            csv.append(i + 1)
                    .append(',')
                    .append(gold)
                    .append(',')
                    .append(predicted)
                    .append(',')
                    .append(predicted - gold)
                    .append(System.lineSeparator());
        }

        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeMarkdownReport(Path path, DatasetMetricsCalculator.MetricsSummary summary) throws IOException {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Dataset evaluation report\n\n");
        markdown.append("## Summary\n\n");
        markdown.append("| Metric | Value |\n");
        markdown.append("| --- | ---: |\n");
        markdown.append("| count | ").append(summary.count()).append(" |\n");
        markdown.append("| MAE | ").append(format(summary.mae())).append(" |\n");
        markdown.append("| RMSE | ").append(format(summary.rmse())).append(" |\n");
        markdown.append("| exact agreement | ").append(format(summary.exactAgreement())).append(" |\n");
        markdown.append("| adjacent agreement | ").append(format(summary.adjacentAgreement())).append(" |\n");
        markdown.append("| mean bias | ").append(format(summary.meanBias())).append(" |\n");
        markdown.append("| QWK | ").append(format(summary.qwk())).append(" |\n\n");
        markdown.append("## Interpretation\n\n");
        markdown.append("- Metric set designed for model comparison in ENEM-style essay evaluation.\n");
        markdown.append("- Lower MAE/RMSE and mean bias closer to zero are better.\n");
        markdown.append("- QWK and adjacent agreement provide a more stable signal than exact match when score bands are coarse.\n");
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
