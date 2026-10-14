package com.redator.corretor.service;

import java.util.ArrayList;
import java.util.List;

public final class DocumentCalibrationService {

    private DocumentCalibrationService() {
    }

    public record Sample(String name, String goldText, String transcribedText) {
    }

    public record SampleResult(
            String name,
            double cer,
            double wer,
            double preservationRate,
            boolean pass,
            String verdict
    ) {
    }

    public record Summary(
            int totalSamples,
            int passCount,
            int failCount,
            double averageCer,
            double averageWer,
            double averagePreservationRate,
            double passRate,
            List<SampleResult> results
    ) {
    }

    public static Summary summarize(List<Sample> samples) {
        if (samples == null || samples.isEmpty()) {
            return new Summary(0, 0, 0, 0.0, 0.0, 0.0, 0.0, List.of());
        }

        List<SampleResult> results = new ArrayList<>();
        double totalCer = 0.0;
        double totalWer = 0.0;
        double totalPreservation = 0.0;
        int passCount = 0;

        for (Sample sample : samples) {
            if (sample == null || sample.goldText() == null || sample.transcribedText() == null) {
                continue;
            }

            TranscriptionQualityMetricsCalculator.Summary metrics = TranscriptionQualityMetricsCalculator.calculate(
                    sample.goldText(),
                    sample.transcribedText()
            );

            boolean pass = metrics.goNoGo();
            if (pass) {
                passCount++;
            }

            totalCer += metrics.cer();
            totalWer += metrics.wer();
            totalPreservation += metrics.preservationRate();

            results.add(new SampleResult(
                    sample.name() == null || sample.name().isBlank() ? "sample" + results.size() : sample.name(),
                    metrics.cer(),
                    metrics.wer(),
                    metrics.preservationRate(),
                    pass,
                    pass ? "GO" : "NO-GO"
            ));
        }

        int total = results.size();
        double averageCer = total == 0 ? 0.0 : totalCer / total;
        double averageWer = total == 0 ? 0.0 : totalWer / total;
        double averagePreservation = total == 0 ? 0.0 : totalPreservation / total;
        double passRate = total == 0 ? 0.0 : (double) passCount / total;

        return new Summary(
                total,
                passCount,
                total - passCount,
                averageCer,
                averageWer,
                averagePreservation,
                passRate,
                results
        );
    }
}
