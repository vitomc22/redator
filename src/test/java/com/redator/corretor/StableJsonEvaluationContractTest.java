package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.model.Tema;
import com.redator.corretor.service.LocalEvaluationContract;
import com.redator.corretor.service.StableJsonEvaluationContract;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

class StableJsonEvaluationContractTest {
    @Test
    void serializesNestedMapsIndependentlyOfInsertionOrderWithoutReorderingArraysOrChangingText() {
        var forward = new LinkedHashMap<String, Object>();
        forward.put("a", "ação 😀"); forward.put("z", List.of("última", "primeira"));
        var reversed = new LinkedHashMap<String, Object>();
        reversed.put("z", List.of("última", "primeira")); reversed.put("a", "ação 😀");
        var contract = new StableJsonEvaluationContract();
        assertThat(contract.json(Map.of("nested", forward))).isEqualTo(contract.json(Map.of("nested", reversed)))
                .isEqualTo("{\"nested\":{\"a\":\"ação 😀\",\"z\":[\"última\",\"primeira\"]}}");
        assertThat(new LocalEvaluationContract().json(forward)).isNotEqualTo(new LocalEvaluationContract().json(reversed));
    }

    @Test
    void hasItsOwnPromptIdentityAndPreservesTheEvidenceSchemaAndLiteralCandidates() {
        var original = new LocalEvaluationContract(); var stable = new StableJsonEvaluationContract();
        String text = "A educação fortalece a cidadania. Ação pública amplia direitos.";
        assertThat(stable.promptHash()).isNotEqualTo(original.promptHash());
        assertThat(stable.rubricHash()).isEqualTo(original.rubricHash());
        assertThat(stable.schemaHash()).isEqualTo(original.schemaHash());
        assertThat(stable.schemaForText(text)).isEqualTo(original.schemaForText(text));
        assertThat(stable.evidenceCandidates(text)).isEqualTo(original.evidenceCandidates(text));
    }

    @Test
    void remainsOptInAndSpringInjectsTheStableContractOnlyWhenExplicitlyEnabled() {
        var runner = new ApplicationContextRunner().withUserConfiguration(Contracts.class);
        runner.run(context -> assertThat(context.getBean(LocalEvaluationContract.class)).isExactlyInstanceOf(LocalEvaluationContract.class));
        runner.withPropertyValues("app.llm.stable-json=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(LocalEvaluationContract.class)).isExactlyInstanceOf(StableJsonEvaluationContract.class);
        });
    }

    @Test
    void producesIdenticalMessageHashesAcrossIndependentJavaProcesses() throws Exception {
        String expected = null;
        for (int i = 0; i < 4; i++) {
            Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    Probe.class.getName()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
            String hash = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            assertThat(process.waitFor()).isZero(); assertThat(hash).matches("[a-f0-9]{64}");
            if (expected == null) expected = hash;
            else assertThat(hash).isEqualTo(expected);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @Import({LocalEvaluationContract.class, StableJsonEvaluationContract.class})
    static class Contracts { }

    public static class Probe {
        public static void main(String[] args) {
            var contract = new StableJsonEvaluationContract();
            var messages = contract.messages("A educação fortalece a cidadania. Ação pública amplia direitos.",
                    new Tema(1L, "Educação", "Direito à educação", "Proposta pública"));
            System.out.println(LocalEvaluationContract.hash(contract.json(messages)));
        }
    }
}
