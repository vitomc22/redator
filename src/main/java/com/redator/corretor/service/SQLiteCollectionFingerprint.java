package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.nio.charset.StandardCharsets;
import org.sqlite.SQLiteConfig;

/** Logical content hash independent of SQLite page layout and WAL checkpoints. */
public final class SQLiteCollectionFingerprint {
    private SQLiteCollectionFingerprint() { }
    public static String of(Path path) throws Exception {
        var config = new SQLiteConfig(); config.setReadOnly(true); config.setBusyTimeout(10_000);
        var digest = MessageDigest.getInstance("SHA-256"); var json = new ObjectMapper();
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + path.toAbsolutePath(), config.toProperties())) {
            db.setAutoCommit(false);
            var tables = new ArrayList<String>();
            try (var statement = db.createStatement(); var rows = statement.executeQuery(
                    "SELECT name,sql FROM sqlite_master WHERE type='table' AND substr(name,1,7)<>'sqlite_' ORDER BY name")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                    digest.update((rows.getString(1) + "\0" + rows.getString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
            for (String table : tables) {
                String quoted = '"' + table.replace("\"", "\"\"") + '"';
                int columns;
                try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + quoted + " LIMIT 0")) {
                    columns = rows.getMetaData().getColumnCount();
                }
                String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                        .collect(java.util.stream.Collectors.joining(","));
                digest.update((table + "\n").getBytes(StandardCharsets.UTF_8));
                try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT * FROM " + quoted + " ORDER BY " + order)) {
                    while (rows.next()) {
                        Object[] values = new Object[columns];
                        for (int i = 0; i < columns; i++) values[i] = rows.getObject(i + 1);
                        digest.update(json.writeValueAsBytes(values)); digest.update((byte) '\n');
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
