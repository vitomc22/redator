package com.redator.corretor.config;

import com.redator.corretor.service.LocalBackupService;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

/** Applies an already validated restore before Spring opens the authoritative SQLite database. */
public class StartupRestoreApplier implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String configured = environment.getProperty("app.data-directory", "./data");
        apply(Path.of(configured));
    }

    public static boolean apply(Path configuredRoot) {
        Path root = configuredRoot.toAbsolutePath().normalize();
        Path staged = root.resolve("runtime/restore-next-start.sqlite");
        if (!Files.isRegularFile(staged)) return false;
        LocalBackupService.verifyCollection(staged);
        Path live = root.resolve("redator.db");
        Path backups = root.resolve("backups");
        try {
            Files.createDirectories(backups);
            if (Files.isRegularFile(live)) {
                LocalBackupService.verifyCollection(live);
                String timestamp = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
                Path safety = backups.resolve("pre-restore-" + timestamp + ".sqlite");
                try (var connection = DriverManager.getConnection("jdbc:sqlite:" + live);
                     var statement = connection.createStatement()) {
                    statement.execute("PRAGMA wal_checkpoint(FULL)");
                    statement.execute("VACUUM INTO '" + safety.toString().replace("'", "''") + "'");
                }
                LocalBackupService.verifyCollection(safety);
            }
            move(staged, live);
            Files.deleteIfExists(Path.of(live + "-wal"));
            Files.deleteIfExists(Path.of(live + "-shm"));
            LocalBackupService.verifyCollection(live);
            return true;
        } catch (Exception exception) {
            throw new IllegalStateException("Falha ao aplicar restauração local antes da inicialização", exception);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
