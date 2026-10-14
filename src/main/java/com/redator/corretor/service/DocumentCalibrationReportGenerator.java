package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public final class DocumentCalibrationReportGenerator {

    private final ObjectMapper objectMapper;

    public DocumentCalibrationReportGenerator() {
        this(new ObjectMapper());
    }

    DocumentCalibrationReportGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public Path generate(Path outputDir, DocumentCalibrationService.Summary summary) throws IOException {
        if (outputDir == null || summary == null) {
            throw new IllegalArgumentException("outputDir e summary sao obrigatorios");
        }

        Files.createDirectories(outputDir);
        Files.writeString(
                outputDir.resolve("summary.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(summary),
                StandardCharsets.UTF_8
        );
        writeCsv(outputDir.resolve("samples.csv"), summary);
        writeMarkdown(outputDir.resolve("report.md"), summary);
        return outputDir;
    }

    private void writeCsv(Path path, DocumentCalibrationService.Summary summary) throws IOException {
        StringBuilder csv = new StringBuilder("name,cer,wer,preservationRate,pass,verdict").append(System.lineSeparator());
        for (DocumentCalibrationService.SampleResult result : summary.results()) {
            csv.append(csvValue(result.name())).append(',')
                    .append(format(result.cer())).append(',')
                    .append(format(result.wer())).append(',')
                    .append(format(result.preservationRate())).append(',')
                    .append(result.pass()).append(',')
                    .append(result.verdict()).append(System.lineSeparator());
        }
        Files.writeString(path, csv.toString(), StandardCharsets.UTF_8);
    }

    private void writeMarkdown(Path path, DocumentCalibrationService.Summary summary) throws IOException {
        String verdict = summary.totalSamples() > 0 && summary.passCount() == summary.totalSamples() ? "GO" : "NO-GO";
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Document calibration report\n\n")
                .append("## Verdict\n\n**").append(verdict).append("**\n\n")
                .append("| Metric | Value |\n| --- | ---: |\n")
                .append("| Samples | ").append(summary.totalSamples()).append(" |\n")
                .append("| Passed | ").append(summary.passCount()).append(" |\n")
                .append("| Failed | ").append(summary.failCount()).append(" |\n")
                .append("| Pass rate | ").append(format(summary.passRate())).append(" |\n")
                .append("| Average CER | ").append(format(summary.averageCer())).append(" |\n")
                .append("| Average WER | ").append(format(summary.averageWer())).append(" |\n")
                .append("| Average preservation | ").append(format(summary.averagePreservationRate())).append(" |\n\n")
                .append("## Samples\n\n")
                .append("| Name | CER | WER | Preservation | Verdict |\n")
                .append("| --- | ---: | ---: | ---: | --- |\n");

        for (DocumentCalibrationService.SampleResult result : summary.results()) {
            markdown.append("| ").append(result.name()).append(" | ")
                    .append(format(result.cer())).append(" | ")
                    .append(format(result.wer())).append(" | ")
                    .append(format(result.preservationRate())).append(" | ")
                    .append(result.verdict()).append(" |\n");
        }

        markdown.append("\nThresholds: CER ≤ 0.08, WER ≤ 0.15, preservation ≥ 0.90, and no suspicious OCR noise.\n");
        Files.writeString(path, markdown.toString(), StandardCharsets.UTF_8);
    }

    private static String csvValue(String value) {
        String escaped = value == null ? "" : value.replace("\"", "\"\"");
        return '"' + escaped + '"';
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.6f", value);
    }
}
