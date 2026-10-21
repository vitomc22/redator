package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.cli.ProfileCollectionCli;
import com.redator.corretor.service.ProfileCollectionExporter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProfileCollectionExporterIntegrationTest {
    @TempDir Path root;

    @Test
    void separatesTheCompleteOwnedGraphFromAConsistentWalSnapshotWithoutChangingTheSource() throws Exception {
        Path source = collection();
        // Keep the WAL connection open: copying only the main file would omit these commits.
        try (Connection live = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            execute(live, "PRAGMA journal_mode=WAL");
            execute(live, "PRAGMA wal_autocheckpoint=0");
            person(live, "alice", 10, "PRIVATE_ALICE_SENTINEL");
            person(live, "bob", 20, "PRIVATE_BOB_SENTINEL");
            execute(live, "INSERT INTO training_goal VALUES ('goals-only',9,'now')");
            execute(live, "INSERT INTO training_session(id,student_id,status,started_at) VALUES (30,'session-only','ACTIVE','now')");
            execute(live, "INSERT INTO evaluation_cache VALUES ('shared-key','PRIVATE_CACHE_SENTINEL','now','now','later')");
            execute(live, "INSERT INTO opaque_relay_channel(id,professor_token_hash,student_token_hash,request_blob,expires_at,created_at) VALUES ('relay','p-hash','s-hash',X'CAFE','later','now')");
            execute(live, "INSERT INTO llm_call(status,created_at,audit_json) VALUES ('READY','now','PRIVATE_UNLINKED_SENTINEL')");
            byte[] original = Files.readAllBytes(source);
            Path output = root.resolve("prepared collections");
            var report = new ProfileCollectionExporter().export(source, output);
            assertThat(report.profiles().keySet()).containsExactly("alice", "bob", "goals-only", "session-only");
            assertThat(report.snapshot()).isEqualTo(output.resolve("legacy-snapshot.sqlite"));
            assertThat(Files.readAllBytes(source)).isEqualTo(original);
            assertThat(count(source, "essay")).isEqualTo(2);
            assertThat(count(source, "evaluation_cache")).isEqualTo(1);
            assertThat(count(report.snapshot(), "essay")).isEqualTo(2);
            assertThat(count(report.snapshot(), "opaque_relay_channel")).isEqualTo(1);
            for (String profile : List.of("alice", "bob")) {
                Path database = report.profiles().get(profile);
                assertThat(database).isEqualTo(output.resolve("profiles/" + profile + "/redator.sqlite"));
                assertThat(value(database, "SELECT student_id FROM essay")).isEqualTo(profile);
                for (String table : List.of("essay", "run", "competence_result", "llm_call", "human_score",
                        "human_review_request", "human_evaluation", "student_checklist", "review_package",
                        "training_card", "training_card_review", "training_goal", "training_session")) {
                    assertThat(count(database, table)).as(profile + " " + table).isEqualTo(1);
                }
                assertThat(value(database, "SELECT main_guidance FROM human_evaluation")).isEqualTo("guidance-" + profile);
                assertThat(count(database, "evaluation_cache")).isZero();
                assertThat(count(database, "opaque_relay_channel")).isZero();
                String bytes = new String(Files.readAllBytes(database), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertThat(bytes).doesNotContain(profile.equals("alice") ? "PRIVATE_BOB_SENTINEL" : "PRIVATE_ALICE_SENTINEL")
                        .doesNotContain("PRIVATE_CACHE_SENTINEL", "PRIVATE_UNLINKED_SENTINEL");
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + database);
                     var check = db.createStatement().executeQuery("PRAGMA foreign_key_check")) {
                    assertThat(check.next()).isFalse();
                }
            }
            assertThat(count(report.profiles().get("goals-only"), "essay")).isZero();
            assertThat(count(report.profiles().get("goals-only"), "training_goal")).isEqualTo(1);
            assertThat(count(report.profiles().get("session-only"), "training_session")).isEqualTo(1);
            Map<?, ?> manifest = new ObjectMapper().readValue(Files.readString(report.manifest()), Map.class);
            assertThat(manifest.get("status")).isEqualTo("PREPARED_NOT_ACTIVATED");
            assertThat(Files.readString(report.manifest())).doesNotContain("PRIVATE_", "guidance-");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cross-owner-card", "cross-owner-parent", "foreign-key", "unknown-table", "unknown-table-lookalike", "unknown-column", "unknown-view", "invalid-profile"})
    void refusesUnsafeLegacyGraphsWithoutPublishingAnyPartialCollection(String problem) throws Exception {
        Path source = collection();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            person(db, "alice", 10, "first"); person(db, "bob", 20, "second");
            String change = switch (problem) {
                case "cross-owner-card" -> "UPDATE training_card SET student_id='bob' WHERE id=10";
                case "cross-owner-parent" -> "UPDATE essay SET parent_essay_id=20 WHERE id=10";
                case "foreign-key" -> "UPDATE run SET essay_id=999 WHERE id=10";
                case "unknown-table" -> "CREATE TABLE future_private_data(text TEXT)";
                case "unknown-table-lookalike" -> "CREATE TABLE sqliteXprivate(text TEXT)";
                case "unknown-column" -> "ALTER TABLE tema ADD COLUMN private_note TEXT";
                case "unknown-view" -> "CREATE VIEW leaked_history AS SELECT 'private history'";
                default -> "UPDATE essay SET student_id='../escape' WHERE id=10";
            };
            execute(db, change);
        }
        byte[] before = Files.readAllBytes(source);
        Path output = root.resolve("unsafe-output");
        assertThatThrownBy(() -> new ProfileCollectionExporter().export(source, output))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(output).doesNotExist();
        assertThat(Files.readAllBytes(source)).isEqualTo(before);
        try (var files = Files.list(root)) {
            assertThat(files.map(p -> p.getFileName().toString())).noneMatch(name -> name.startsWith(".profile-export-"));
        }
    }

    @Test
    void cliPreparesAnEmptyLegacyCollectionAndNeverOverwritesAnExistingDestination() throws Exception {
        Path source = collection(), output = root.resolve("empty-output");
        ProfileCollectionCli.main(new String[]{"--source", source.toString(), "--output", output.toString()});
        assertThat(output.resolve("legacy-snapshot.sqlite")).exists();
        assertThat(Files.readString(output.resolve("manifest.json"))).contains("PREPARED_NOT_ACTIVATED");
        Path marker = output.resolve("keep.txt"); Files.writeString(marker, "preserve");
        assertThatThrownBy(() -> new ProfileCollectionExporter().export(source, output)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Files.readString(marker)).isEqualTo("preserve");
        assertThatThrownBy(() -> new ProfileCollectionExporter().export(root.resolve("missing.db"), root.resolve("missing-output")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(root.resolve("missing.db")).doesNotExist();
    }

    private Path collection() {
        Path path = root.resolve("legacy.sqlite");
        Flyway.configure().dataSource("jdbc:sqlite:" + path, null, null).load().migrate();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            execute(db, "INSERT INTO tema VALUES (1,'Tema público','Recorte','Motivadores')");
        } catch (Exception e) { throw new IllegalStateException(e); }
        return path;
    }

    private void person(Connection db, String owner, int id, String text) throws Exception {
        String[] statements = {
            "INSERT INTO essay(id,tema_id,text,status,created_at,student_id,group_id) VALUES (%1$d,1,'%3$s','READY','now','%2$s',%1$d)",
            "INSERT INTO run(id,essay_id,profile,total,status,created_at) VALUES (%1$d,%1$d,'normal',800,'READY','now')",
            "INSERT INTO competence_result(id,run_id,competencia,nota,nivel_sugerido) VALUES (%1$d,%1$d,'C1',160,160)",
            "INSERT INTO llm_call(id,run_id,status,created_at,audit_json) VALUES (%1$d,%1$d,'READY','now','audit-%2$s')",
            "INSERT INTO human_score(id,essay_id,competencia,nota,avaliador,created_at) VALUES (%1$d,%1$d,'C1',160,'r','now')",
            "INSERT INTO human_review_request(id,essay_id,essay_revision,reference_run_id,status,requested_at) VALUES (%1$d,%1$d,'revision',%1$d,'COMPLETED','now')",
            "INSERT INTO human_evaluation(id,request_id,version,status,reviewer_id,main_guidance,total,rubric_version,created_at) VALUES (%1$d,%1$d,1,'PUBLISHED','r','guidance-%2$s',800,'v1','now')",
            "INSERT INTO student_checklist(id,essay_id,competence,item_key,updated_at) VALUES (%1$d,%1$d,'C1','item','now')",
            "INSERT INTO review_package(package_id,request_id,student_id,revision_hash,secret_hash,status,expires_at,created_at) VALUES ('package-%2$s',%1$d,'%2$s','revision','hash','IMPORTED','later','now')",
            "INSERT INTO training_goal VALUES ('%2$s',5,'now')",
            "INSERT INTO training_session(id,student_id,status,started_at) VALUES (%1$d,'%2$s','ACTIVE','now')",
            "INSERT INTO training_card(id,student_id,essay_id,competence,question,answer,source_run_id,due_at,created_at) VALUES (%1$d,'%2$s',%1$d,'C1','q','answer-%2$s',%1$d,'now','now')",
            "INSERT INTO training_card_review(id,card_id,quality,previous_interval_days,next_interval_days,reviewed_at,session_id) VALUES (%1$d,%1$d,4,0,1,'now',%1$d)"
        };
        for (String sql : statements) execute(db, sql.formatted(id, owner, text));
    }

    private static void execute(Connection db, String sql) throws Exception {
        try (var statement = db.createStatement()) { statement.execute(sql); }
    }

    private int count(Path database, String table) throws Exception {
        return Integer.parseInt(value(database, "SELECT count(*) FROM " + table));
    }

    private String value(Path database, String sql) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = db.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
