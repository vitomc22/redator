package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.LocalBackupService;
import com.redator.corretor.service.LocalDataDirectories;
import com.redator.corretor.service.LocalUserContext;
import com.redator.corretor.service.DatasetManifestValidator;
import com.redator.corretor.service.PairedBenchmarkRunner;
import com.redator.corretor.service.PublicHumanCorpus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.config.ProfileRoutingDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.profile-databases=true", "app.rag.enabled=false", "app.llm.provider=fake", "app.local-api-token.enabled=false"})
class ProfileDatabaseIntegrationTest {
    private static final Path ROOT;
    static {
        try { ROOT = Files.createTempDirectory("redator-profile-integration-"); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    @DynamicPropertySource static void data(DynamicPropertyRegistry properties) {
        properties.add("app.data-directory", ROOT::toString);
    }
    @Autowired TestRestTemplate rest;
    @Autowired LocalUserContext users;
    @Autowired LocalDataDirectories directories;
    @Autowired LocalBackupService backups;
    @Autowired JdbcTemplate jdbc;
    @Autowired PairedBenchmarkRunner benchmarks;

    @Test
    void benchmarkCreatesThemesOnTheSameConnectionThatReturnsTheirKeys() throws Exception {
        String text = "A educação pública requer investimento e formação docente.";
        var samples = List.of(Map.of("essayHash", PublicHumanCorpus.hashText(text), "text", text, "theme", "Tema próprio"));
        var json = new ObjectMapper();
        Path benchmark = ROOT.resolve("benchmark.json"), output = ROOT.resolve("results.json");
        Files.writeString(benchmark, json.writeValueAsString(Map.of("samples", samples, "sampleCount", 1,
                "selectionFingerprint", DatasetManifestValidator.sha256(json.writeValueAsBytes(samples)))));
        users.set("benchmark-profile");
        try {
            var results = (List<Map<String, Object>>) benchmarks.run(benchmark, output, "baseline").get("results");
            assertThat(results).hasSize(1);
            assertThat(results.getFirst()).containsEntry("status", "NEEDS_REVIEW");
            assertThat(jdbc.queryForObject("SELECT t.titulo FROM essay e JOIN tema t ON t.id=e.tema_id", String.class))
                    .isEqualTo("Tema próprio");
        } finally { users.clear(); }
    }

    @Test
    void refusesRestoreWithUnknownSchemaOrBrokenRelationsWithoutStagingIt() throws Exception {
        String profile = "invalid-backup-" + UUID.randomUUID();
        essay(profile, "Coleção válida.");
        users.set(profile);
        try {
            Path unknown = backups.createBackup();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + unknown)) {
                db.createStatement().execute("CREATE TABLE future_private_data(body TEXT)");
            }
            assertThatThrownBy(() -> backups.stageRestore(unknown)).isInstanceOf(IllegalArgumentException.class);
            Path broken = backups.createBackup();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + broken)) {
                db.createStatement().execute("UPDATE essay SET parent_essay_id=9999");
            }
            assertThatThrownBy(() -> backups.stageRestore(broken)).isInstanceOf(IllegalArgumentException.class);
            assertThat(ROOT.resolve("profiles/" + profile + "/runtime/restore-next-start.sqlite")).doesNotExist();
            assertThat(text(profile)).isEqualTo("Coleção válida.");
        } finally { users.clear(); }
    }

    @Test
    void httpRequestsAndTheirCachesUsePhysicallySeparateCollections() throws Exception {
        String alice = "alice-" + UUID.randomUUID(), bob = "bob-" + UUID.randomUUID();
        long aliceId = essay(alice, "Texto privado de Alice.");
        var forbidden = rest.exchange("/api/essays/" + aliceId, HttpMethod.GET, entity(bob, null), Map.class);
        assertThat(forbidden.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        long bobId = essay(bob, "Texto privado de Bob.");
        assertThat(aliceId).isEqualTo(1); assertThat(bobId).isEqualTo(1);
        assertThat(text(alice)).isEqualTo("Texto privado de Alice.");
        assertThat(text(bob)).isEqualTo("Texto privado de Bob.");
        users.set(alice);
        try {
            jdbc.update("INSERT INTO evaluation_cache VALUES ('key','private-result','now','now','later')");
            users.set(bob);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluation_cache", Integer.class)).isZero();
        } finally { users.clear(); }
        assertThat(ROOT.resolve("redator.db")).doesNotExist();
    }

    @Test
    void backupContainsOnlyCurrentProfileAndRestoreWaitsForRestartAndPreservesOtherProfiles() throws Exception {
        String alice = "restore-alice-" + UUID.randomUUID(), bob = "restore-bob-" + UUID.randomUUID();
        essay(alice, "Original de Alice."); essay(bob, "Original de Bob.");
        users.set(alice);
        try {
            Path backup = backups.createBackup();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + backup);
                 var result = db.createStatement().executeQuery("SELECT student_id,text FROM essay")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getString(1)).isEqualTo(alice);
                assertThat(result.getString(2)).isEqualTo("Original de Alice.");
                assertThat(result.next()).isFalse();
            }
            jdbc.update("UPDATE essay SET text='Alteração posterior.'");
            Path staged = backups.stageRestore(backup);
            assertThat(staged).startsWith(ROOT.resolve("profiles/" + alice));
            assertThat(text(alice)).isEqualTo("Alteração posterior.");
            // A new datasource models restart: restore precedes its first connection.
            var restarted = new ProfileRoutingDataSource(directories, users);
            assertThat(new JdbcTemplate(restarted).queryForObject("SELECT text FROM essay", String.class))
                    .isEqualTo("Original de Alice.");
            assertThat(staged).doesNotExist();
            assertThat(text(bob)).isEqualTo("Original de Bob.");
            try (var files = Files.list(ROOT.resolve("profiles/" + alice + "/backups"))) {
                assertThat(files.map(p -> p.getFileName().toString())).anyMatch(n -> n.startsWith("pre-restore-"));
            }
            users.set(bob);
            assertThatThrownBy(() -> backups.stageRestore(backup)).isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("outro perfil");
            assertThat(ROOT.resolve("profiles/" + bob + "/runtime/restore-next-start.sqlite")).doesNotExist();
        } finally { users.clear(); }
    }

    private long essay(String profile, String text) {
        var response = rest.exchange("/api/attempts", HttpMethod.POST,
                entity(profile, Map.of("temaId", 1, "text", text, "title", "Teste", "sourceType", "text")), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) ((Map<?, ?>) response.getBody().get("essay")).get("id")).longValue();
    }
    private HttpEntity<?> entity(String profile, Object body) {
        var headers = new HttpHeaders(); headers.set(LocalUserContext.HEADER, profile);
        return new HttpEntity<>(body, headers);
    }
    private String text(String profile) throws Exception {
        Path dbFile = ROOT.resolve("profiles/" + profile + "/redator.sqlite");
        assertThat(dbFile).exists();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + dbFile);
             var query = db.createStatement().executeQuery("SELECT text FROM essay")) {
            assertThat(query.next()).isTrue(); return query.getString(1);
        }
    }
}
