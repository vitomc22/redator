package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.LocalDataDirectories;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalDataDirectoriesTest {
    @TempDir Path temporary;

    @Test
    void createsPortableLayoutAndKeepsProfilesInsideItsRoot() {
        LocalDataDirectories directories = new LocalDataDirectories(temporary.resolve("Redator"));

        assertThat(Files.isDirectory(directories.backups())).isTrue();
        assertThat(Files.isDirectory(directories.corpus())).isTrue();
        assertThat(Files.isDirectory(directories.inbox())).isTrue();
        assertThat(Files.isDirectory(directories.outbox())).isTrue();
        assertThat(directories.profile("estudante-01")).startsWith(directories.profiles());
        assertThatThrownBy(() -> directories.profile("../../fora"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
