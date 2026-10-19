package com.redator.corretor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Cria snapshots consistentes do SQLite e prepara restauração para o launcher aplicar offline. */
@Service
public class LocalBackupService {
    private final JdbcTemplate jdbc;
    private final LocalDataDirectories directories;

    public LocalBackupService(JdbcTemplate jdbc, LocalDataDirectories directories) {
        this.jdbc = jdbc;
        this.directories = directories;
    }

    public synchronized Path createBackup() {
        String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path destination = directories.backups().resolve(
                "redator-" + timestamp + "-" + UUID.randomUUID().toString().substring(0, 8) + ".sqlite");
        jdbc.execute("PRAGMA wal_checkpoint(FULL)");
        jdbc.execute("VACUUM INTO '" + sqlPath(destination) + "'");
        verify(destination);
        return destination;
    }

    public synchronized Path stageRestore(Path source) {
        Path normalized = source.toAbsolutePath().normalize();
        if (!Files.isRegularFile(normalized)) throw new IllegalArgumentException("Backup não encontrado");
        verify(normalized);
        Path staged = directories.runtime().resolve("restore-next-start.sqlite");
        try {
            Files.copy(normalized, staged, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível preparar a restauração", ex);
        }
        verify(staged);
        return staged;
    }

    static void verify(Path database) {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
             var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA integrity_check")) {
            if (!result.next() || !"ok".equalsIgnoreCase(result.getString(1))) {
                throw new IllegalArgumentException("Backup SQLite inválido");
            }
        } catch (SQLException ex) {
            throw new IllegalArgumentException("Backup SQLite inválido", ex);
        }
    }

    private static String sqlPath(Path path) {
        return path.toAbsolutePath().toString().replace("'", "''");
    }
}
