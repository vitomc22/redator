package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.config.StartupRestoreApplier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StartupRestoreApplierTest {
    @TempDir Path root;

    @Test
    void appliesStagedCollectionBeforeStartupAndPreservesPreviousDatabase() throws Exception {
        Path live = root.resolve("redator.db");
        Path staged = root.resolve("runtime/restore-next-start.sqlite");
        Files.createDirectories(staged.getParent());
        collection(live, "anterior");
        collection(staged, "restaurada");

        assertThat(StartupRestoreApplier.apply(root)).isTrue();

        assertThat(value(live)).isEqualTo("restaurada");
        Path safety = Files.list(root.resolve("backups"))
                .filter(path -> path.getFileName().toString().startsWith("pre-restore-")).findFirst().orElseThrow();
        assertThat(value(safety)).isEqualTo("anterior");
        assertThat(staged).doesNotExist();
        assertThat(StartupRestoreApplier.apply(root)).isFalse();
    }

    private static void collection(Path path, String value) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE essay(id INTEGER PRIMARY KEY, text TEXT)");
            statement.execute("CREATE TABLE run(id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE flyway_schema_history(installed_rank INTEGER PRIMARY KEY)");
            try (var insert = connection.prepareStatement("INSERT INTO essay(text) VALUES (?)")) {
                insert.setString(1, value); insert.executeUpdate();
            }
        }
    }

    private static String value(Path path) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT text FROM essay")) {
            return result.next() ? result.getString(1) : null;
        }
    }
}
