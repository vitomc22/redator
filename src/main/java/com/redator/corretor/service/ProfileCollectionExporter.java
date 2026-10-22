package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.sqlite.SQLiteConfig;

/** Prepares isolated collections; never selects, replaces or writes the live database. */
public final class ProfileCollectionExporter {
    private static final Set<String> TABLES = Set.of("flyway_schema_history", "tema", "essay", "run",
            "competence_result", "llm_call", "human_score", "human_review_request", "human_evaluation",
            "student_checklist", "review_package", "training_card", "training_card_review", "training_goal",
            "training_session", "evaluation_cache", "opaque_relay_channel");
    private static final ObjectMapper JSON = new ObjectMapper();
    // Exact DDL produced by the bundled V1–V10 migrations, including Flyway's history table.
    private static final String SOURCE_SCHEMA_HASH = "43225ab911bbaeeae8cbf6cfc920b0d08289e256dec9504252184e5992b3d3df";

    public record Report(Path snapshot, Map<String, Path> profiles, Path manifest) { }

    public Report export(Path source, Path destination) throws Exception {
        Path original = source.toAbsolutePath().normalize();
        Path output = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(original)) throw new IllegalArgumentException("Coleção de origem não encontrada");
        if (Files.exists(output)) throw new IllegalArgumentException("O destino já existe; nenhuma coleção será substituída");
        Files.createDirectories(output.getParent());
        Path staging = Files.createDirectory(output.getParent().resolve(".profile-export-" + UUID.randomUUID()));
        try {
            if (Files.getFileStore(staging).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(staging, PosixFilePermissions.fromString("rwx------"));
            Path snapshot = staging.resolve("legacy-snapshot.sqlite");
            try (Connection live = readOnly(original); var statement = live.createStatement()) {
                // SQLite includes committed WAL pages and takes a consistent read snapshot.
                statement.execute("VACUUM INTO '" + sqlPath(snapshot) + "'");
            }
            Path sourceSnapshot = staging.resolve("legacy-source.sqlite");
            Files.copy(snapshot, sourceSnapshot);
            String sourceLogicalFingerprint = SQLiteCollectionFingerprint.of(sourceSnapshot);
            // Upgrade only the disposable copy; the original and its raw snapshot stay untouched.
            org.flywaydb.core.Flyway.configure().dataSource("jdbc:sqlite:" + snapshot, null, null).load().migrate();
            List<String> profiles;
            Map<String, Object> unassigned;
            try (Connection db = readOnly(snapshot)) {
                validate(db);
                profiles = profiles(db);
                unassigned = Map.of("evaluationCacheEntries", count(db, "evaluation_cache"),
                        "relayChannels", count(db, "opaque_relay_channel"),
                        "unlinkedLlmCalls", scalar(db, "SELECT count(*) FROM llm_call WHERE run_id IS NULL"),
                        "preservedInLegacySnapshot", true);
            }
            var targets = new LinkedHashMap<String, Path>();
            var entries = new LinkedHashMap<String, Object>();
            LocalDataDirectories layout = new LocalDataDirectories(staging);
            for (String profile : profiles) {
                Path target = layout.profile(profile).resolve("redator.sqlite");
                Files.copy(snapshot, target);
                isolate(target, profile);
                Path relative = staging.relativize(target);
                targets.put(profile, output.resolve(relative));
                try (Connection db = readOnly(target)) {
                    entries.put(profile, Map.of("file", relative.toString().replace('\\', '/'),
                            "sha256", hash(target), "essays", count(db, "essay"),
                            "runs", count(db, "run"), "humanEvaluations", count(db, "human_evaluation"),
                            "trainingCards", count(db, "training_card")));
                }
            }
            var manifest = new LinkedHashMap<String, Object>();
            manifest.put("schemaVersion", 1);
            manifest.put("status", "PREPARED_NOT_ACTIVATED");
            manifest.put("source", original.toString());
            manifest.put("sourceLogicalFingerprint", sourceLogicalFingerprint);
            manifest.put("legacySourceSnapshot", Map.of("file", "legacy-source.sqlite", "sha256", hash(sourceSnapshot)));
            manifest.put("sourceSchemaSha256", SOURCE_SCHEMA_HASH);
            manifest.put("legacySnapshot", Map.of("file", "legacy-snapshot.sqlite", "sha256", hash(snapshot)));
            manifest.put("profiles", entries);
            manifest.put("unassignedData", unassigned);
            Files.writeString(staging.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest) + "\n");
            // Same-parent rename publishes only the complete tree, without replacing an existing destination.
            Files.move(staging, output);
            return new Report(output.resolve("legacy-snapshot.sqlite"), Collections.unmodifiableMap(targets), output.resolve("manifest.json"));
        } finally {
            if (Files.exists(staging)) {
                try (var paths = Files.walk(staging)) {
                    for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
                }
            }
        }
    }

    private static void isolate(Path target, String profile) throws Exception {
        try (Connection db = DriverManager.getConnection("jdbc:sqlite:" + target)) {
            try (var statement = db.createStatement()) {
                statement.execute("PRAGMA foreign_keys=ON");
                statement.execute("PRAGMA secure_delete=ON");
            }
            db.setAutoCommit(false);
            try {
                remove(db, "DELETE FROM training_card_review WHERE card_id IN (SELECT id FROM training_card WHERE student_id<>?)", profile);
                remove(db, "DELETE FROM training_card WHERE student_id<>?", profile);
                remove(db, "DELETE FROM training_session WHERE student_id<>?", profile);
                remove(db, "DELETE FROM training_goal WHERE student_id<>?", profile);
                remove(db, "DELETE FROM review_package WHERE student_id<>?", profile);
                remove(db, "DELETE FROM human_evaluation WHERE request_id IN (SELECT hr.id FROM human_review_request hr JOIN essay e ON e.id=hr.essay_id WHERE e.student_id<>?)", profile);
                remove(db, "DELETE FROM human_review_request WHERE essay_id IN (SELECT id FROM essay WHERE student_id<>?)", profile);
                remove(db, "DELETE FROM student_checklist WHERE essay_id IN (SELECT id FROM essay WHERE student_id<>?)", profile);
                remove(db, "DELETE FROM human_score WHERE essay_id IN (SELECT id FROM essay WHERE student_id<>?)", profile);
                remove(db, "DELETE FROM competence_result WHERE run_id IN (SELECT r.id FROM run r JOIN essay e ON e.id=r.essay_id WHERE e.student_id<>?)", profile);
                remove(db, "DELETE FROM llm_call WHERE run_id IS NULL OR run_id IN (SELECT r.id FROM run r JOIN essay e ON e.id=r.essay_id WHERE e.student_id<>?)", profile);
                remove(db, "DELETE FROM run WHERE essay_id IN (SELECT id FROM essay WHERE student_id<>?)", profile);
                remove(db, "DELETE FROM essay WHERE student_id<>?", profile);
                try (var statement = db.createStatement()) {
                    // Neither table has a trustworthy owner link in the legacy schema.
                    statement.executeUpdate("DELETE FROM evaluation_cache");
                    statement.executeUpdate("DELETE FROM opaque_relay_channel");
                }
                db.commit();
            } catch (Exception error) {
                db.rollback(); throw error;
            }
            db.setAutoCommit(true);
            try (var statement = db.createStatement()) { statement.execute("VACUUM"); }
            validate(db);
            if (!profiles(db).equals(List.of(profile))) throw new IllegalArgumentException("Coleção isolada com proprietário inconsistente");
        }
    }

    static void verifyCollection(Path database) throws Exception {
        try (var db = readOnly(database.toAbsolutePath().normalize())) { validate(db); }
    }

    private static void validate(Connection db) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check")) {
            if (!rows.next() || !"ok".equals(rows.getString(1))) throw new IllegalArgumentException("Coleção SQLite inválida");
        }
        var actual = new java.util.HashSet<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery(
                "SELECT type,name FROM sqlite_master WHERE type IN ('table','view','trigger') AND substr(name,1,7)<>'sqlite_'")) {
            while (rows.next()) {
                if (!"table".equals(rows.getString(1))) throw new IllegalArgumentException("Schema contém objeto não suportado");
                actual.add(rows.getString(2));
            }
        }
        if (!actual.equals(TABLES)) throw new IllegalArgumentException("Schema da coleção não suportado para separação");
        if (!SOURCE_SCHEMA_HASH.equals(schemaHash(db)))
            throw new IllegalArgumentException("Schema da coleção difere das migrações suportadas");
        if (scalar(db, "SELECT count(*) FROM flyway_schema_history WHERE success=1 AND version IN ('1','2','3','4','5','6','7','8','9','10')") != 10
                || scalar(db, "SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL") != 10)
            throw new IllegalArgumentException("Versão da coleção não suportada para separação");
        try (var statement = db.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (rows.next()) throw new IllegalArgumentException("Coleção contém referência inválida");
        }
        List<String> invalid = List.of(
            "SELECT count(*) FROM essay e WHERE (parent_essay_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM essay p WHERE p.id=e.parent_essay_id AND p.student_id=e.student_id)) OR (group_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM essay g WHERE g.id=e.group_id AND g.student_id=e.student_id))",
            "SELECT count(*) FROM training_card c JOIN essay e ON e.id=c.essay_id WHERE c.student_id<>e.student_id OR (c.source_run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM run r WHERE r.id=c.source_run_id AND r.essay_id=e.id))",
            "SELECT count(*) FROM review_package p JOIN human_review_request hr ON hr.id=p.request_id JOIN essay e ON e.id=hr.essay_id WHERE p.student_id<>e.student_id",
            "SELECT count(*) FROM human_review_request hr JOIN run r ON r.id=hr.reference_run_id WHERE hr.essay_id<>r.essay_id",
            "SELECT count(*) FROM training_card_review cr JOIN training_card c ON c.id=cr.card_id JOIN training_session s ON s.id=cr.session_id WHERE c.student_id<>s.student_id",
            "SELECT count(*) FROM llm_call l WHERE l.run_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM run r WHERE r.id=l.run_id)"
        );
        for (String sql : invalid) if (scalar(db, sql) != 0) throw new IllegalArgumentException("Coleção contém relações inconsistentes entre perfis");
        profiles(db);
    }

    private static List<String> profiles(Connection db) throws SQLException {
        var profiles = new ArrayList<String>();
        try (var statement = db.createStatement(); var rows = statement.executeQuery("""
                SELECT student_id FROM essay UNION SELECT student_id FROM training_goal
                UNION SELECT student_id FROM training_session UNION SELECT student_id FROM training_card
                UNION SELECT student_id FROM review_package ORDER BY student_id
                """)) {
            while (rows.next()) {
                String profile = rows.getString(1);
                if (profile == null || !profile.matches("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}"))
                    throw new IllegalArgumentException("Coleção contém identificador local inválido");
                profiles.add(profile);
            }
        }
        return profiles;
    }

    private static Connection readOnly(Path path) throws SQLException {
        var configuration = new SQLiteConfig();
        configuration.setReadOnly(true);
        configuration.setBusyTimeout(10_000);
        return DriverManager.getConnection("jdbc:sqlite:" + path, configuration.toProperties());
    }

    private static String schemaHash(Connection db) throws SQLException {
        var ddl = new StringBuilder();
        try (var statement = db.createStatement(); var rows = statement.executeQuery(
                "SELECT type,name,sql FROM sqlite_master WHERE sql IS NOT NULL AND substr(name,1,7)<>'sqlite_' ORDER BY type,name")) {
            while (rows.next()) ddl.append(rows.getString(1)).append('\0').append(rows.getString(2))
                    .append('\0').append(rows.getString(3)).append('\n');
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ddl.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static int count(Connection db, String table) throws SQLException { return scalar(db, "SELECT count(*) FROM " + table); }
    private static int scalar(Connection db, String sql) throws SQLException {
        try (var statement = db.createStatement(); var rows = statement.executeQuery(sql)) { return rows.next() ? rows.getInt(1) : 0; }
    }
    private static void remove(Connection db, String sql, String profile) throws SQLException {
        try (var statement = db.prepareStatement(sql)) { statement.setString(1, profile); statement.executeUpdate(); }
    }
    private static String sqlPath(Path path) { return path.toString().replace("'", "''"); }
    private static String hash(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
