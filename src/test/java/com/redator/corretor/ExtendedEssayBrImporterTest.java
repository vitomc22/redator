package com.redator.corretor;

import com.redator.corretor.service.ExtendedEssayBrImporter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExtendedEssayBrImporterTest {
    @Test
    void mapsOfficialCsvShapeAndJoinsParagraphs() throws Exception {
        String prompts = "id,title,description,category\n4,Tema quatro,Proposta completa,sociedade\n";
        String essays = "prompt,title,essay,c1,c2,c3,c4,c5,score\n"
                + "4,Titulo,\"['Primeiro parágrafo.', 'Segundo com \\\'citação\\\'.']\",160,120,120,160,200,760\n";

        var entries = new ExtendedEssayBrImporter().map(essays, prompts);

        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().essayText()).isEqualTo("Primeiro parágrafo.\n\nSegundo com 'citação'.");
        assertThat(entries.getFirst().score().total()).isEqualTo(760);
        assertThat(entries.getFirst().licenseSpdx()).isEqualTo("MIT");
        assertThat(entries.getFirst().split()).isEqualTo(ExtendedEssayBrImporter.splitForTheme("4"));
    }

    @Test
    void rejectsMalformedPythonList() {
        assertThatThrownBy(() -> ExtendedEssayBrImporter.parsePythonStringList("texto solto"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsUnsignedShaPrefixWhenFreezingThemeSplit() {
        assertThat(ExtendedEssayBrImporter.splitForTheme("6")).isIn("train", "validation", "test");
    }

    @Test
    void parsesPythonListThatMixesSingleAndDoubleQuotedParagraphs() {
        assertThat(ExtendedEssayBrImporter.parsePythonStringList("[\"Tem 'aspas'.\", 'Outro parágrafo.']"))
                .isEqualTo("Tem 'aspas'.\n\nOutro parágrafo.");
    }

    @Test
    void quarantinesRowsWhoseEssayListIsEmpty() throws Exception {
        String prompts = "id,title,description,category\n4,Tema,Proposta,sociedade\n";
        String essays = "prompt,title,essay,c1,c2,c3,c4,c5,score\n4,Sem texto,[],120,120,160,160,80,640\n";
        var mapping = new ExtendedEssayBrImporter().mapDetailed(essays, prompts);
        assertThat(mapping.entries()).isEmpty();
        assertThat(mapping.skippedRows()).isEqualTo(1);
    }
}
