package com.redator.corretor.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public record DeterministicEssayAnalysis(
        String text,
        int words,
        int paragraphs,
        int lines,
        int tokensForaDoDicionario,
        boolean containsPromptInjection,
        boolean shortText,
        boolean needsReview,
        String paragraphSummary,
        List<String> problems,
        List<String> evidencias
) {

    private static final Pattern[] PROMPT_INJECTION_PATTERNS = new Pattern[] {
            Pattern.compile("(?:ignore|ignora|ignorem|desconsidere|desconsiderar|nao\\s+siga|nao\\s+obedeca|nao\\s+obede[a-z]*|nao\\s+ignore|nao\\s+ignorem|nao\\s+desconsidere|nao\\s+procure|nao\\s+use|nao\\s+respeite)\\s+(?:as\\s+)?(?:instrucoes|instrucao|instrucoes\\s+anteriores|instrucoes\\s+do\\s+sistema)"),
            Pattern.compile("(?:d\\s*e|da|atribua|asigne|dar|forneca|forne\\s+|entregue)\\s+(?:a\\s+)?(?:maior|melhor|maxima|maximo|maior\\s+nota|melhor\\s+nota|nota\\s+mais\\s+alta)\\s*(?:possivel|possivel)?"),
            Pattern.compile("(?:d\\s*e|da|atribua|asigne|dar|forneca|forne\\s+|entregue)\\s+(?:a\\s+)?nota\\s*(?:\\d{3,4}|1000|10000)"),
            Pattern.compile("(?:ignore\\s+the\\s+instructions|ignore\\s+all\\s+instructions|do\\s+not\\s+follow\\s+instructions|override\\s+instructions|do\\s+not\\s+use\\s+the\\s+instructions)")
    };

    public static DeterministicEssayAnalysis analyze(String text) {
        String sanitized = text == null ? "" : text.trim();
        List<String> problems = new ArrayList<>();
        List<String> evidencias = new ArrayList<>();

        if (sanitized.isBlank()) {
            return new DeterministicEssayAnalysis("", 0, 0, 0, 0, false, true, true, "", problems, evidencias);
        }

        String[] words = sanitized.split("\\s+");
        String[] paragraphs = sanitized.split("\\n\\s*\\n+");
        String[] lines = sanitized.split("\\R+");

        int wordCount = words.length;
        int paragraphCount = Math.max(1, paragraphs.length);
        int lineCount = Math.max(1, lines.length);

        boolean injection = containsPromptInjectionAttempt(sanitized);

        boolean isShortText = lineCount <= 7 || wordCount < 120;
        boolean review = injection || isShortText;

        int tokensForaDoDicionario = countSuspiciousTokens(sanitized);
        if (injection) {
            problems.add("Entrada suspeita de injecao de prompt");
            evidencias.add("Padrao de instrucao explicita foi detectado");
        }
        if (isShortText) {
            problems.add("Texto muito curto para uma avaliacao robusta");
            evidencias.add("Conteudo com poucos paragrafos e menos de 120 palavras");
        }
        if (tokensForaDoDicionario > 0) {
            problems.add("Ha itens de registro informal e expressao coloquial");
            evidencias.add("Tokens de oralidade foram detectados");
        }

        String paragraphSummary = "paragrafos=" + paragraphCount + "; linhas=" + lineCount + "; palavras=" + wordCount;
        return new DeterministicEssayAnalysis(
                sanitized,
                wordCount,
                paragraphCount,
                lineCount,
                tokensForaDoDicionario,
                injection,
                isShortText,
                review,
                paragraphSummary,
                problems,
                evidencias
        );
    }

    private static boolean containsPromptInjectionAttempt(String text) {
        String normalized = normalizeForDetection(text);
        for (Pattern pattern : PROMPT_INJECTION_PATTERNS) {
            if (pattern.matcher(normalized).find()) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeForDetection(String text) {
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD);
        normalized = normalized.replaceAll("\\p{M}", "");
        normalized = normalized.toLowerCase(Locale.ROOT);
        normalized = normalized.replaceAll("[^a-z0-9\\s]", " ");
        normalized = normalized.replaceAll("\\s+", " ").trim();
        return normalized;
    }

    private static int countSuspiciousTokens(String text) {
        String lower = normalizeForDetection(text);
        String[] markers = {"vc", "pq", "tb", "ne", "tipo assim", "a gente", "ta", "da pra", "mano"};
        int count = 0;
        for (String marker : markers) {
            if (lower.contains(marker)) {
                count++;
            }
        }
        return count;
    }
}
