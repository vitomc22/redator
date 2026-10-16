package com.redator.corretor;

import com.redator.corretor.service.KamelDatasetImporter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KamelDatasetImporterTest {

    @Test
    void mapsOfficialKamelRowWithoutNetwork() {
        var importer = new KamelDatasetImporter();
        var rows = List.of(Map.<String, Object>of(
                "id", "8.html",
                "id_prompt", "posse-de-armas",
                "essay_text", "Uma redação pública corrigida por humano.",
                "grades", List.of(160, 120, 120, 120, 80, 600),
                "essay_year", 2019,
                "prompt", "Posse de armas: segurança ou perigo?",
                "reference", "grader_a"
        ));

        var entry = importer.mapRows("JBCS2025", "train", rows, 0).getFirst();

        assertThat(entry.sourceId()).isEqualTo("JBCS2025:train:8.html");
        assertThat(entry.themeGroup()).isEqualTo("posse-de-armas");
        assertThat(entry.licenseSpdx()).isEqualTo("Apache-2.0");
        assertThat(entry.score().graderId()).isEqualTo("grader_a");
        assertThat(entry.score().total()).isEqualTo(600);
    }

    @Test
    void rejectsRowsWithoutSixConsistentGrades() {
        var importer = new KamelDatasetImporter();
        var row = Map.<String, Object>of(
                "id", "broken", "id_prompt", "tema", "essay_text", "Texto",
                "grades", List.of(160, 160), "prompt", "Tema"
        );
        assertThatThrownBy(() -> importer.mapRows("JBCS2025", "train", List.of(row), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("grades exige");
    }
}
