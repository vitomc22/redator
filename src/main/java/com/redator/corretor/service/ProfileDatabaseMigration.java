package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/** Activates complete exports once; preserves both the legacy database and migration snapshot. */
public final class ProfileDatabaseMigration {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final LocalDataDirectories directories;
    public ProfileDatabaseMigration(LocalDataDirectories directories) { this.directories = directories; }

    public synchronized void activate() {
        Path source = directories.root().resolve("redator.db");
        if (!Files.isRegularFile(source)) return;
        Path prepared = directories.runtime().resolve("profile-migration-prepared");
        Path active = directories.runtime().resolve("profile-migration-active.json");
        try {
            if (!Files.exists(prepared)) new ProfileCollectionExporter().export(source, prepared);
            Path manifestFile = prepared.resolve("manifest.json");
            var manifest = JSON.readValue(Files.readString(manifestFile), Map.class);
            if (!"PREPARED_NOT_ACTIVATED".equals(manifest.get("status"))
                    || !source.toString().equals(manifest.get("source")))
                throw new IllegalStateException("Manifesto de migração inválido");
            var snapshotEntry = (Map<?, ?>) manifest.get("legacySnapshot");
            Path snapshot = prepared.resolve("legacy-snapshot.sqlite");
            if (!hash(snapshot).equals(snapshotEntry.get("sha256"))) throw new IllegalStateException("Snapshot de migração divergente");
            String logicalHash;
            if (manifest.containsKey("legacySourceSnapshot")) {
                var rawEntry = (Map<?, ?>) manifest.get("legacySourceSnapshot");
                Path raw = prepared.resolve("legacy-source.sqlite");
                if (!hash(raw).equals(rawEntry.get("sha256")))
                    throw new IllegalStateException("Snapshot original de migração divergente");
                logicalHash = SQLiteCollectionFingerprint.of(raw);
                if (!logicalHash.equals(manifest.get("sourceLogicalFingerprint")))
                    throw new IllegalStateException("Impressão da origem de migração divergente");
            } else {
                // Previously prepared V10 exports used the same schema as their source.
                logicalHash = SQLiteCollectionFingerprint.of(snapshot);
            }
            if (!logicalHash.equals(SQLiteCollectionFingerprint.of(source)))
                throw new IllegalStateException("O banco legado mudou após a preparação; preserve as duas coleções e prepare uma nova migração");
            if (Files.exists(active)) {
                var marker = JSON.readValue(Files.readString(active), Map.class);
                if (!"ACTIVE".equals(marker.get("status")) || !hash(manifestFile).equals(marker.get("manifestSha256"))
                        || !logicalHash.equals(marker.get("legacyLogicalFingerprint")))
                    throw new IllegalStateException("Registro de ativação divergente");
                return;
            }
            var profiles = (Map<String, Map<String, Object>>) manifest.get("profiles");
            for (var entry : profiles.entrySet()) {
                Path target = directories.profile(entry.getKey()).resolve("redator.sqlite");
                Path exported = prepared.resolve("profiles").resolve(entry.getKey()).resolve("redator.sqlite");
                String expected = entry.getValue().get("sha256").toString();
                if (!hash(exported).equals(expected)) throw new IllegalStateException("Coleção preparada divergente");
                if (Files.exists(target)) {
                    if (!hash(target).equals(expected)) throw new IllegalStateException("Coleção privada existente difere da migração: " + entry.getKey());
                } else {
                    Path temporary = target.resolveSibling("migration-" + UUID.randomUUID() + ".sqlite");
                    try { Files.copy(exported, temporary); Files.move(temporary, target); }
                    finally { Files.deleteIfExists(temporary); }
                }
            }
            if (!logicalHash.equals(SQLiteCollectionFingerprint.of(source)))
                throw new IllegalStateException("O banco legado mudou durante a ativação; nenhuma ativação foi publicada");
            Path marker = active.resolveSibling("profile-migration-active-" + UUID.randomUUID() + ".tmp");
            try {
                Files.writeString(marker, JSON.writeValueAsString(Map.of("schemaVersion", 1, "status", "ACTIVE",
                        "manifestSha256", hash(manifestFile), "legacyLogicalFingerprint", logicalHash)) + "\n");
                Files.move(marker, active);
            } finally { Files.deleteIfExists(marker); }
        } catch (IllegalStateException error) { throw error; }
        catch (Exception error) { throw new IllegalStateException("Não foi possível ativar bancos privados por perfil", error); }
    }

    private static String hash(Path path) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = new byte[8192]; for (int n; (n = input.read(bytes)) != -1;) digest.update(bytes, 0, n);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
