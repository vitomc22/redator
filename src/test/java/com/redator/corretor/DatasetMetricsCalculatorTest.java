package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.service.BaselineComparisonService;
import com.redator.corretor.service.DatasetMetricsCalculator;
import com.redator.corretor.service.DatasetReportGenerator;
import com.redator.corretor.service.DocumentTranscriptionService;
import com.redator.corretor.service.TranscriptionQualityMetricsCalculator;
import java.nio.charset.StandardCharsets;
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

    @Test
    void shouldMeasureTranscriptionQualityForGoNoGoDecision() {
        String goldText = "A educacao e fundamental para o futuro dos jovens.";
        String transcribedText = "A educação é fundamental para o futuro dos jovens.";

        TranscriptionQualityMetricsCalculator.Summary summary = TranscriptionQualityMetricsCalculator.calculate(goldText, transcribedText);

        assertThat(summary.cer()).isLessThan(0.20);
        assertThat(summary.wer()).isLessThan(0.20);
        assertThat(summary.preservationRate()).isGreaterThan(0.70);
        assertThat(summary.goNoGo()).isTrue();
    }

    @Test
    void shouldFlagSuspiciousTokensInTranscriptionQuality() {
        DocumentTranscriptionService.Summary quality = DocumentTranscriptionService.analyze(
                "A educacao [ilegivel] e fundamental para o futuro para [???]"
        );

        assertThat(quality.quality()).isEqualTo("REVISAR");
        assertThat(quality.suspiciousTokens()).isNotEmpty();
    }

    @Test
    void shouldTranscribePdfContentBeforeHumanReview() {
        String pdfContent = "%PDF-1.4\nBT\n/F1 12 Tf\n72 720 Td\n(A educacao e um direito fundamental.) Tj\nET\n%%EOF";

        String transcription = DocumentTranscriptionService.transcribeDocument(
                pdfContent.getBytes(StandardCharsets.UTF_8),
                "redacao.pdf"
        );

        assertThat(transcription).contains("educacao").contains("direito");
    }

    @Test
    void shouldDecodeEscapedPdfTextWithAccentsBeforeHumanReview() {
        String pdfContent = "%PDF-1.4\nBT\n/F1 12 Tf\n72 720 Td\n(A educa\\347\\343o e um direito fundamental.) Tj\nET\n%%EOF";

        String transcription = DocumentTranscriptionService.transcribeDocument(
                pdfContent.getBytes(StandardCharsets.ISO_8859_1),
                "redacao.pdf"
        );

        assertThat(transcription).contains("educa").contains("direito");
    }

    @Test
    void shouldUseCustomVisionProviderForDocumentTranscription() {
        DocumentTranscriptionService.VisionTranscriptionProvider provider = (content, fileName) -> "Transcricao literal validada por provider customizado";

        String transcription = DocumentTranscriptionService.transcribeDocument(
                "arquivo".getBytes(StandardCharsets.UTF_8),
                "redacao.png",
                provider
        );

        assertThat(transcription).isEqualTo("Transcricao literal validada por provider customizado");
    }

    @Test
    void shouldRouteDocumentTranscriptionThroughVisionGateway() {
        String pdfContent = "%PDF-1.4\nBT\n/F1 12 Tf\n72 720 Td\n(A educacao e um direito fundamental.) Tj\nET\n%%EOF";
        var gateway = new com.redator.corretor.service.VisionTranscriptionGateway();

        String transcription = gateway.transcribe(pdfContent.getBytes(StandardCharsets.UTF_8), "redacao.pdf");

        assertThat(transcription).contains("educacao").contains("direito");
    }
}
