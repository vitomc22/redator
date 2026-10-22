package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.model.Tema;
import com.redator.corretor.service.GroundedEvaluationContract;
import com.redator.corretor.service.LocalEvaluationContract;
import com.redator.corretor.service.StableJsonEvaluationContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GroundedEvaluationContractTest {
    @Test
    void offersLiteralEvidenceFromBeginningDevelopmentAndConclusionOfLongEssays() {
        var contract = new GroundedEvaluationContract();
        List<String> sentences = IntStream.range(0, 60).mapToObj(i -> "Argumento " + i + " demonstra uma relação específica entre educação e cidadania.").toList();
        String text = String.join(" ", sentences);
        var candidates = contract.evidenceCandidates(text);
        assertThat(candidates).hasSize(24).doesNotHaveDuplicates().contains(sentences.getFirst(), sentences.getLast());
        assertThat(candidates).anyMatch(s -> sentences.subList(25, 35).contains(s));
        assertThat(candidates).allMatch(s -> text.contains(s) && s.codePointCount(0, s.length()) <= 120);
        assertThat(new LocalEvaluationContract().evidenceCandidates(text)).doesNotContain(sentences.getLast());
        var values = contract.schemaForText(text).path("properties").path("competences").path("items")
                .path("properties").path("evidence").path("items").path("enum");
        assertThat(values.toString()).contains(sentences.getLast());
    }

    @Test
    void preservesUnicodeAndShortTextWithoutSynthesizingEvidence() {
        var contract = new GroundedEvaluationContract();
        String text = "Educação amplia direitos. Ação pública é necessária. " + "🧑🏽‍🎓".repeat(50);
        assertThat(contract.evidenceCandidates(text)).allMatch(text::contains).allMatch(s -> {
            for (int i = 0; i < s.length(); i++) {
                if (Character.isHighSurrogate(s.charAt(i))) {
                    if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false;
                    i++;
                } else if (Character.isLowSurrogate(s.charAt(i))) return false;
            }
            return true;
        });
        assertThat(contract.evidenceCandidates("Conclusão breve.")).containsExactly("Conclusão breve.");
    }

    @Test
    void explicitlySelectedGroundedContractTakesPrecedenceOverTheOldSerializationFlag() {
        new ApplicationContextRunner().withUserConfiguration(LocalEvaluationContract.class,
                StableJsonEvaluationContract.class, GroundedEvaluationContract.class)
                .withPropertyValues("app.llm.contract=grounded-v2", "app.llm.stable-json=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(LocalEvaluationContract.class)).isExactlyInstanceOf(GroundedEvaluationContract.class);
                });
    }

    @Test
    void keepsTheWholeEssayAndVersionsOperationalGuidanceAndDeterministicSerialization() {
        var contract = new GroundedEvaluationContract();
        String text = "A educação é um direito e requer investimento público.";
        var messages = contract.messages(text, new Tema(1L, "Educação", "Direito à educação", "Proposta"));
        assertThat(messages.getLast().get("content")).contains(text);
        assertThat(messages.getFirst().get("content")).contains("C2", "repertório", "C5", "agente", "detalhamento");
        assertThat(contract.promptHash()).isNotEqualTo(new LocalEvaluationContract().promptHash());
        var a = new LinkedHashMap<String, Object>(); a.put("z", 1); a.put("a", 2);
        var b = new LinkedHashMap<String, Object>(); b.put("a", 2); b.put("z", 1);
        assertThat(contract.json(a)).isEqualTo(contract.json(b));
    }
}
