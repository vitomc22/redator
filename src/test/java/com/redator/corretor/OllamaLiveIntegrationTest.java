package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Opt-in: performs a real generation against the local Ollama container. */
@EnabledIfEnvironmentVariable(named = "LLM_LIVE_TEST", matches = "true")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:sqlite:./target/ollama-live-test.db",
        "app.llm.provider=ollama"
})
class OllamaLiveIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldEvaluateAndPersistOneRealStructuredGeneration() {
        ResponseEntity<Map> runtime = restTemplate.getForEntity("/api/runtime", Map.class);
        assertThat(runtime.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(runtime.getBody()).containsEntry("provider", "ollama");
        assertThat(runtime.getBody()).containsEntry("model", "gemma4:e2b-it-q4_K_M");

        String text = "A educação pública é essencial para reduzir desigualdades no Brasil. "
                + "Entretanto, a falta de infraestrutura e a evasão escolar impedem que esse direito alcance todos. "
                + "Além disso, a formação continuada dos professores precisa acompanhar as mudanças sociais. "
                + "Portanto, o Ministério da Educação deve financiar escolas vulneráveis e oferecer formação docente, "
                + "com metas públicas e fiscalização dos conselhos de educação, para ampliar a permanência e a aprendizagem.";
        ResponseEntity<Map> created = restTemplate.postForEntity(
                "/api/essays", Map.of("temaId", 1, "text", text), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Number essayId = (Number) ((Map<?, ?>) created.getBody().get("essay")).get("id");

        ResponseEntity<Map> evaluated = restTemplate.postForEntity(
                "/api/essays/{id}/evaluate?profile=normal", null, Map.class, essayId.longValue());
        assertThat(evaluated.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evaluated.getBody()).containsEntry("provider", "ollama");
        assertThat(evaluated.getBody()).containsEntry("model", "gemma4:e2b-it-q4_K_M");
        java.util.List<String> competenceKeys = ((Map<?, ?>) evaluated.getBody().get("competences"))
                .keySet().stream().map(String::valueOf).toList();
        assertThat(competenceKeys)
                .containsExactlyInAnyOrder("C1", "C2", "C3", "C4", "C5");
        Number runId = (Number) evaluated.getBody().get("runId");

        ResponseEntity<Map> persisted = restTemplate.getForEntity("/api/runs/{id}", Map.class, runId.longValue());
        assertThat(persisted.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((java.util.List<?>) persisted.getBody().get("llmCalls")).hasSize(1);
        assertThat(persisted.getBody()).containsEntry("provider", "ollama");
    }
}
