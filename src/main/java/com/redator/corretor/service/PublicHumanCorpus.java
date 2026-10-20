package com.redator.corretor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/**
 * Catálogo público de redações e avaliações humanas, fisicamente separado do banco
 * operacional dos alunos. Splits reservados nunca são elegíveis para recuperação.
 */
public final class PublicHumanCorpus implements AutoCloseable {

    public record HumanScore(String graderId, int c1, int c2, int c3, int c4, int c5, int total) {
        public HumanScore {
            if (graderId == null || graderId.isBlank()) throw new IllegalArgumentException("graderId obrigatorio");
            int[] scores = {c1, c2, c3, c4, c5};
            int sum = 0;
            for (int score : scores) {
                if (score < 0 || score > 200 || score % 20 != 0) {
                    throw new IllegalArgumentException("nota deve estar entre 0 e 200 e ser multipla de 20");
                }
                sum += score;
            }
            if (total != sum) throw new IllegalArgumentException("total difere da soma C1-C5");
        }
    }

    public record ImportEntry(
            String source,
            String subset,
            String sourceId,
            String split,
            String themeGroup,
            String theme,
            String essayText,
            Integer year,
            String licenseSpdx,
            String licenseUrl,
            String sourceUrl,
            HumanScore score
    ) {
        public ImportEntry {
            require(source, "source");
            require(subset, "subset");
            require(sourceId, "sourceId");
            require(theme, "theme");
            require(essayText, "essayText");
            require(licenseSpdx, "licenseSpdx");
            require(licenseUrl, "licenseUrl");
            require(sourceUrl, "sourceUrl");
            split = normalizeSplit(split);
            themeGroup = themeGroup == null || themeGroup.isBlank()
                    ? DatasetManifestValidator.sha256(normalize(theme).getBytes(java.nio.charset.StandardCharsets.UTF_8))
                    : themeGroup;
            Objects.requireNonNull(score, "score");
        }
    }

    public record Example(
            long essayId,
            String essayHash,
            String source,
            String subset,
            String sourceId,
            String split,
            String theme,
            String essayText,
            String licenseSpdx,
            String graderId,
            List<Integer> scores,
            int total,
            double relevance
    ) {
    }

    public record ImportResult(int received, int insertedEssays, int insertedEvaluations, int duplicates) {
    }

    public record ExistingEssay(String essayHash, String split, String themeGroup) { }

    private final Connection connection;

