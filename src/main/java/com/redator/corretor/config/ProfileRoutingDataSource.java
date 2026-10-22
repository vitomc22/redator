package com.redator.corretor.config;

import com.redator.corretor.service.LocalDataDirectories;
import com.redator.corretor.service.LocalUserContext;
import com.redator.corretor.service.ProfileDatabaseMigration;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.sqlite.SQLiteConfig;

/** Connections follow request identity; Spring binds a single connection for each transaction. */
public final class ProfileRoutingDataSource extends AbstractDataSource {
    private final LocalDataDirectories directories;
    private final LocalUserContext users;
    private final Set<String> initialized = new HashSet<>();
    public ProfileRoutingDataSource(LocalDataDirectories directories, LocalUserContext users) {
        this.directories = directories; this.users = users;
        new ProfileDatabaseMigration(directories).activate();
    }
    @Override public Connection getConnection() throws SQLException {
        String profile = users.current();
        Path root = directories.profile(profile), database = root.resolve("redator.sqlite");
        initialize(profile, root, database);
        var config = new SQLiteConfig(); config.enforceForeignKeys(true); config.setBusyTimeout(10_000);
        return DriverManager.getConnection("jdbc:sqlite:" + database, config.toProperties());
    }
    @Override public Connection getConnection(String username, String password) throws SQLException { return getConnection(); }

    private synchronized void initialize(String profile, Path root, Path database) {
        if (initialized.contains(profile)) return;
        StartupRestoreApplier.apply(root, "redator.sqlite");
        var raw = new DriverManagerDataSource("jdbc:sqlite:" + database);
        Flyway.configure().dataSource(raw).load().migrate();
        new InitialDataLoader(new JdbcTemplate(raw)).run(null);
        initialized.add(profile);
    }
}
