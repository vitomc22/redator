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
    private static final List<String> ALLOWED_SPLITS = List.of("dev", "validation", "test");

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
        var ids = new java.util.HashSet<Object>();
        var groups = new java.util.HashMap<String, Object>();
        var hashes = new java.util.HashSet<String>();
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
            requireText(entry, "themeGroup", prefix, errors);
            if (!ids.add(entry.get("id"))) errors.add(prefix + " id duplicado");
            Object split = entry.get("split");
            if (!(split instanceof String) || !ALLOWED_SPLITS.contains(split)) {
                errors.add(prefix + ".split deve ser dev, validation ou test");
            }
            for (String field : List.of("themeGroup", "duplicateGroup", "authorGroup")) {
                Object group = entry.get(field);
                if (group instanceof String s && !s.isBlank()) {
                    Object previous = groups.putIfAbsent(field + ":" + s, split);
                    if (previous != null && !previous.equals(split)) errors.add(prefix + " " + field + " atravessa splits");
                }
            }
            if (entry.get("humanScores") != null) validateLabels(entry, prefix, errors);

            boolean authorizedLocalText = Boolean.TRUE.equals(entry.get("authorizedLocalText"));
            Object textPath = entry.get("textPath");
            Object sha256 = entry.get("sha256");
            if (authorizedLocalText) {
                authorized++;
                if (!List.of("authorized", "owned", "public-domain").contains(entry.get("rightsStatus"))) errors.add(prefix + " direitos nao autorizados");
                if (!(entry.get("allowedUses") instanceof List<?> uses) || !uses.contains("evaluation")) errors.add(prefix + " allowedUses exige evaluation");
                requireText(entry, "rightsEvidence", prefix, errors);
                if (!(textPath instanceof String path) || path.isBlank()) {
                    errors.add(prefix + ".textPath e obrigatorio para texto autorizado");
                } else if (!path.startsWith("dataset/private/")) {
                    errors.add(prefix + ".textPath deve permanecer em dataset/private/");
                }
                if (!(sha256 instanceof String hash) || !hash.matches("[a-fA-F0-9]{64}")) {
                    errors.add(prefix + ".sha256 deve conter 64 caracteres hexadecimais");
                }
                try {
                    byte[] bytes = readVerifiedText(manifestPath, entry);
                    String hash = sha256(bytes);
                    if (!hashes.add(hash)) errors.add(prefix + " texto duplicado por hash");
                    String normalized = new String(bytes, StandardCharsets.UTF_8).toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ").strip();
                    Object previous = groups.putIfAbsent("normalized:" + sha256(normalized.getBytes(StandardCharsets.UTF_8)), split);
                    if (previous != null) errors.add(prefix + " copia normalizada duplicada; revisar similaridade");
                } catch (Exception ex) { errors.add(prefix + " texto: " + ex.getMessage()); }
                if (eligible(entry)) {
                    validateLabels(entry, prefix, errors);
                    requireText(entry, "rubricVersion", prefix, errors);
                    if (!labelHash(entry).equals(entry.get("labelRevisionSha256"))) errors.add(prefix + " hash da revisao dos rotulos inconsistente");
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

    public static boolean eligible(Map<?, ?> entry) {
        return Boolean.TRUE.equals(entry.get("authorizedLocalText"))
                && "reviewed".equals(entry.get("reviewStatus"))
                && Boolean.TRUE.equals(entry.get("originalExtractionReviewed"))
                && "reviewed".equals(entry.get("labelQuality"))
                && entry.get("exclusionReason") == null;
    }

    public static String labelHash(Map<?, ?> entry) {
        return sha256((String.valueOf(entry.get("humanScores")) + "|" + entry.get("humanTotal") + "|"
                + entry.get("labelSource") + "|" + entry.get("rubricVersion") + "|" + entry.get("globalOutcome")).getBytes(StandardCharsets.UTF_8));
    }

    private static void validateLabels(Map<?, ?> entry, String prefix, List<String> errors) {
        if (!"human".equals(entry.get("evaluatorType"))) errors.add(prefix + " evaluatorType deve ser human, nunca IA");
        if (!"human".equals(entry.get("labelSource"))) errors.add(prefix + " labelSource deve identificar tabela human");
        if (!List.of("individual", "published-average").contains(entry.get("scoreScale"))) errors.add(prefix + " escala desconhecida");
        Object scores = entry.get("humanScores");
        if (!(scores instanceof List<?> values) || values.size() != 5) { errors.add(prefix + " exige cinco notas humanas"); return; }
        int sum = 0;
        for (Object value : values) {
            if (!(value instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() < 0 || n.intValue() > 200
                    || ("individual".equals(entry.get("scoreScale")) && n.intValue() % 40 != 0)) {
                errors.add(prefix + " nota fora da escala"); return;
            }
            sum += ((Number) value).intValue();
        }
        if (!(entry.get("humanTotal") instanceof Number total) || total.doubleValue() != sum) errors.add(prefix + " soma humana inconsistente");
    }

    /** Paths are relative to the repository containing dataset/manifest.json. No links in any component. */
    public static byte[] readVerifiedText(Path manifest, Map<?, ?> entry) throws IOException {
        Path root = manifest.toAbsolutePath().normalize().getParent().getParent();
        String name = String.valueOf(entry.get("textPath"));
        Path relative = Path.of(name);
        if (relative.isAbsolute() || !relative.startsWith(Path.of("dataset/private"))) throw new IOException("caminho fora de dataset/private");
        for (Path part : relative) if (part.toString().equals("..") || part.toString().equals(".")) throw new IOException("traversal proibido");
        Path file = root.resolve(relative);
        Path cursor = file.getRoot();
        for (Path part : file) { cursor = cursor.resolve(part); if (Files.isSymbolicLink(cursor)) throw new IOException("symlink proibido"); }
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw new IOException("arquivo inexistente");
        byte[] bytes = Files.readAllBytes(file);
        if (!sha256(bytes).equalsIgnoreCase(String.valueOf(entry.get("sha256")))) throw new IOException("sha256 difere dos bytes reais");
        try { StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)); }
        catch (java.nio.charset.CharacterCodingException ex) { throw new IOException("texto nao UTF-8", ex); }
        if (new String(bytes, StandardCharsets.UTF_8).isBlank()) throw new IOException("texto vazio");
        return bytes;
    }

    public static String sha256(byte[] bytes) {
        try { return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static void requireText(Map<?, ?> entry, String field, String prefix, List<String> errors) {
        Object value = entry.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            errors.add(prefix + "." + field + " e obrigatorio");
        }
    }
}
