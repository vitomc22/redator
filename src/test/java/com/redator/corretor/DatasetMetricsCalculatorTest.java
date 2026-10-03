package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.service.BaselineComparisonService;
import com.redator.corretor.service.DatasetMetricsCalculator;
import com.redator.corretor.service.DatasetReportGenerator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatasetMetricsCalculatorTest {

    @Test
    void shouldCalculateDatasetAgreementMetrics() {
        DatasetMetricsCalculator.MetricsSummary summary = DatasetMetricsCalculator.calculate(
                List.of(200, 400, 600, 800),
                List.of(180, 420, 620, 760)
        );

        assertThat(summary.count()).isEqualTo(4);
        assertThat(summary.mae()).isGreaterThan(0.0);
        assertThat(summary.mae()).isLessThan(100.0);
        assertThat(summary.exactAgreement()).isEqualTo(0.0);
        assertThat(summary.adjacentAgreement()).isGreaterThan(0.0);
        assertThat(summary.qwk()).isGreaterThan(0.0);
    }

    @Test
    void shouldWriteMarkdownAndCsvReports(@TempDir Path tempDir) throws Exception {
        Path outputDir = tempDir.resolve("eval-reports").resolve("run-001");

        Path reportDir = new DatasetReportGenerator().generate(outputDir,
                List.of(200, 400, 600, 800),
                List.of(180, 420, 620, 760));

        assertThat(reportDir).isDirectory();
        assertThat(reportDir.resolve("report.md")).exists();
        assertThat(reportDir.resolve("metrics.csv")).exists();
        assertThat(Files.readString(reportDir.resolve("report.md"))).contains("MAE");
        assertThat(Files.readString(reportDir.resolve("metrics.csv"))).contains("count,mae");
    }

    @Test
    void shouldCompareBaselines() {
        Map<String, List<Integer>> baselinePredictions = Map.of(
                "B0", List.of(220, 390, 610, 780),
                "B1", List.of(180, 420, 620, 760),
                "B2", List.of(200, 400, 600, 800)
        );

        Map<String, BaselineComparisonService.BaselineSummary> summary = BaselineComparisonService.compare(
                List.of(200, 400, 600, 800),
                baselinePredictions
        );

        assertThat(summary).containsKeys("B0", "B1", "B2");
        assertThat(summary.get("B2").mae()).isZero();
        assertThat(summary.get("B2").qwk()).isEqualTo(1.0);
        assertThat(summary.get("B0").mae()).isLessThan(summary.get("B1").mae());
    }
}
