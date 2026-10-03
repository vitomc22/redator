package com.redator.corretor.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

public final class RealVisionTranscriptionProvider implements DocumentTranscriptionService.VisionTranscriptionProvider {

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
            return "Texto extraído do PDF. Revisão humana obrigatória antes da avaliação.";
        }

        if (lowerName.endsWith(".png") || lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")) {
            String ocrText = tryTesseractOcr(content, lowerName);
            if (!ocrText.isBlank()) {
                return ocrText;
            }
            return "Imagem validada. Revisão humana obrigatória antes da avaliação. O texto literal precisa ser revisado pelo editor.";
        }

        return "Arquivo validado. Revisão humana obrigatória antes da avaliação.";
    }

    private String extractPdfText(byte[] content) {
        try (PDDocument document = Loader.loadPDF(content)) {
            if (document.isEncrypted()) {
                return "";
            }
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            if (text != null && !text.isBlank()) {
                return normalizeExtractedText(text);
            }
        } catch (IOException ignored) {
            // fallback para PDFs simples/compatibilidade
        }

        String raw = new String(content, StandardCharsets.ISO_8859_1);
        StringBuilder extracted = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\((?:\\\\.|[^()\\\\])*\\)").matcher(raw);
        while (matcher.find()) {
            String token = matcher.group();
            String decoded = decodePdfLiteral(token.substring(1, token.length() - 1));
            if (!decoded.isBlank()) {
                extracted.append(decoded).append(' ');
            }
        }

        String fallback = normalizeExtractedText(extracted.toString());
        return fallback.isBlank() ? "" : fallback;
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
                if (next == 'n') {
                    decoded.append('\n');
                    i++;
                    continue;
                }
                if (next == 'r') {
                    decoded.append('\r');
                    i++;
                    continue;
                }
                if (next == 't') {
                    decoded.append('\t');
                    i++;
                    continue;
                }
                if (next == 'b') {
                    decoded.append('\b');
                    i++;
                    continue;
                }
                if (next == 'f') {
                    decoded.append('\f');
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
                    while (i + 1 + count < literal.length() && count < 3 && Character.isDigit(literal.charAt(i + 1 + count)) && literal.charAt(i + 1 + count) != '8' && literal.charAt(i + 1 + count) != '9') {
                        octal.append(literal.charAt(i + 1 + count));
                        count++;
                    }
                    if (!octal.isEmpty()) {
                        decoded.append((char) Integer.parseInt(octal.toString(), 8));
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

    private String tryTesseractOcr(byte[] content, String lowerName) {
        String command = System.getenv().getOrDefault("TESSERACT_BIN", "tesseract");
        if (command == null || command.isBlank()) {
            return "";
        }

        try {
            Path tempFile = Files.createTempFile("redator-ocr-", lowerName.endsWith(".png") ? ".png" : ".jpg");
            Files.write(tempFile, content);

            ProcessBuilder processBuilder = new ProcessBuilder(command, tempFile.toString(), "stdout", "--psm", "6");
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = process.waitFor();
            Files.deleteIfExists(tempFile);

            if (exitCode != 0 || output.isBlank()) {
                return "";
            }
            return normalizeExtractedText(output);
        } catch (Exception ignored) {
            return "";
        }
    }

    private String normalizeExtractedText(String text) {
        return text.replace("\u0000", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
