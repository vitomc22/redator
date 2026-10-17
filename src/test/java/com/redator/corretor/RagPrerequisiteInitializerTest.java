package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.config.RagPrerequisiteInitializer;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.RagService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagPrerequisiteInitializerTest {
    @TempDir Path temp;

    @Test
    void failsOllamaStartupWithoutUsableHumanCorpus() {
        var initializer = new RagPrerequisiteInitializer("ollama",
                new RagService(temp.resolve("missing.db"), true, 3, 1200));
        assertThatThrownBy(() -> initializer.run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RAG obrigatório");
    }

    @Test
    void acceptsEligibleTrainCorpus() throws Exception {
        Path database = temp.resolve("ready.db");
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(new PublicHumanCorpus.ImportEntry(
                    "essay-br", "fixture", "1", "train", "tema", "Tema",
                    "Texto humano autorizado.", 2024, "MIT", "https://example.test/license",
                    "https://example.test/source",
                    new PublicHumanCorpus.HumanScore("human", 160, 160, 160, 160, 160, 800))), "v1");
        }
        var initializer = new RagPrerequisiteInitializer("ollama", new RagService(database, true, 3, 1200));
        assertThatCode(() -> initializer.run(null)).doesNotThrowAnyException();
    }
}
