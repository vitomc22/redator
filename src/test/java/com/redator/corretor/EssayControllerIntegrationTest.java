package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.service.BudgetGuard;
import com.redator.corretor.service.ResponseSchemaValidator;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EssayControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void shouldCreateEssayAndEvaluate() {
        ResponseEntity<Map> created = restTemplate.postForEntity(
                "/api/essays",
                Map.of("temaId", 1, "text", "A educação é um direito fundamental e deve ser garantida para todos."),
                Map.class
        );

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsKey("essay");

        Map essay = (Map) created.getBody().get("essay");
        Number essayId = (Number) essay.get("id");

        ResponseEntity<Map> evaluation = restTemplate.postForEntity(
                "/api/essays/{id}/evaluate?profile=cheap",
                null,
                Map.class,
                essayId.longValue()
        );

        assertThat(evaluation.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(evaluation.getBody()).containsKey("runId");
        assertThat(evaluation.getBody().get("runId")).isNotNull();
    }

    @Test
    void shouldRejectUnknownProfile() {
        ResponseEntity<Map> created = restTemplate.postForEntity(
                "/api/essays",
                Map.of("temaId", 1, "text", "Texto suficiente para uma avaliacao do tema da educacao e da cidadania."),
                Map.class
        );

        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map essay = (Map) created.getBody().get("essay");
        Number essayId = (Number) essay.get("id");

        ResponseEntity<String> evaluation = restTemplate.postForEntity(
                "/api/essays/{id}/evaluate?profile=invalid",
                null,
                String.class,
                essayId.longValue()
        );

        assertThat(evaluation.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void shouldValidateModelResponseSchema() {
        ResponseSchemaValidator validator = new ResponseSchemaValidator();

        assertThatThrownBy(() -> validator.validate("{\"competencia\": 123, \"score\": \"x\"}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema");
    }

    @Test
    void shouldRejectBudgetExceeded() {
        BudgetGuard guard = new BudgetGuard(0.005);

        guard.reserve(EvaluationProfile.CHEAP);
        assertThatThrownBy(() -> guard.reserve(EvaluationProfile.NORMAL))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("orcamento");
    }

    @Test
    void shouldRunBenchmarkOnBatch() {
        ResponseEntity<Map> benchmark = restTemplate.postForEntity(
                "/api/benchmark",
                Map.of(
                        "temaId", 1,
                        "profile", "cheap",
                        "texts", java.util.List.of(
                                "A educacao e um direito fundamental para o desenvolvimento humano.",
                                "A tecnologia pode ampliar oportunidades, mas exige responsabilidade social."
                        )
                ),
                Map.class
        );

        assertThat(benchmark.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(benchmark.getBody()).containsKey("count");
        assertThat(((Number) benchmark.getBody().get("count")).intValue()).isEqualTo(2);
    }
}
