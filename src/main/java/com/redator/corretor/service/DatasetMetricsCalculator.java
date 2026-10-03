package com.redator.corretor.service;

import java.util.List;

public final class DatasetMetricsCalculator {

    private DatasetMetricsCalculator() {
    }

    public record MetricsSummary(
            long count,
            double mae,
            double rmse,
            double exactAgreement,
            double adjacentAgreement,
            double meanBias,
            double qwk
    ) {
    }

    public static MetricsSummary calculate(List<Integer> goldScores, List<Integer> predictedScores) {
        if (goldScores == null || predictedScores == null) {
            throw new IllegalArgumentException("goldScores e predictedScores sao obrigatorios");
        }
        if (goldScores.size() != predictedScores.size() || goldScores.isEmpty()) {
            throw new IllegalArgumentException("goldScores e predictedScores devem ter o mesmo tamanho e nao podem estar vazios");
        }

        int count = goldScores.size();
        double sumAbsError = 0.0;
        double sumSquaredError = 0.0;
        double totalBias = 0.0;
        int exactMatches = 0;
        int adjacentMatches = 0;

        for (int i = 0; i < count; i++) {
            int gold = goldScores.get(i);
            int predicted = predictedScores.get(i);
            int error = Math.abs(predicted - gold);
            sumAbsError += error;
            sumSquaredError += (double) error * error;
            totalBias += predicted - gold;
            if (error == 0) {
                exactMatches++;
            }
            if (error <= 40) {
                adjacentMatches++;
            }
        }

        double mae = sumAbsError / count;
        double rmse = Math.sqrt(sumSquaredError / count);
        double meanBias = totalBias / count;
        double exactAgreement = exactMatches / (double) count;
        double adjacentAgreement = adjacentMatches / (double) count;
        double qwk = calculateQwk(goldScores, predictedScores);

        return new MetricsSummary(count, mae, rmse, exactAgreement, adjacentAgreement, meanBias, qwk);
    }

    private static double calculateQwk(List<Integer> goldScores, List<Integer> predictedScores) {
        int levels = 6;
        int[][] confusionMatrix = new int[levels][levels];

        for (int i = 0; i < goldScores.size(); i++) {
            int gold = normalizeToLevel(goldScores.get(i));
            int predicted = normalizeToLevel(predictedScores.get(i));
            confusionMatrix[gold][predicted]++;
        }

        double observedWeighted = 0.0;
        int[] rowTotals = new int[levels];
        int[] columnTotals = new int[levels];

        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                rowTotals[i] += confusionMatrix[i][j];
                columnTotals[j] += confusionMatrix[i][j];
            }
        }

        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                double weight = 1.0 - Math.pow((double) (i - j) / (levels - 1), 2.0);
                observedWeighted += weight * confusionMatrix[i][j];
            }
        }

        double expectedWeighted = 0.0;
        double total = goldScores.size();
        for (int i = 0; i < levels; i++) {
            for (int j = 0; j < levels; j++) {
                double weight = 1.0 - Math.pow((double) (i - j) / (levels - 1), 2.0);
                expectedWeighted += weight * ((rowTotals[i] / total) * (columnTotals[j] / total));
            }
        }

        double po = observedWeighted / total;
        if (expectedWeighted >= 1.0) {
            return 1.0;
        }
        return (po - expectedWeighted) / (1.0 - expectedWeighted);
    }

    private static int normalizeToLevel(int score) {
        int bounded = Math.max(0, Math.min(1000, score));
        return Math.min(5, bounded / 200);
    }
}
