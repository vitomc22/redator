package com.redator.corretor.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class BaselineComparisonService {

    private BaselineComparisonService() {
    }

    public record BaselineSummary(
            String name,
            long count,
            double mae,
            double rmse,
            double exactAgreement,
            double adjacentAgreement,
            double meanBias,
            double qwk
    ) {
    }

    public static List<Integer> generateB0(List<Integer> goldScores) {
        if (goldScores == null || goldScores.isEmpty()) {
            throw new IllegalArgumentException("goldScores nao pode ser nulo ou vazio");
        }
        int median = calculateMedian(goldScores);
        return repeatValue(median, goldScores.size());
    }

    public static List<Integer> generateB1(List<Integer> goldScores) {
        if (goldScores == null || goldScores.isEmpty()) {
            throw new IllegalArgumentException("goldScores nao pode ser nulo ou vazio");
        }
        int mean = Math.round((float) goldScores.stream().mapToInt(Integer::intValue).sum() / goldScores.size());
        return repeatValue(mean, goldScores.size());
    }

    public static Map<String, BaselineSummary> compare(List<Integer> goldScores, Map<String, List<Integer>> predictionsByBaseline) {
        if (goldScores == null || goldScores.isEmpty()) {
            throw new IllegalArgumentException("goldScores nao pode ser nulo ou vazio");
        }
        if (predictionsByBaseline == null || predictionsByBaseline.isEmpty()) {
            throw new IllegalArgumentException("predictionsByBaseline nao pode ser nulo ou vazio");
        }

        Map<String, BaselineSummary> summary = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : new TreeMap<>(predictionsByBaseline).entrySet()) {
            String baselineName = entry.getKey();
            List<Integer> predicted = entry.getValue();
            if (predicted == null || predicted.size() != goldScores.size()) {
                throw new IllegalArgumentException("Predicoes da baseline '%s' devem ter o mesmo tamanho do gold".formatted(baselineName));
            }

            DatasetMetricsCalculator.MetricsSummary metrics = DatasetMetricsCalculator.calculate(goldScores, predicted);
            summary.put(baselineName, new BaselineSummary(
                    baselineName,
                    metrics.count(),
                    metrics.mae(),
                    metrics.rmse(),
                    metrics.exactAgreement(),
                    metrics.adjacentAgreement(),
                    metrics.meanBias(),
                    metrics.qwk()
            ));
        }

        return summary;
    }

    public static Map<String, Object> compareWithDelta(List<Integer> goldScores, Map<String, List<Integer>> predictionsByBaseline) {
        Map<String, BaselineSummary> baselineSummary = compare(goldScores, predictionsByBaseline);
        List<String> orderedNames = new ArrayList<>(new TreeMap<>(baselineSummary).keySet());
        Map<String, Object> result = new LinkedHashMap<>();

        for (int i = 0; i < orderedNames.size(); i++) {
            String name = orderedNames.get(i);
            BaselineSummary current = baselineSummary.get(name);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("name", current.name());
            payload.put("count", current.count());
            payload.put("mae", current.mae());
            payload.put("rmse", current.rmse());
            payload.put("exactAgreement", current.exactAgreement());
            payload.put("adjacentAgreement", current.adjacentAgreement());
            payload.put("meanBias", current.meanBias());
            payload.put("qwk", current.qwk());

            if (i == 0) {
                payload.put("deltaMae", 0.0);
                payload.put("deltaRmse", 0.0);
                payload.put("deltaQwk", 0.0);
            } else {
                BaselineSummary previous = baselineSummary.get(orderedNames.get(i - 1));
                payload.put("deltaMae", current.mae() - previous.mae());
                payload.put("deltaRmse", current.rmse() - previous.rmse());
                payload.put("deltaQwk", current.qwk() - previous.qwk());
            }

            result.put(name, payload);
        }

        return result;
    }

    private static int calculateMedian(List<Integer> scores) {
        List<Integer> sorted = new ArrayList<>(scores);
        Collections.sort(sorted);
        int middle = sorted.size() / 2;
        if (sorted.size() % 2 == 0) {
            return (sorted.get(middle - 1) + sorted.get(middle)) / 2;
        }
        return sorted.get(middle);
    }

    private static List<Integer> repeatValue(int value, int count) {
        List<Integer> repeated = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            repeated.add(value);
        }
        return repeated;
    }
}
