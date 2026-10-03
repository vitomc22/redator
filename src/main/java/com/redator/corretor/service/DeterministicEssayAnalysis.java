package com.redator.corretor.service;

import java.util.ArrayList;
import java.util.List;

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

        boolean injection = sanitized.toLowerCase().contains("ignore as instrucoes")
                || sanitized.toLowerCase().contains("nota 1000")
                || sanitized.toLowerCase().contains("ignore the instructions");

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

    private static int countSuspiciousTokens(String text) {
        String lower = text.toLowerCase();
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
