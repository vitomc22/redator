package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.sqlite.SQLiteConfig;

/** Freezes both expert annotations without rounding or filtering by their mean. */
public final class TwoRaterBenchmarkExporter {
    public static final String LABEL_POLICY = "individual-raters-macro-average-v1";
    private static final ObjectMapper JSON = new ObjectMapper();

    public Map<String, Object> export(Path database, String split, int limit, Set<String> excludedHashes) throws Exception {
        if (!List.of("validation", "test").contains(split))
            throw new IllegalArgumentException("benchmark exige validation ou test");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit deve ficar entre 1 e 10000");
        if (!Files.isRegularFile(database)) throw new IllegalArgumentException("corpus não encontrado");
        Set<String> excluded = excludedHashes == null ? Set.of() : Set.copyOf(excludedHashes);
        for (String hash : excluded) {
            if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("hash excluído inválido");
        }
        String before = fingerprint(database);
        Map<String, Map<String, Object>> essays = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> annotations = new LinkedHashMap<>();
        var configuration = new SQLiteConfig();
        configuration.setReadOnly(true);
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath(), configuration.toProperties());
             var statement = connection.prepareStatement("""
                     SELECT e.essay_hash, e.source, e.subset_name, e.source_id, e.theme, e.essay_text, e.license_spdx,
                            h.grader_id, h.c1, h.c2, h.c3, h.c4, h.c5, h.total
                     FROM public_essay e JOIN public_human_evaluation h ON h.essay_id=e.id
                     WHERE e.split_name=? AND e.source=? AND e.subset_name=?
                       AND h.eligible_for_benchmark=1 AND h.human_verified=1 AND h.evaluator_type='human'
                     ORDER BY e.essay_hash, h.grader_id, h.id
                     """)) {
            statement.setString(1, split);
            statement.setString(2, KamelDatasetImporter.DATASET);
            statement.setString(3, "JBCS2025");
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    String hash = rows.getString("essay_hash");
                    if (excluded.contains(hash)) continue;
                    String text = rows.getString("essay_text");
                    if (!PublicHumanCorpus.hashText(text).equals(hash))
                        throw new IllegalArgumentException("essayHash não corresponde ao texto do corpus");
                    if (!essays.containsKey(hash)) {
                        Map<String, Object> sample = new LinkedHashMap<>();
                        sample.put("essayHash", hash); sample.put("source", rows.getString("source"));
                        sample.put("sourceId", rows.getString("source_id")); sample.put("license", rows.getString("license_spdx"));
                        sample.put("theme", rows.getString("theme")); sample.put("text", text);
                        essays.put(hash, sample); annotations.put(hash, new TreeMap<>());
                    }
                    List<Integer> scores = new ArrayList<>();
                    for (String code : List.of("c1", "c2", "c3", "c4", "c5")) scores.add(officialScore(rows.getObject(code)));
                    int total = scores.stream().mapToInt(Integer::intValue).sum();
                    Object storedTotal = rows.getObject("total");
                    if (!(storedTotal instanceof Number number) || number.doubleValue() != total)
                        throw new IllegalArgumentException("total humano difere da soma C1–C5");
                    String grader = rows.getString("grader_id");
                    if (grader == null || grader.isBlank()) throw new IllegalArgumentException("avaliador humano ausente");
                    Map<String, Object> rating = new LinkedHashMap<>();
                    rating.put("graderId", grader); rating.put("scores", List.copyOf(scores)); rating.put("total", total);
                    if (annotations.get(hash).putIfAbsent(grader, rating) != null)
                        throw new IllegalArgumentException("avaliador duplicado na mesma redação");
                }
            }
        }
        List<String> raters = null;
        List<Map<String, Object>> samples = new ArrayList<>();
        for (var entry : essays.entrySet()) {
            var ratings = annotations.get(entry.getKey());
            if (ratings.size() != 2) throw new IllegalArgumentException("cada redação exige dois avaliadores humanos distintos");
            var panel = List.copyOf(ratings.keySet());
            if (raters == null) raters = panel;
            else if (!raters.equals(panel)) throw new IllegalArgumentException("painel de avaliadores varia entre redações");
            entry.getValue().put("humanEvaluations", List.copyOf(ratings.values()));
            if (samples.size() < limit) samples.add(entry.getValue());
        }
        if (samples.isEmpty()) throw new IllegalArgumentException("split não possui redações elegíveis após exclusões");
        if (!before.equals(fingerprint(database))) throw new IllegalStateException("corpus alterado durante a exportação");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schemaVersion", 2); result.put("humanLabelPolicy", LABEL_POLICY);
        result.put("corpusFingerprint", before);
        result.put("selectionFingerprint", DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples)));
        result.put("split", split); result.put("sampleCount", samples.size()); result.put("annotationCount", samples.size() * 2);
        result.put("raterIds", raters); result.put("excludedEssayHashes", excluded.stream().sorted().toList());
        result.put("source", KamelDatasetImporter.DATASET); result.put("subset", "JBCS2025"); result.put("samples", samples);
        return result;
    }

    private static int officialScore(Object raw) {
        if (!(raw instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 0 || number.intValue() > 200 || number.intValue() % 40 != 0)
            throw new IllegalArgumentException("nota humana fora da escala oficial de seis níveis");
        return number.intValue();
    }

    private static String fingerprint(Path database) throws Exception {
        try (var corpus = new PublicHumanCorpus(database)) { return corpus.fingerprint(); }
    }
}