    public PublicHumanCorpus(Path database) throws SQLException, IOException {
        Path absolute = database.toAbsolutePath().normalize();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + absolute);
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = ON");
            statement.execute("PRAGMA journal_mode = WAL");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public_essay (
                      id INTEGER PRIMARY KEY,
                      source TEXT NOT NULL,
                      subset_name TEXT NOT NULL,
                      source_id TEXT NOT NULL,
                      essay_hash TEXT NOT NULL UNIQUE,
                      near_signature INTEGER NOT NULL,
                      split_name TEXT NOT NULL CHECK (split_name IN ('train','validation','test')),
                      theme_group TEXT NOT NULL,
                      theme TEXT NOT NULL,
                      essay_text TEXT NOT NULL,
                      essay_year INTEGER,
                      license_spdx TEXT NOT NULL,
                      license_url TEXT NOT NULL,
                      source_url TEXT NOT NULL,
                      imported_at TEXT NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS public_human_evaluation (
                      id INTEGER PRIMARY KEY,
                      essay_id INTEGER NOT NULL REFERENCES public_essay(id) ON DELETE CASCADE,
                      grader_id TEXT NOT NULL,
                      c1 INTEGER NOT NULL, c2 INTEGER NOT NULL, c3 INTEGER NOT NULL,
                      c4 INTEGER NOT NULL, c5 INTEGER NOT NULL, total INTEGER NOT NULL,
                      evaluator_type TEXT NOT NULL DEFAULT 'human' CHECK (evaluator_type = 'human'),
                      human_verified INTEGER NOT NULL DEFAULT 1,
                      eligible_for_rag INTEGER NOT NULL,
                      eligible_for_benchmark INTEGER NOT NULL,
                      eligible_for_training INTEGER NOT NULL,
                      UNIQUE (essay_id, grader_id, c1, c2, c3, c4, c5)
                    )
                    """);
            statement.execute("CREATE INDEX IF NOT EXISTS idx_public_essay_split ON public_essay(split_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_public_eval_rag ON public_human_evaluation(eligible_for_rag)");
            statement.execute("""
                    CREATE TABLE IF NOT EXISTS corpus_import (
                      id INTEGER PRIMARY KEY,
                      source TEXT NOT NULL,
                      subset_name TEXT NOT NULL,
                      corpus_version TEXT NOT NULL,
                      license_spdx TEXT NOT NULL,
                      imported_at TEXT NOT NULL,
                      UNIQUE(source, subset_name, corpus_version)
                    )
                    """);
        }
        ensureNearSignatureColumn();
    }

    private void ensureNearSignatureColumn() throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement(); ResultSet columns = statement.executeQuery("PRAGMA table_info(public_essay)")) {
            while (columns.next()) if ("near_signature".equals(columns.getString("name"))) present = true;
        }
        if (!present) try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE public_essay ADD COLUMN near_signature INTEGER NOT NULL DEFAULT 0");
        }
        try (PreparedStatement select = connection.prepareStatement("SELECT id, essay_text FROM public_essay WHERE near_signature=0");
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement("UPDATE public_essay SET near_signature=? WHERE id=?")) {
            while (rows.next()) {
                update.setLong(1, nearSignature(rows.getString(2)));
                update.setLong(2, rows.getLong(1));
                update.addBatch();
            }
            update.executeBatch();
        }
    }

    private void assertNoNearDuplicateAcrossSplits(ImportEntry entry) throws SQLException {
        long signature = nearSignature(entry.essayText());
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source_id, split_name, near_signature FROM public_essay WHERE split_name<>?")) {
            statement.setString(1, entry.split());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    if (Long.bitCount(signature ^ rows.getLong("near_signature")) <= 8) {
                        throw new IllegalArgumentException("redacao quase duplicada atravessa splits: "
                                + entry.sourceId() + " / " + rows.getString("source_id"));
                    }
                }
            }
        }
    }

    public ImportResult importEntries(List<ImportEntry> entries, String corpusVersion) throws SQLException {
        if (entries == null || entries.isEmpty()) throw new IllegalArgumentException("nenhuma entrada para importar");
        require(corpusVersion, "corpusVersion");
        int essays = 0;
        int evaluations = 0;
        int duplicates = 0;
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            for (ImportEntry entry : entries) {
                String hash = hashText(entry.essayText());
                Long essayId = findEssayId(hash);
                if (essayId == null) {
                    assertNoNearDuplicateAcrossSplits(entry);
                    essayId = insertEssay(entry, hash);
                    essays++;
                } else {
                    assertSameSplitAndTheme(essayId, entry);
                }
                if (insertEvaluation(essayId, entry)) evaluations++; else duplicates++;
                recordImport(entry, corpusVersion);
            }
            connection.commit();
            return new ImportResult(entries.size(), essays, evaluations, duplicates);
        } catch (SQLException | RuntimeException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    public List<Example> retrieve(String query, String theme, int limit, List<String> excludedHashes) throws SQLException {
        if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit deve ficar entre 1 e 20");
        List<String> terms = terms(query + " " + (theme == null ? "" : theme));
        var excluded = excludedHashes == null ? java.util.Set.<String>of() : java.util.Set.copyOf(excludedHashes);
        List<Example> candidates = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, e.essay_hash, e.source, e.subset_name, e.source_id, e.split_name,
                       e.theme, e.essay_text, e.license_spdx, COUNT(h.id) grader_count,
                       CAST(ROUND(AVG(h.c1)) AS INTEGER) c1,
                       CAST(ROUND(AVG(h.c2)) AS INTEGER) c2,
                       CAST(ROUND(AVG(h.c3)) AS INTEGER) c3,
                       CAST(ROUND(AVG(h.c4)) AS INTEGER) c4,
                       CAST(ROUND(AVG(h.c5)) AS INTEGER) c5
                FROM public_essay e
                JOIN public_human_evaluation h ON h.essay_id = e.id
                WHERE h.eligible_for_rag = 1 AND e.split_name = 'train'
                GROUP BY e.id
                ORDER BY e.id
                """)) {
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String hash = rows.getString("essay_hash");
                    if (excluded.contains(hash)) continue;
                    String rowTheme = rows.getString("theme");
                    String text = rows.getString("essay_text");
                    double relevance = relevance(terms, theme, rowTheme, text);
                    if (!terms.isEmpty() && relevance <= 0) continue;
                    List<Integer> scores = List.of(rows.getInt("c1"), rows.getInt("c2"), rows.getInt("c3"),
                            rows.getInt("c4"), rows.getInt("c5"));
                    candidates.add(new Example(
                            rows.getLong("id"), hash, rows.getString("source"), rows.getString("subset_name"),
                            rows.getString("source_id"), rows.getString("split_name"), rowTheme, text,
                            rows.getString("license_spdx"), "human-mean:" + rows.getInt("grader_count"), scores,
                            scores.stream().mapToInt(Integer::intValue).sum(), relevance
                    ));
                }
            }
        }
        return candidates.stream()
                .sorted(Comparator.comparingDouble(Example::relevance).reversed()
                        .thenComparing(Example::essayHash).thenComparing(Example::graderId))
                .limit(limit)
                .toList();
    }

    public Map<String, Object> statistics() throws SQLException {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("essays", scalar("SELECT COUNT(*) FROM public_essay"));
        stats.put("humanEvaluations", scalar("SELECT COUNT(*) FROM public_human_evaluation"));
        stats.put("ragEligible", scalar("SELECT COUNT(DISTINCT essay_id) FROM public_human_evaluation WHERE eligible_for_rag = 1"));
        stats.put("benchmarkEligible", scalar("SELECT COUNT(DISTINCT essay_id) FROM public_human_evaluation WHERE eligible_for_benchmark = 1"));
        Map<String, Long> splits = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT split_name, COUNT(*) amount FROM public_essay GROUP BY split_name ORDER BY split_name")) {
            while (rows.next()) splits.put(rows.getString(1), rows.getLong(2));
        }
        stats.put("splits", splits);
        return stats;
    }

    /** Seleção determinística e cega; exemplos reservados nunca passam pela recuperação RAG. */
    public List<Example> benchmarkExamples(String split, int limit) throws SQLException {
        String normalizedSplit = normalizeSplit(split);
        if ("train".equals(normalizedSplit)) throw new IllegalArgumentException("benchmark exige validation ou test");
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("limit deve ficar entre 1 e 10000");
        List<Example> examples = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT e.id, e.essay_hash, e.source, e.subset_name, e.source_id, e.split_name,
                       e.theme, e.essay_text, e.license_spdx, COUNT(h.id) grader_count,
                       CAST(ROUND(AVG(h.c1)) AS INTEGER) c1,
                       CAST(ROUND(AVG(h.c2)) AS INTEGER) c2,
                       CAST(ROUND(AVG(h.c3)) AS INTEGER) c3,
                       CAST(ROUND(AVG(h.c4)) AS INTEGER) c4,
                       CAST(ROUND(AVG(h.c5)) AS INTEGER) c5
                FROM public_essay e JOIN public_human_evaluation h ON h.essay_id=e.id
                WHERE e.split_name=? AND h.eligible_for_benchmark=1 AND h.human_verified=1
                GROUP BY e.id
                HAVING CAST(ROUND(AVG(h.c1)) AS INTEGER) % 40 = 0
                   AND CAST(ROUND(AVG(h.c2)) AS INTEGER) % 40 = 0
                   AND CAST(ROUND(AVG(h.c3)) AS INTEGER) % 40 = 0
                   AND CAST(ROUND(AVG(h.c4)) AS INTEGER) % 40 = 0
                   AND CAST(ROUND(AVG(h.c5)) AS INTEGER) % 40 = 0
                ORDER BY e.essay_hash LIMIT ?
                """)) {
            statement.setString(1, normalizedSplit);
            statement.setInt(2, limit);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    List<Integer> scores = List.of(rows.getInt("c1"), rows.getInt("c2"), rows.getInt("c3"),
                            rows.getInt("c4"), rows.getInt("c5"));
                    examples.add(new Example(rows.getLong("id"), rows.getString("essay_hash"),
                            rows.getString("source"), rows.getString("subset_name"), rows.getString("source_id"),
                            rows.getString("split_name"), rows.getString("theme"), rows.getString("essay_text"),
                            rows.getString("license_spdx"), "human-mean:" + rows.getInt("grader_count"), scores,
                            scores.stream().mapToInt(Integer::intValue).sum(), 0));
                }
            }
        }
        return List.copyOf(examples);
    }

    /** Hashes logical rows, so WAL/checkpoint state cannot change or hide the corpus identity. */
    public String fingerprint() throws SQLException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            fingerprintRows(digest, """
                    SELECT source, subset_name, source_id, essay_hash, split_name, theme_group, theme,
                           essay_text, COALESCE(essay_year,''), license_spdx, license_url, source_url
                    FROM public_essay ORDER BY essay_hash
                    """);
            fingerprintRows(digest, """
                    SELECT e.essay_hash, h.grader_id, h.c1, h.c2, h.c3, h.c4, h.c5, h.total,
                           h.evaluator_type, h.human_verified, h.eligible_for_rag,
                           h.eligible_for_benchmark, h.eligible_for_training
                    FROM public_human_evaluation h JOIN public_essay e ON e.id=h.essay_id
                    ORDER BY e.essay_hash, h.grader_id, h.c1, h.c2, h.c3, h.c4, h.c5
                    """);
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private void fingerprintRows(MessageDigest digest, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            int columns = rows.getMetaData().getColumnCount();
            while (rows.next()) {
                for (int index = 1; index <= columns; index++) {
                    byte[] value = String.valueOf(rows.getObject(index)).getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
                    digest.update(value);
                }
            }
        }
    }

    public ExistingEssay findByText(String text) throws SQLException {
        String hash = hashText(text);
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT essay_hash, split_name, theme_group FROM public_essay WHERE essay_hash = ?")) {
            statement.setString(1, hash);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? new ExistingEssay(row.getString(1), row.getString(2), row.getString(3)) : null;
            }
        }
    }

    private Long findEssayId(String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM public_essay WHERE essay_hash = ?")) {
            statement.setString(1, hash);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? rows.getLong(1) : null;
            }
        }
    }

    private long insertEssay(ImportEntry entry, String hash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO public_essay
                  (source, subset_name, source_id, essay_hash, near_signature, split_name, theme_group, theme, essay_text,
                   essay_year, license_spdx, license_url, source_url, imported_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, entry.source());
            statement.setString(2, entry.subset());
            statement.setString(3, entry.sourceId());
            statement.setString(4, hash);
            statement.setLong(5, nearSignature(entry.essayText()));
            statement.setString(6, entry.split());
            statement.setString(7, entry.themeGroup());
            statement.setString(8, entry.theme());
            statement.setString(9, entry.essayText().strip());
            if (entry.year() == null) statement.setNull(10, java.sql.Types.INTEGER); else statement.setInt(10, entry.year());
            statement.setString(11, entry.licenseSpdx());
            statement.setString(12, entry.licenseUrl());
            statement.setString(13, entry.sourceUrl());
            statement.setString(14, Instant.now().toString());
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("SQLite nao retornou o id da redacao");
                return keys.getLong(1);
            }
        }
    }

    private boolean insertEvaluation(long essayId, ImportEntry entry) throws SQLException {
        HumanScore score = entry.score();
        boolean train = "train".equals(entry.split());
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO public_human_evaluation
                  (essay_id, grader_id, c1, c2, c3, c4, c5, total,
                   eligible_for_rag, eligible_for_benchmark, eligible_for_training)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """)) {
            statement.setLong(1, essayId);
            statement.setString(2, score.graderId());
            statement.setInt(3, score.c1()); statement.setInt(4, score.c2()); statement.setInt(5, score.c3());
            statement.setInt(6, score.c4()); statement.setInt(7, score.c5()); statement.setInt(8, score.total());
            statement.setInt(9, train ? 1 : 0);
            statement.setInt(10, train ? 0 : 1);
            statement.setInt(11, train ? 1 : 0);
            return statement.executeUpdate() == 1;
        }
    }

    private void assertSameSplitAndTheme(long essayId, ImportEntry entry) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT split_name, theme_group FROM public_essay WHERE id = ?")) {
            statement.setLong(1, essayId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new SQLException("redacao deduplicada desapareceu");
                if (!row.getString(1).equals(entry.split())) {
                    throw new IllegalArgumentException("redacao duplicada atravessa splits: " + entry.sourceId());
                }
                if (!row.getString(2).equals(entry.themeGroup())) {
                    throw new IllegalArgumentException("redacao duplicada possui themeGroup divergente: " + entry.sourceId());
                }
            }
        }
    }

    private void recordImport(ImportEntry entry, String corpusVersion) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT OR IGNORE INTO corpus_import(source, subset_name, corpus_version, license_spdx, imported_at)
                VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, entry.source());
            statement.setString(2, entry.subset());
            statement.setString(3, corpusVersion);
            statement.setString(4, entry.licenseSpdx());
            statement.setString(5, Instant.now().toString());
            statement.executeUpdate();
        }
    }

    private long scalar(String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql)) {
            return row.next() ? row.getLong(1) : 0;
        }
    }

    public static String hashText(String text) {
        return DatasetManifestValidator.sha256(normalize(text).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static long nearSignature(String text) {
        int[] weights = new int[64];
        java.util.Arrays.stream(normalize(text).split("[^\\p{L}\\p{N}]+"))
                .filter(token -> token.length() >= 3).forEach(token -> {
                    byte[] digest;
                    try { digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
                    catch (Exception ex) { throw new IllegalStateException(ex); }
                    long hash = java.nio.ByteBuffer.wrap(digest).getLong();
                    for (int bit = 0; bit < 64; bit++) weights[bit] += ((hash >>> bit) & 1L) == 1L ? 1 : -1;
                });
        long signature = 0;
        for (int bit = 0; bit < 64; bit++) if (weights[bit] >= 0) signature |= 1L << bit;
        return signature;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    private static List<String> terms(String text) {
        return java.util.Arrays.stream(normalize(text).split("[^\\p{L}\\p{N}]+"))
                .filter(term -> term.length() >= 4).distinct().limit(24).toList();
    }

    private static double relevance(List<String> terms, String requestedTheme, String theme, String essay) {
        String normalizedTheme = normalize(theme);
        String normalizedEssay = normalize(essay);
        double score = requestedTheme != null && normalizedTheme.equals(normalize(requestedTheme)) ? 20 : 0;
        for (String term : terms) {
            if (normalizedTheme.contains(term)) score += 4;
            if (normalizedEssay.contains(term)) score += 1;
        }
        return score;
    }

    private static String normalizeSplit(String split) {
        String value = split == null ? "" : split.toLowerCase(Locale.ROOT).strip();
        if (!List.of("train", "validation", "test").contains(value)) {
            throw new IllegalArgumentException("split deve ser train, validation ou test");
        }
        return value;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " obrigatorio");
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
