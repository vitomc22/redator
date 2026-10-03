package com.redator.corretor.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static Map<String, BaselineSummary> compare(List<Integer> goldScores, Map<String, List<Integer>> predictionsByBaseline) {
        if (goldScores == null || goldScores.isEmpty()) {
            throw new IllegalArgumentException("goldScores nao pode ser nulo ou vazio");
        }
        if (predictionsByBaseline == null || predictionsByBaseline.isEmpty()) {
            throw new IllegalArgumentException("predictionsByBaseline nao pode ser nulo ou vazio");
        }

        Map<String, BaselineSummary> summary = new LinkedHashMap<>();
        for (Map.Entry<String, List<Integer>> entry : predictionsByBaseline.entrySet()) {
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
        List<String> orderedNames = new ArrayList<>(baselineSummary.keySet());
        Map<String, Object> result = new LinkedHashMap<>();

        for (String name : orderedNames) {
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
            result.put(name, payload);
        }

        return result;
    }
}
