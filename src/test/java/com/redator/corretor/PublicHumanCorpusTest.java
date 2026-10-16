package com.redator.corretor;

import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicHumanCorpusTest {

    @TempDir
    Path temp;

    @Test
    void separatesPublicCorpusDeduplicatesGradersAndProtectsReservedSplits() throws Exception {
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(temp.resolve("public-corpus.db"))) {
            var first = entry("a-1", "train", "Desinformação no Brasil", "Texto real sobre educação midiática e desinformação.", "original", 160);
            var secondGrader = entry("a-1-grader-b", "train", "Desinformação no Brasil", "  Texto real sobre educação midiática e DESINFORMAÇÃO.\n", "grader-b", 180);
            var test = entry("a-2", "test", "Desinformação no Brasil", "Texto reservado sobre algoritmos e notícias falsas.", "original", 120);

            var result = corpus.importEntries(List.of(first, secondGrader, test), "fixture-v1");

            assertThat(result.received()).isEqualTo(3);
            assertThat(result.insertedEssays()).isEqualTo(2);
            assertThat(result.insertedEvaluations()).isEqualTo(3);
            assertThat(corpus.statistics()).containsEntry("essays", 2L)
                    .containsEntry("humanEvaluations", 3L)
                    .containsEntry("ragEligible", 1L)
                    .containsEntry("benchmarkEligible", 1L);
            assertThat(corpus.findByText("  TEXTO real sobre educação midiática e desinformação. "))
                    .extracting(PublicHumanCorpus.ExistingEssay::split).isEqualTo("train");

            var retrieved = corpus.retrieve("educação midiática", "Desinformação no Brasil", 10, List.of());
            assertThat(retrieved).hasSize(1).allMatch(example -> example.split().equals("train"));
            assertThat(retrieved.getFirst().graderId()).isEqualTo("human-mean:2");
            assertThat(retrieved.getFirst().scores()).containsExactly(170, 170, 170, 170, 170);
            assertThat(retrieved.getFirst().total()).isEqualTo(850);

            var excluded = corpus.retrieve("educação midiática", "Desinformação no Brasil", 10,
                    List.of(PublicHumanCorpus.hashText(first.essayText())));
            assertThat(excluded).isEmpty();
        }
    }

    @Test
    void rejectsLeakageWhenNormalizedDuplicateCrossesSplits() throws Exception {
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(temp.resolve("leak.db"))) {
            corpus.importEntries(List.of(entry("train-1", "train", "Tema", "Mesmo texto integral.", "original", 160)), "v1");
            assertThatThrownBy(() -> corpus.importEntries(
                    List.of(entry("test-1", "test", "Tema", " mesmo TEXTO integral. ", "original", 160)), "v1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("atravessa splits");
        }
    }

    @Test
    void validatesHumanScoreScaleAndSum() {
        assertThatThrownBy(() -> new PublicHumanCorpus.HumanScore("human", 160, 160, 160, 160, 150, 790))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("multipla de 20");
        assertThatThrownBy(() -> new PublicHumanCorpus.HumanScore("human", 160, 160, 160, 160, 160, 900))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("soma");
    }

    private static PublicHumanCorpus.ImportEntry entry(
            String sourceId, String split, String theme, String text, String grader, int score
    ) {
        return new PublicHumanCorpus.ImportEntry(
                "kamel-usp/aes_enem_dataset", "fixture", sourceId, split, "theme-desinformacao",
                theme, text, 2024, "Apache-2.0",
                "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset",
                "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset",
                new PublicHumanCorpus.HumanScore(grader, score, score, score, score, score, score * 5)
        );
    }
}
