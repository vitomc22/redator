package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.LocalBackupService;
import com.redator.corretor.service.LocalDataDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
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
        jdbc.update("INSERT INTO note(body) VALUES (?)", "histórico local");
        LocalDataDirectories directories = new LocalDataDirectories(temporary.resolve("Redator"));
        LocalBackupService backups = new LocalBackupService(jdbc, directories);

        Path backup = backups.createBackup();
        JdbcTemplate snapshot = new JdbcTemplate(new DriverManagerDataSource("jdbc:sqlite:" + backup));
        assertThat(snapshot.queryForObject("SELECT body FROM note", String.class)).isEqualTo("histórico local");
        assertThat(backups.stageRestore(backup)).exists();

        Path invalid = temporary.resolve("invalid.sqlite");
        Files.writeString(invalid, "não é sqlite");
        assertThatThrownBy(() -> backups.stageRestore(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
}
