package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.TwoRaterBenchmarkExporter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("unchecked")
class TwoRaterBenchmarkExporterTest {
    @TempDir Path temporary;

    @Test
    void preservesBothOfficialRatingsWhenTheirMeanIsNotAnOfficialLevel() throws Exception {
        Path database = temporary.resolve("folder with spaces/corpus.db");
        String text = "A educação pública requer investimento e participação social.";
        String fingerprint;
        try (var corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(entry("one-a", "validation", text, "grader_a", 120),
                    entry("one-b", "validation", text, "grader_b", 160)), "fixture-v1");
            assertThat(corpus.benchmarkExamples("validation", 100)).isEmpty();
            fingerprint = corpus.fingerprint();
        }
        var exporter = new TwoRaterBenchmarkExporter();
        var document = exporter.export(database, "validation", 100, Set.of());
        assertThat(document).containsEntry("schemaVersion", 2).containsEntry("sampleCount", 1)
                .containsEntry("annotationCount", 2).containsEntry("corpusFingerprint", fingerprint)
                .containsEntry("humanLabelPolicy", "individual-raters-macro-average-v1");
        assertThat((List<String>) document.get("raterIds")).containsExactly("grader_a", "grader_b");
        var sample = ((List<Map<String, Object>>) document.get("samples")).getFirst();
        assertThat(sample).doesNotContainKey("humanScores");
        var ratings = (List<Map<String, Object>>) sample.get("humanEvaluations");
        assertThat(ratings).hasSize(2);
        assertThat((List<Integer>) ratings.getFirst().get("scores")).containsExactly(120, 120, 120, 120, 120);
        assertThat((List<Integer>) ratings.getLast().get("scores")).containsExactly(160, 160, 160, 160, 160);
        var json = new ObjectMapper();
        assertThat(json.writeValueAsString(document))
                .isEqualTo(json.writeValueAsString(exporter.export(database, "validation", 100, Set.of())));
        try (var corpus = new PublicHumanCorpus(database)) {
            assertThat(corpus.fingerprint()).isEqualTo(fingerprint);
        }
    }

    @Test
    void refusesTrainAndIncompleteHumanPanels() throws Exception {
        Path database = temporary.resolve("panel.db");
        try (var corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(entry("one-a", "validation", "Texto sem segundo avaliador.", "grader_a", 120)), "v1");
        }
        var exporter = new TwoRaterBenchmarkExporter();
        assertThatThrownBy(() -> exporter.export(database, "train", 100, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("validation ou test");
        assertThatThrownBy(() -> exporter.export(database, "validation", 100, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("dois avaliadores");
    }

    @Test
    void refusesInvalidIndividualLevelWithoutRoundingOrSilentlyDroppingTheEssay() throws Exception {
        Path database = temporary.resolve("invalid.db");
        String text = "Texto com anotação fora dos níveis oficiais.";
        try (var corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(entry("one-a", "validation", text, "grader_a", 140),
                    entry("one-b", "validation", text, "grader_b", 160)), "v1");
        }
        assertThatThrownBy(() -> new TwoRaterBenchmarkExporter().export(database, "validation", 100, Set.of()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("escala oficial");
    }

    @Test
    void excludesPreviouslyScoredEssaysBeforeDeterministicSelection() throws Exception {
        Path database = temporary.resolve("exclude.db");
        String old = "O acesso à escola exige políticas públicas contínuas.";
        String unseen = "A participação democrática amplia direitos de cidadania.";
        try (var corpus = new PublicHumanCorpus(database)) {
            corpus.importEntries(List.of(entry("old-a", "test", old, "grader_a", 120),
                    entry("old-b", "test", old, "grader_b", 160),
                    entry("new-a", "test", unseen, "grader_a", 80),
                    entry("new-b", "test", unseen, "grader_b", 120)), "v1");
        }
        var result = new TwoRaterBenchmarkExporter().export(database, "test", 1,
                Set.of(PublicHumanCorpus.hashText(old)));
        assertThat(result).containsEntry("sampleCount", 1).containsEntry("annotationCount", 2);
        var sample = ((List<Map<String, Object>>) result.get("samples")).getFirst();
        assertThat(sample).containsEntry("essayHash", PublicHumanCorpus.hashText(unseen));
    }

    private static PublicHumanCorpus.ImportEntry entry(String id, String split, String text, String grader, int score) {
        return new PublicHumanCorpus.ImportEntry("kamel-usp/aes_enem_dataset", "JBCS2025", id, split,
                "theme-" + split, "Educação e cidadania", text, 2024, "Apache-2.0",
                "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset", "https://huggingface.co/datasets/kamel-usp/aes_enem_dataset",
                new PublicHumanCorpus.HumanScore(grader, score, score, score, score, score, score * 5));
    }
}
