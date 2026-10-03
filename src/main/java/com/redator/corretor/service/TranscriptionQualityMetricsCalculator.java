package com.redator.corretor.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class TranscriptionQualityMetricsCalculator {

    private static final double CER_LIMIT = 0.08;
    private static final double WER_LIMIT = 0.15;
    private static final double PRESERVATION_LIMIT = 0.90;

    private TranscriptionQualityMetricsCalculator() {
    }

    public record Summary(
            double cer,
            double wer,
            double preservationRate,
            boolean goNoGo
    ) {
    }

    public static Summary calculate(String goldText, String transcribedText) {
        if (goldText == null || goldText.isBlank()) {
            throw new IllegalArgumentException("goldText nao pode ser vazio");
        }
        if (transcribedText == null || transcribedText.isBlank()) {
            throw new IllegalArgumentException("transcribedText nao pode ser vazio");
        }

        String goldNormalized = normalize(goldText);
        String transcribedNormalized = normalize(transcribedText);

        int charDistance = levenshteinDistance(goldNormalized, transcribedNormalized);
        int wordDistance = levenshteinDistance(joinWords(normalizeWords(goldText)), joinWords(normalizeWords(transcribedText)));
        double cer = (double) charDistance / Math.max(1, goldNormalized.length());
        double wer = (double) wordDistance / Math.max(1, normalizeWords(goldText).size());
        double preservationRate = calculatePreservationRate(goldText, transcribedText);
        boolean goNoGo = cer <= CER_LIMIT && wer <= WER_LIMIT && preservationRate >= PRESERVATION_LIMIT;

        return new Summary(cer, wer, preservationRate, goNoGo);
    }

    private static double calculatePreservationRate(String goldText, String transcribedText) {
        List<String> goldWords = normalizeWords(goldText);
        List<String> transcribedWords = normalizeWords(transcribedText);
        int maxLen = Math.max(goldWords.size(), transcribedWords.size());
        if (maxLen == 0) {
            return 1.0;
        }

        int preserved = 0;
        int limit = Math.min(goldWords.size(), transcribedWords.size());
        for (int i = 0; i < limit; i++) {
            if (goldWords.get(i).equals(transcribedWords.get(i))) {
                preserved++;
            }
        }

        return (double) preserved / maxLen;
    }

    private static List<String> normalizeWords(String text) {
        String value = normalize(text);
        if (value.isBlank()) {
            return List.of();
        }
        String[] tokens = value.split("\\s+");
        List<String> words = new ArrayList<>();
        for (String token : tokens) {
            if (!token.isBlank()) {
                words.add(token);
            }
        }
        return words;
    }

    private static String normalize(String text) {
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        return normalized.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-zA-Z0-9\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String joinWords(List<String> words) {
        return String.join(" ", words);
    }

    private static int levenshteinDistance(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];

        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }

        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[left.length()][right.length()];
    }
}
