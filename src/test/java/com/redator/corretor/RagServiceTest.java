package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.RagService;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagServiceTest {
    @TempDir Path temp;

    @Test
    void retrievesOnlyHumanTrainReferencesAndExcludesCurrentEssay() throws Exception {
        Path database = temp.resolve("corpus.db");
        String current = "Redação atual sobre educação midiática e notícias falsas.";
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(
                    entry("train-1", "train", "A educação midiática ajuda a combater notícias falsas.", 180),
                    entry("test-1", "test", "Algoritmos ampliam a circulação de desinformação.", 120),
                    entry("same", "train", current, 160)
            ), "rag-fixture-v1");
        }

        RagService service = new RagService(database, true, 3, 1200);
        RagService.Context context = service.retrieve(current, "Desinformação no Brasil");

        assertThat(context.enabled()).isTrue();
        assertThat(context.corpusFingerprint()).matches("[a-f0-9]{64}");
        assertThat(context.references()).hasSize(1);
        assertThat(context.references().getFirst().sourceId()).isEqualTo("train-1");
        assertThat(context.references().getFirst().scores()).containsExactly(180, 180, 180, 180, 180);
        assertThat(context.promptExamples().getFirst()).containsEntry("humanTotal", 900);
        assertThat(service.status()).containsEntry("available", true).containsEntry("ragEligible", 2L);
    }

    @Test
    void failsClosedWhenRequiredCorpusIsMissing() {
        RagService service = new RagService(temp.resolve("missing.db"), true, 3, 1200);
        assertThatThrownBy(() -> service.retrieve("Texto", "Tema"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("obrigatório não encontrado");
    }

    private static PublicHumanCorpus.ImportEntry entry(String sourceId, String split, String text, int score) {
        return new PublicHumanCorpus.ImportEntry(
                "essay-br", "fixture", sourceId, split, "desinformacao", "Desinformação no Brasil",
                text, 2024, "MIT", "https://github.com/lplnufpi/essay-br",
                "https://github.com/lplnufpi/essay-br",
                new PublicHumanCorpus.HumanScore("human", score, score, score, score, score, score * 5));
    }
}
