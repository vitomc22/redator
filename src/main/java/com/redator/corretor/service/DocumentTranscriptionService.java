package com.redator.corretor.service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentTranscriptionService {

    private static final Pattern WORD_PATTERN = Pattern.compile("[\\p{L}\\p{N}]+(?:[\\-'][\\p{L}\\p{N}]+)*");
    private static final Pattern SUSPICIOUS_TOKEN_PATTERN = Pattern.compile("\\[[^\\]]+\\]|\\?+|[\\p{L}\\p{N}]+(?:[\\-'][\\p{L}\\p{N}]+)*");
    private static volatile VisionTranscriptionProvider defaultProvider = new RealVisionTranscriptionProvider();
    private static final String[] PDF_SPECIAL_ESCAPES = {
            "\\n", "\n",
            "\\r", "\r",
            "\\t", "\t",
            "\\b", "\b",
            "\\f", "\f",
            "\\(", "(",
            "\\)", ")",
            "\\\\", "\\"
    };

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
                String decoded = decodePdfLiteral(token.substring(1, token.length() - 1));
                if (!decoded.isBlank()) {
                    textSegments.add(decoded);
                }
            }

            String joined = String.join(" ", textSegments).replaceAll("\\s+", " ").trim();
            if (!joined.isBlank()) {
                return joined;
            }

            Matcher fallbackMatcher = Pattern.compile("(?s)\\((?:[^()]|\\([^)]*\\))*\\)").matcher(raw);
            while (fallbackMatcher.find()) {
                String token = fallbackMatcher.group();
                String decoded = token.substring(1, token.length() - 1)
                        .replace("\\n", " ")
                        .replace("\\r", " ")
                        .replace("\\t", " ");
                if (!decoded.isBlank()) {
                    textSegments.add(decoded);
                }
            }

            String fallbackJoined = String.join(" ", textSegments).replaceAll("\\s+", " ").trim();
            return fallbackJoined.isBlank() ? "" : fallbackJoined;
        }

        private String decodePdfLiteral(String literal) {
            if (literal == null || literal.isBlank()) {
                return "";
            }

            StringBuilder decoded = new StringBuilder();
            for (int i = 0; i < literal.length(); i++) {
                char ch = literal.charAt(i);
                if (ch == '\\' && i + 1 < literal.length()) {
                    char next = literal.charAt(i + 1);
                    if (next == 'n' || next == 'r' || next == 't' || next == 'b' || next == 'f') {
                        decoded.append(next == 'n' ? '\n' : next == 'r' ? '\r' : next == 't' ? '\t' : next == 'b' ? '\b' : '\f');
                        i++;
                        continue;
                    }
                    if (next == '(' || next == ')' || next == '\\') {
                        decoded.append(next);
                        i++;
                        continue;
                    }
                    if (Character.isDigit(next)) {
                        StringBuilder octal = new StringBuilder();
                        int count = 0;
                        while (i + 1 + count < literal.length() && count < 3 && Character.isDigit(literal.charAt(i + 1 + count))) {
                            char digit = literal.charAt(i + 1 + count);
                            if (digit >= '8') {
                                break;
                            }
                            octal.append(digit);
                            count++;
                        }
                        if (octal.length() > 0) {
                            int codePoint = Integer.parseInt(octal.toString(), 8);
                            decoded.append((char) codePoint);
                            i += octal.length();
                            continue;
                        }
                    }
                    decoded.append(next);
                    i++;
                    continue;
                }
                if (ch == '\n' || ch == '\r' || ch == '\t') {
                    decoded.append(' ');
                    continue;
                }
                decoded.append(ch);
            }

            return decoded.toString().replace("\\012", " ")
                    .replace("\\015", " ")
                    .replace("\\000", " ")
                    .trim();
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

        List<String> suspiciousTokens = findSuspiciousSegments(text);
        double ratio = words.isEmpty() ? 0.0 : (double) suspiciousTokens.size() / words.size();
        String quality = requiresReview(words.size(), ilegiveis, suspiciousTokens.size(), ratio) ? "REVISAR" : "OK";

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

    public static List<String> findSuspiciousSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        Set<String> suspiciousTokens = new HashSet<>();
        Matcher suspiciousMatcher = SUSPICIOUS_TOKEN_PATTERN.matcher(text);
        while (suspiciousMatcher.find()) {
            String token = suspiciousMatcher.group();
            String normalized = token.toLowerCase(Locale.ROOT);
            if (shouldFlagAsSuspicious(normalized)) {
                suspiciousTokens.add(normalized);
            }
        }

        Matcher wordMatcher = WORD_PATTERN.matcher(text);
        while (wordMatcher.find()) {
            String token = wordMatcher.group();
            String normalized = token.toLowerCase(Locale.ROOT);
            if (shouldFlagAsSuspicious(normalized)) {
                suspiciousTokens.add(normalized);
            }
        }

        return new ArrayList<>(suspiciousTokens);
    }

    private static boolean requiresReview(int wordCount, int illegibleCount, int suspiciousCount, double suspiciousRatio) {
        if (illegibleCount > 0) {
            return true;
        }

        if (wordCount <= 0) {
            return false;
        }

        int absoluteThreshold = (wordCount < 80) ? 2 : (wordCount < 200) ? 3 : (wordCount < 500) ? 5 : 6;
        double ratioThreshold = (wordCount < 80) ? 0.12 : (wordCount < 200) ? 0.08 : (wordCount < 500) ? 0.04 : 0.025;

        return suspiciousCount >= absoluteThreshold || suspiciousRatio > ratioThreshold;
    }

    private static boolean shouldFlagAsSuspicious(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }

        String normalized = token.replaceAll("[^\\p{L}\\p{N}]", "").toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            return false;
        }

        if (token.contains("[") || token.contains("]") || token.contains("?")) {
            return true;
        }

        String keyboardNoise = "(?i)(?:qwe|asd|zxc|qaz|wsx|edc|rfv|tgb|yhn|ujm|mnb|vbn|poi|lkj|qwerty|asdf|zxcv|uiop|lkj|mnbv|poiu|qazwsx|yuiop)";
        if (normalized.matches(keyboardNoise)) {
            return true;
        }

        if (normalized.matches(".*(.)\\1{2,}.*")) {
            return true;
        }

        long vowelCount = normalized.chars()
                .filter(ch -> "aeiou".indexOf(ch) >= 0)
                .count();
        if (normalized.length() >= 3 && vowelCount == 0) {
            return true;
        }

        if (normalized.matches("(?i)(?:[bcdfghjklmnpqrstvwxyz]{4,}|[aeiou]{4,})")) {
            return true;
        }

        if (normalized.length() >= 12) {
            return true;
        }
        return false;
    }
}
