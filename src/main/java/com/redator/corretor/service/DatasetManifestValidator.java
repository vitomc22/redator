package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class DatasetManifestValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> ALLOWED_SPLITS = List.of("dev", "test");

    private DatasetManifestValidator() {
    }

    public record Result(int entries, int metadataOnly, int authorizedLocalTexts, List<String> errors) {
        public boolean valid() {
            return errors.isEmpty() && entries > 0;
        }
    }

    public static Result validate(Path manifestPath) throws IOException {
        Object root = MAPPER.readValue(Files.readString(manifestPath, StandardCharsets.UTF_8), Object.class);
        if (!(root instanceof Map<?, ?> manifest)) {
            return new Result(0, 0, 0, List.of("manifesto deve ser um objeto JSON"));
        }
        Object entriesValue = manifest.get("entries");
        if (!(entriesValue instanceof List<?> entries)) {
            return new Result(0, 0, 0, List.of("manifesto deve possuir a lista 'entries'"));
        }

        List<String> errors = new ArrayList<>();
        int metadataOnly = 0;
        int authorized = 0;
        for (int index = 0; index < entries.size(); index++) {
            Object value = entries.get(index);
            String prefix = "entries[" + index + "]";
            if (!(value instanceof Map<?, ?> entry)) {
                errors.add(prefix + " deve ser um objeto");
                continue;
            }
            requireText(entry, "id", prefix, errors);
            requireText(entry, "theme", prefix, errors);
            requireText(entry, "source", prefix, errors);
            requireText(entry, "sourceUrl", prefix, errors);
            Object split = entry.get("split");
            if (!(split instanceof String) || !ALLOWED_SPLITS.contains(split)) {
                errors.add(prefix + ".split deve ser dev ou test");
            }

            boolean authorizedLocalText = Boolean.TRUE.equals(entry.get("authorizedLocalText"));
            Object textPath = entry.get("textPath");
            Object sha256 = entry.get("sha256");
            if (authorizedLocalText) {
                authorized++;
                if (!(textPath instanceof String path) || path.isBlank()) {
                    errors.add(prefix + ".textPath e obrigatorio para texto autorizado");
                } else if (!path.startsWith("dataset/private/")) {
                    errors.add(prefix + ".textPath deve permanecer em dataset/private/");
                }
                if (!(sha256 instanceof String hash) || !hash.matches("[a-fA-F0-9]{64}")) {
                    errors.add(prefix + ".sha256 deve conter 64 caracteres hexadecimais");
                }
            } else {
                metadataOnly++;
                if (textPath != null || sha256 != null) {
                    errors.add(prefix + " metadata-only nao pode declarar textPath ou sha256");
                }
            }
        }
        return new Result(entries.size(), metadataOnly, authorized, List.copyOf(errors));
    }

    private static void requireText(Map<?, ?> entry, String field, String prefix, List<String> errors) {
        Object value = entry.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(prefix + "." + field + " e obrigatorio");
        }
    }
}
