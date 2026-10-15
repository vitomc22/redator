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
        return calculate(goldScores, predictedScores, false);
    }

    public static MetricsSummary calculateCompetence(List<Integer> goldScores, List<Integer> predictedScores) {
        return calculate(goldScores, predictedScores, true);
    }

    private static MetricsSummary calculate(List<Integer> goldScores, List<Integer> predictedScores, boolean competence) {
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
            if (goldScores.get(i) == null || predictedScores.get(i) == null) throw new IllegalArgumentException("nota ausente");
            int gold = goldScores.get(i);
            int predicted = predictedScores.get(i);
            int max = competence ? 200 : 1000;
            if (gold < 0 || predicted < 0 || gold > max || predicted > max) throw new IllegalArgumentException("nota fora da faixa");
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
        double qwk = competence && java.util.stream.Stream.concat(goldScores.stream(), predictedScores.stream()).anyMatch(n -> n % 40 != 0)
                ? Double.NaN : calculateQwk(goldScores, predictedScores, competence);

        return new MetricsSummary(count, mae, rmse, exactAgreement, adjacentAgreement, meanBias, qwk);
    }

    private static double calculateQwk(List<Integer> goldScores, List<Integer> predictedScores, boolean competence) {
        int levels = 6;
        int[][] confusionMatrix = new int[levels][levels];

        for (int i = 0; i < goldScores.size(); i++) {
            int gold = competence ? goldScores.get(i) / 40 : normalizeToLevel(goldScores.get(i));
            int predicted = competence ? predictedScores.get(i) / 40 : normalizeToLevel(predictedScores.get(i));
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
            return Double.NaN;
        }
        return (po - expectedWeighted) / (1.0 - expectedWeighted);
    }

    public static int[][] competenceConfusion(List<Integer> gold, List<Integer> predicted) {
        calculateCompetence(gold, predicted);
        int[][] matrix = new int[6][6];
        for (int i = 0; i < gold.size(); i++) {
            if (gold.get(i) % 40 != 0 || predicted.get(i) % 40 != 0) throw new IllegalArgumentException("QWK/matriz exigem seis niveis, sem arredondamento");
            matrix[gold.get(i) / 40][predicted.get(i) / 40]++;
        }
        return matrix;
    }

    private static int normalizeToLevel(int score) {
        int bounded = Math.max(0, Math.min(1000, score));
        return Math.min(5, bounded / 200);
    }
}
