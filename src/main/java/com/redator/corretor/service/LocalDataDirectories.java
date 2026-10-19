package com.redator.corretor.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolve e prepara somente diretórios pertencentes ao Redator. */
@Component
public class LocalDataDirectories {
    private static final Pattern PROFILE = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}");
    private final Path root;

    @Autowired
    public LocalDataDirectories(@Value("${app.data-directory:./data}") String configuredRoot) {
        this(Path.of(configuredRoot));
    }

    public LocalDataDirectories(Path configuredRoot) {
        root = configuredRoot.toAbsolutePath().normalize();
        try {
            for (Path directory : List.of(profiles(), corpus(), backups(), inbox(), outbox(), logs(), runtime())) {
                Files.createDirectories(directory);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível preparar os dados locais em " + root, ex);
        }
    }

    public Path root() { return root; }
    public Path profiles() { return root.resolve("profiles"); }
    public Path corpus() { return root.resolve("corpus"); }
    public Path backups() { return root.resolve("backups"); }
    public Path inbox() { return root.resolve("exchange/inbox"); }
    public Path outbox() { return root.resolve("exchange/outbox"); }
    public Path logs() { return root.resolve("logs"); }
    public Path runtime() { return root.resolve("runtime"); }

    public Path profile(String profileId) {
        if (profileId == null || !PROFILE.matcher(profileId).matches()) {
            throw new IllegalArgumentException("Identificador local inválido");
        }
        Path path = profiles().resolve(profileId).normalize();
        if (!path.startsWith(profiles())) throw new IllegalArgumentException("Identificador local inválido");
        try {
            return Files.createDirectories(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Não foi possível preparar o perfil local", ex);
        }
    }
}
