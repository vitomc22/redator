package com.redator.corretor.service;

import java.io.InputStream;
import java.nio.file.Path;
import java.sql.DriverManager;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.sqlite.SQLiteConfig;

@Service
@Primary
@ConditionalOnProperty(name = "app.profile-databases", havingValue = "true")
public class ProfileBackupService extends LocalBackupService {
    private final JdbcTemplate jdbc;
    private final LocalDataDirectories directories;
    private final LocalUserContext users;
    public ProfileBackupService(JdbcTemplate jdbc, LocalDataDirectories directories, LocalUserContext users) {
        super(jdbc, directories); this.jdbc = jdbc; this.directories = directories; this.users = users;
    }
    @Override public synchronized Path createBackup() { return scoped().createBackup(); }
    @Override public synchronized Path stageRestore(Path source) { return scoped().stageRestore(source); }
    @Override public synchronized Path stageRestore(InputStream source, long size) { return scoped().stageRestore(source, size); }

    private LocalBackupService scoped() {
        String profile = users.current();
        return new LocalBackupService(jdbc, new LocalDataDirectories(directories.profile(profile))) {
            @Override public synchronized Path stageRestore(Path source) {
                verifyCollection(source);
                try { ProfileCollectionExporter.verifyCollection(source); }
                catch (Exception error) { throw new IllegalArgumentException("Schema ou relações do backup de perfil inválidos", error); }
                var config = new SQLiteConfig(); config.setReadOnly(true);
                try (var db = DriverManager.getConnection("jdbc:sqlite:" + source.toAbsolutePath(), config.toProperties());
                     var statement = db.prepareStatement("""
                             SELECT student_id FROM essay UNION SELECT student_id FROM training_goal
                             UNION SELECT student_id FROM training_session UNION SELECT student_id FROM training_card
                             UNION SELECT student_id FROM review_package
                             """ ); var rows = statement.executeQuery()) {
                    while (rows.next()) if (!profile.equals(rows.getString(1)))
                        throw new IllegalArgumentException("O backup contém dados de outro perfil; use a migração de coleções");
                } catch (IllegalArgumentException error) { throw error; }
                catch (Exception error) { throw new IllegalArgumentException("Backup de perfil inválido", error); }
                return super.stageRestore(source);
            }
        };
    }
}
