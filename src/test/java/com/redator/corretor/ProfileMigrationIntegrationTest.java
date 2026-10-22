package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.LocalDataDirectories;
import com.redator.corretor.service.ProfileCollectionExporter;
import com.redator.corretor.service.ProfileDatabaseMigration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProfileMigrationIntegrationTest {
    @TempDir Path root;

    @Test
    void activatesPreparedCollectionsOnceAndNeverReplacesSubsequentPrivateEdits() throws Exception {
        Path source = legacy(); byte[] original = Files.readAllBytes(source);
        var migration = new ProfileDatabaseMigration(new LocalDataDirectories(root));
        migration.activate();
        Path alice = root.resolve("profiles/alice/redator.sqlite"), bob = root.resolve("profiles/bob/redator.sqlite");
        assertThat(value(alice)).isEqualTo("Alice original"); assertThat(value(bob)).isEqualTo("Bob original");
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + alice)) {
            db.createStatement().executeUpdate("UPDATE essay SET text='Alice alterada'");
        }
        migration.activate();
        assertThat(value(alice)).isEqualTo("Alice alterada");
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(root.resolve("runtime/profile-migration-prepared/legacy-snapshot.sqlite")).exists();
    }

    @Test
    void refusesAnExistingUnrelatedCollectionAndResumesAfterAnInterruptedActivation() throws Exception {
        legacy();
        Path bob = root.resolve("profiles/bob/redator.sqlite"); Files.createDirectories(bob.getParent());
        Files.writeString(bob, "do not overwrite");
        var migration = new ProfileDatabaseMigration(new LocalDataDirectories(root));
        assertThatThrownBy(migration::activate).isInstanceOf(IllegalStateException.class).hasMessageContaining("existente");
        assertThat(Files.readString(bob)).isEqualTo("do not overwrite");
        assertThat(root.resolve("runtime/profile-migration-active.json")).doesNotExist();
        Files.delete(bob);
        migration.activate();
        assertThat(value(bob)).isEqualTo("Bob original");
        assertThat(root.resolve("runtime/profile-migration-active.json")).exists();
    }

    @Test
    void refusesLegacyWritesAfterPreparationInsteadOfSilentlyLosingThem() throws Exception {
        Path source = legacy();
        new ProfileCollectionExporter().export(source, root.resolve("runtime/profile-migration-prepared"));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + source)) {
            db.createStatement().executeUpdate("UPDATE essay SET text='Alteração ainda no legado' WHERE student_id='alice'");
        }
        var migration = new ProfileDatabaseMigration(new LocalDataDirectories(root));
        assertThatThrownBy(migration::activate).isInstanceOf(IllegalStateException.class).hasMessageContaining("legado mudou");
        assertThat(root.resolve("profiles/alice/redator.sqlite")).doesNotExist();
    }

    @Test
    void upgradesVersionFiveInACopyAndPreservesTheOriginalAndItsOwnedHistory() throws Exception {
        Path source = legacy("5");
        byte[] original = Files.readAllBytes(source);
        new ProfileDatabaseMigration(new LocalDataDirectories(root)).activate();
        assertThat(Files.readAllBytes(source)).isEqualTo(original);
        assertThat(value(root.resolve("profiles/alice/redator.sqlite"))).isEqualTo("Alice original");
        assertThat(value(root.resolve("profiles/bob/redator.sqlite"))).isEqualTo("Bob original");
        Path raw = root.resolve("runtime/profile-migration-prepared/legacy-source.sqlite");
        assertThat(raw).exists();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + raw);
             var rows = db.createStatement().executeQuery("SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(5);
        }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + root.resolve("profiles/alice/redator.sqlite"));
             var rows = db.createStatement().executeQuery("SELECT max(CAST(version AS INTEGER)) FROM flyway_schema_history")) {
            assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(10);
        }
    }

    private Path legacy() throws Exception { return legacy("10"); }
    private Path legacy(String version) throws Exception {
        Path source = root.resolve("redator.db");
        Flyway.configure().dataSource("jdbc:sqlite:" + source, null, null).target(version).load().migrate();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + source); var statement = db.createStatement()) {
            statement.execute("INSERT INTO tema VALUES (1,'Tema público','Recorte','Motivadores')");
            statement.execute("INSERT INTO essay(id,tema_id,text,status,created_at,student_id,group_id) VALUES (1,1,'Alice original','READY','now','alice',1),(2,1,'Bob original','READY','now','bob',2)");
        }
        return source;
    }
    private String value(Path database) throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + database); var query = db.createStatement().executeQuery("SELECT text FROM essay")) {
            assertThat(query.next()).isTrue(); return query.getString(1);
        }
    }
}
