package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.LocalBackupService;
import com.redator.corretor.service.LocalDataDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class LocalBackupServiceTest {
    @TempDir Path temporary;

    @Test
    void createsConsistentBackupAndStagesOnlyValidSQLiteForRestore() throws Exception {
        Path database = temporary.resolve("live.sqlite");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + database));
        jdbc.execute("PRAGMA journal_mode=WAL");
        jdbc.execute("CREATE TABLE note(id INTEGER PRIMARY KEY, body TEXT NOT NULL)");
        jdbc.execute("CREATE TABLE essay(id INTEGER PRIMARY KEY, text TEXT)");
        jdbc.execute("CREATE TABLE run(id INTEGER PRIMARY KEY)");
        jdbc.execute("CREATE TABLE flyway_schema_history(installed_rank INTEGER PRIMARY KEY)");
        jdbc.update("INSERT INTO note(body) VALUES (?)", "histórico local");
        LocalDataDirectories directories = new LocalDataDirectories(temporary.resolve("Redator"));
        LocalBackupService backups = new LocalBackupService(jdbc, directories);

        Path backup = backups.createBackup();
        JdbcTemplate snapshot = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + backup));
        assertThat(snapshot.queryForObject("SELECT body FROM note", String.class)).isEqualTo("histórico local");
        assertThat(backups.stageRestore(backup)).exists();
        byte[] exported = Files.readAllBytes(backup);
        assertThat(backups.stageRestore(new ByteArrayInputStream(exported), exported.length)).exists();

        Path invalid = temporary.resolve("invalid.sqlite");
        Files.writeString(invalid, "não é sqlite");
        assertThatThrownBy(() -> backups.stageRestore(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> backups.stageRestore(new ByteArrayInputStream(new byte[0]), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
