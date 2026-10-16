package com.redator.corretor.service;

import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;

/** Importador reprodutível do Extended Essay-BR, com split congelado por tema. */
public final class ExtendedEssayBrImporter {
    public static final String SOURCE = "lplnufpi/essay-br";
    public static final String SOURCE_URL = "https://github.com/" + SOURCE;
    public static final String LICENSE = "MIT";
    public static final String LICENSE_URL = SOURCE_URL + "/blob/main/LICENSE";
    private static final String ESSAYS = "extended-corpus/extended_essay-br.csv";
    private static final String PROMPTS = "extended-corpus/prompts.csv";
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();

    public record Result(String revision, int skippedRows, int duplicateRows, PublicHumanCorpus.ImportResult imported) { }
    public record Mapping(List<PublicHumanCorpus.ImportEntry> entries, int skippedRows) { }

    public Result importAll(PublicHumanCorpus corpus) throws Exception {
        String revision = revision();
        String base = "https://raw.githubusercontent.com/" + SOURCE + "/" + revision + "/";
        Mapping mapping = mapDetailed(download(base + ESSAYS), download(base + PROMPTS));
        Map<String, PublicHumanCorpus.ImportEntry> unique = new java.util.LinkedHashMap<>();
        int duplicates = 0;
        for (var entry : mapping.entries()) {
            String hash = PublicHumanCorpus.hashText(entry.essayText());
            if (corpus.findByText(entry.essayText()) != null || unique.putIfAbsent(hash, entry) != null) duplicates++;
        }
        return new Result(revision, mapping.skippedRows(), duplicates,
                corpus.importEntries(List.copyOf(unique.values()), revision));
    }

    public List<PublicHumanCorpus.ImportEntry> map(String essayCsv, String promptCsv) throws IOException {
        return mapDetailed(essayCsv, promptCsv).entries();
    }

    public Mapping mapDetailed(String essayCsv, String promptCsv) throws IOException {
        Map<String, String> prompts = new HashMap<>();
        for (CSVRecord row : parse(promptCsv)) {
            prompts.put(row.get("id"), row.get("title") + "\n\n" + row.get("description"));
        }
        List<PublicHumanCorpus.ImportEntry> entries = new ArrayList<>();
        int skipped = 0;
        int index = 0;
        for (CSVRecord row : parse(essayCsv)) {
            String promptId = row.get("prompt");
            String prompt = prompts.get(promptId);
            if (prompt == null || prompt.isBlank()) throw new IllegalArgumentException("prompt inexistente: " + promptId);
            String essay = parsePythonStringList(row.get("essay"));
            if (essay.isBlank()) { skipped++; continue; }
            int c1 = integer(row, "c1"), c2 = integer(row, "c2"), c3 = integer(row, "c3");
            int c4 = integer(row, "c4"), c5 = integer(row, "c5"), total = integer(row, "score");
            entries.add(new PublicHumanCorpus.ImportEntry(
                    SOURCE, "extended-corpus", "extended:" + index++, splitForTheme(promptId),
                    "extended-prompt-" + promptId, prompt, essay, null, LICENSE, LICENSE_URL, SOURCE_URL,
                    new PublicHumanCorpus.HumanScore("published", c1, c2, c3, c4, c5, total)
            ));
        }
        return new Mapping(List.copyOf(entries), skipped);
    }

    public static String splitForTheme(String promptId) {
        String hash = DatasetManifestValidator.sha256(("extended-prompt-" + promptId)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        long bucket = Long.parseUnsignedLong(hash.substring(0, 8), 16);
        int percentile = (int) (bucket % 100);
        return percentile < 70 ? "train" : percentile < 85 ? "validation" : "test";
    }

    public static String parsePythonStringList(String value) {
        if (value == null || value.length() < 2 || value.charAt(0) != '[' || value.charAt(value.length() - 1) != ']')
            throw new IllegalArgumentException("essay nao e uma lista Python");
        List<String> parts = new ArrayList<>();
        StringBuilder current = null;
        boolean escaped = false;
        char quote = 0;
        for (int i = 1; i < value.length() - 1; i++) {
            char ch = value.charAt(i);
            if (current == null) {
                if (Character.isWhitespace(ch) || ch == ',') continue;
                if (ch != '\'' && ch != '"') throw new IllegalArgumentException("lista Python possui item nao textual");
                quote = ch;
                current = new StringBuilder();
            } else if (escaped) {
                current.append(switch (ch) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> ch; });
                escaped = false;
            } else if (ch == '\\') {
                escaped = true;
            } else if (ch == quote) {
                parts.add(current.toString().strip());
                current = null;
                quote = 0;
            } else {
                current.append(ch);
            }
        }
        if (current != null) throw new IllegalArgumentException("lista Python incompleta");
        if (parts.isEmpty()) return "";
        return String.join("\n\n", parts).strip();
    }

    private static Iterable<CSVRecord> parse(String csv) throws IOException {
        return CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build()
                .parse(new StringReader(csv));
    }

    private static int integer(CSVRecord row, String field) {
        try { return Integer.parseInt(row.get(field)); }
        catch (RuntimeException exception) { throw new IllegalArgumentException("valor invalido em " + field, exception); }
    }

    private String revision() throws Exception {
        String json = download("https://api.github.com/repos/" + SOURCE + "/commits/main");
        var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        String sha = node.path("sha").asText();
        if (!sha.matches("[0-9a-f]{40}")) throw new IOException("GitHub nao informou revisao valida");
        return sha;
    }

    private String download(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(90))
                .header("Accept", "text/csv, application/json").header("User-Agent", "redator-enem-open-source/0.1").GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) throw new IOException("HTTP " + response.statusCode() + " ao baixar " + url);
        return response.body();
    }
}
