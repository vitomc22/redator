package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class KamelDatasetImporter {

    public static final String DATASET = "kamel-usp/aes_enem_dataset";
    public static final String SOURCE_URL = "https://huggingface.co/datasets/" + DATASET;
    public static final String LICENSE = "Apache-2.0";
    public static final String LICENSE_URL = SOURCE_URL + "/blob/main/README.md";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final HttpClient client;

    public KamelDatasetImporter() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    KamelDatasetImporter(HttpClient client) {
        this.client = client;
    }

    public record DownloadResult(String revision, int downloaded, PublicHumanCorpus.ImportResult imported) {
    }

    public DownloadResult importSplit(PublicHumanCorpus corpus, String config, String split, int pageSize)
            throws IOException, InterruptedException, java.sql.SQLException {
        if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("pageSize deve ficar entre 1 e 100");
        String revision = fetchRevision();
        int offset = 0;
        int downloaded = 0;
        int insertedEssays = 0;
        int insertedEvaluations = 0;
        int duplicates = 0;
        while (true) {
            Map<String, Object> payload = getJson(rowsUrl(config, split, offset, pageSize));
            List<Map<String, Object>> rows = extractRows(payload);
            if (rows.isEmpty()) break;
            List<PublicHumanCorpus.ImportEntry> entries = mapRows(config, split, rows, offset);
            var result = corpus.importEntries(entries, revision);
            downloaded += rows.size();
            insertedEssays += result.insertedEssays();
            insertedEvaluations += result.insertedEvaluations();
            duplicates += result.duplicates();
            offset += rows.size();
            Number total = (Number) payload.get("num_rows_total");
            if (total == null || offset >= total.intValue()) break;
        }
        return new DownloadResult(revision, downloaded,
                new PublicHumanCorpus.ImportResult(downloaded, insertedEssays, insertedEvaluations, duplicates));
    }

    public List<PublicHumanCorpus.ImportEntry> mapRows(
            String config, String split, List<Map<String, Object>> rows, int offset
    ) {
        List<PublicHumanCorpus.ImportEntry> result = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Map<String, Object> row = rows.get(index);
            String id = text(row, "id", String.valueOf(offset + index));
            String themeGroup = text(row, "id_prompt", "prompt-" + id);
            String prompt = text(row, "prompt", themeGroup.replace('-', ' '));
            String reference = text(row, "reference", "published");
            List<Integer> grades = integerList(row.get("grades"));
            if (grades.size() != 6) throw new IllegalArgumentException("grades exige C1-C5 e total na linha " + id);
            Number yearValue = row.get("essay_year") instanceof Number number ? number : null;
            result.add(new PublicHumanCorpus.ImportEntry(
                    DATASET,
                    config,
                    config + ":" + split + ":" + id,
                    split,
                    themeGroup,
                    prompt,
                    text(row, "essay_text", null),
                    yearValue == null ? null : yearValue.intValue(),
                    LICENSE,
                    LICENSE_URL,
                    SOURCE_URL,
                    new PublicHumanCorpus.HumanScore(reference,
                            grades.get(0), grades.get(1), grades.get(2), grades.get(3), grades.get(4), grades.get(5))
            ));
        }
        return result;
    }

    private String fetchRevision() throws IOException, InterruptedException {
        Map<String, Object> metadata = getJson("https://huggingface.co/api/datasets/" + DATASET);
        Object sha = metadata.get("sha");
        if (!(sha instanceof String value) || value.isBlank()) {
            throw new IOException("Hugging Face nao informou a revisao do dataset");
        }
        return value;
    }

    private Map<String, Object> getJson(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("User-Agent", "redator-enem-open-source/0.1")
                .GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Hugging Face respondeu HTTP " + response.statusCode() + " para " + url);
        }
        return MAPPER.readValue(response.body(), new TypeReference<>() { });
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractRows(Map<String, Object> payload) {
        Object value = payload.get("rows");
        if (!(value instanceof List<?> wrappers)) return List.of();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object wrapper : wrappers) {
            if (wrapper instanceof Map<?, ?> map && map.get("row") instanceof Map<?, ?> row) {
                rows.add((Map<String, Object>) row);
            }
        }
        return rows;
    }

    private static String rowsUrl(String config, String split, int offset, int pageSize) {
        return "https://datasets-server.huggingface.co/rows?dataset=" + encode(DATASET)
                + "&config=" + encode(config) + "&split=" + encode(split)
                + "&offset=" + offset + "&length=" + pageSize;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String text(Map<String, Object> row, String key, String fallback) {
        Object value = row.get(key);
        String text = value == null ? fallback : String.valueOf(value).strip();
        if (text == null || text.isBlank()) throw new IllegalArgumentException(key + " ausente no dataset KAMeL");
        return text;
    }

    private static List<Integer> integerList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Integer> result = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Number number) || number.doubleValue() != number.intValue()) return List.of();
            result.add(number.intValue());
        }
        return result;
    }
}
