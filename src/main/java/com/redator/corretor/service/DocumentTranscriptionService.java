package com.redator.corretor.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentTranscriptionService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+(?:[\\-'][\\p{L}\\p{N}]+)*");
    private static final Pattern SUSPICIOUS_TOKEN_PATTERN = Pattern.compile("\\[[^\\]]+\\]|\\?+|[\\p{L}\\p{N}]+(?:[\\-'][\\p{L}\\p{N}]+)*");
    private static volatile VisionTranscriptionProvider defaultProvider = new LocalVisionTranscriptionProvider();

    private DocumentTranscriptionService() {
    }

    public interface VisionTranscriptionProvider {
        String transcribe(byte[] content, String fileName);
    }

    private static final class LocalVisionTranscriptionProvider implements VisionTranscriptionProvider {
        @Override
        public String transcribe(byte[] content, String fileName) {
            if (content == null || content.length == 0) {
                return "Arquivo vazio. Revisão humana obrigatória antes da avaliação.";
            }

            String lowerName = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
            if (lowerName.endsWith(".pdf")) {
                String pdfText = extractPdfText(content);
                if (!pdfText.isBlank()) {
                    return pdfText;
                }
                return "Texto transcrito do arquivo " + fileName + ". Revisão humana obrigatória antes da avaliação.";
            }

            if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
                return "Imagem validada. Revisão humana obrigatória antes da avaliação. A transcrição literal precisa ser checada pelo editor.";
            }

            return "Arquivo validado. Revisão humana obrigatória antes da avaliação.";
        }

        private String extractPdfText(byte[] content) {
            String raw = new String(content, StandardCharsets.ISO_8859_1);
            List<String> textSegments = new ArrayList<>();
            Matcher matcher = Pattern.compile("\\((?:\\\\.|[^()\\\\])*\\)").matcher(raw);
            while (matcher.find()) {
                String token = matcher.group();
                String decoded = token.substring(1, token.length() - 1)
                        .replace("\\n", " ")
                        .replace("\\(", "(")
                        .replace("\\)", ")")
                        .replace("\\012", " ")
                        .replace("\\015", " ")
                        .replace("\\r", " ")
                        .replace("\\t", " ");
                if (!decoded.isBlank()) {
                    textSegments.add(decoded);
                }
            }

            String joined = String.join(" ", textSegments).replaceAll("\\s+", " ").trim();
            return joined.isBlank() ? "" : joined;
        }
    }

    public record Summary(
            int palavras,
            int linhas,
            int paragrafos,
            int ilegiveis,
            int tokensForaDoDicionario,
            String quality,
            List<String> suspiciousTokens
    ) {
    }

    public static String highlightSuspiciousTokens(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String escaped = text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");

        return escaped.replaceAll("\\[[^\\]]+\\]|\\?+", "<mark>$0</mark>");
    }

    public static void setDefaultProvider(VisionTranscriptionProvider provider) {
        if (provider == null) {
            throw new IllegalArgumentException("provider nao pode ser nulo");
        }
        defaultProvider = provider;
    }

    public static String transcribeDocument(byte[] content, String fileName) {
        return defaultProvider.transcribe(content, fileName);
    }

    public static String transcribeDocument(byte[] content, String fileName, VisionTranscriptionProvider provider) {
        if (provider == null) {
            return transcribeDocument(content, fileName);
        }
        return provider.transcribe(content, fileName);
    }

    public static Summary analyze(String text) {
        if (text == null || text.isBlank()) {
            return new Summary(0, 0, 0, 0, 0, "OK", List.of());
        }

        String[] lines = text.split("\\R+");
        int linhas = 0;
        for (String line : lines) {
            if (line != null && !line.isBlank()) {
                linhas++;
            }
        }

        String[] paragraphs = text.split("\\n\\s*\\n+");
        int paragrafos = 0;
        for (String paragraph : paragraphs) {
            if (paragraph != null && !paragraph.isBlank()) {
                paragrafos++;
            }
        }

        Matcher wordMatcher = WORD_PATTERN.matcher(text);
        List<String> words = new ArrayList<>();
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            if (!word.isBlank()) {
                words.add(word.toLowerCase(Locale.ROOT));
            }
        }

        Matcher illegibleMatcher = Pattern.compile("\\[[^\\]]+\\]").matcher(text);
        int ilegiveis = 0;
        while (illegibleMatcher.find()) {
            ilegiveis++;
        }

        List<String> suspiciousTokens = new ArrayList<>();
        Matcher suspiciousMatcher = SUSPICIOUS_TOKEN_PATTERN.matcher(text);
        while (suspiciousMatcher.find()) {
            String token = suspiciousMatcher.group();
            String normalized = token.toLowerCase(Locale.ROOT);
            if (normalized.contains("[") || normalized.contains("]") || normalized.contains("?") || normalized.length() >= 12) {
                suspiciousTokens.add(normalized);
            }
        }

        double ratio = words.isEmpty() ? 0.0 : (double) suspiciousTokens.size() / words.size();
        String quality = (ilegiveis > 0 || ratio > 0.06) ? "REVISAR" : "OK";

        return new Summary(
                words.size(),
                linhas,
                paragrafos,
                ilegiveis,
                suspiciousTokens.size(),
                quality,
                suspiciousTokens
        );
    }
}
