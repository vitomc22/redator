package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:sqlite:./target/training-cycle-test.db")
class TrainingCycleIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Test
    void keepsLinkedAttemptsAndFreezesEvaluatedRevision() {
        Map<?, ?> first = createAttempt(null, "A educação pública amplia a cidadania e precisa de investimento contínuo.");
        long firstId = number(first, "id");
        long groupId = number(first, "group_id");

        ResponseEntity<Map> evaluation = rest.postForEntity(
                "/api/essays/{id}/evaluate?profile=normal", null, Map.class, firstId);
        assertThat(evaluation.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<Map> edit = rest.exchange("/api/essays/{id}/text", HttpMethod.PUT,
                new HttpEntity<>(Map.of("text", "Tentativa de sobrescrever.")), Map.class, firstId);
        assertThat(edit.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Map<?, ?> second = createAttempt(firstId,
                "A educação pública amplia a cidadania. Por isso, Estado e comunidade devem investir continuamente.");
        assertThat(number(second, "group_id")).isEqualTo(groupId);
        assertThat(number(second, "attempt_number")).isEqualTo(2L);

        ResponseEntity<List> attempts = rest.getForEntity(
                "/api/me/essays/{groupId}/attempts", List.class, groupId);
        assertThat(attempts.getBody()).hasSize(2);
    }

    @Test
    void supportsIndependentHumanReviewAndRejectsDuplicateActiveRequest() {
        Map<?, ?> essay = createAttempt(null,
                "A tecnologia na educação exige acesso democrático, formação docente e proteção dos estudantes.");
        long essayId = number(essay, "id");

        ResponseEntity<Map> requested = rest.postForEntity(
                "/api/essays/{id}/human-review-requests", Map.of(), Map.class, essayId);
        assertThat(requested.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long requestId = number(requested.getBody(), "id");

        ResponseEntity<Map> duplicate = rest.postForEntity(
                "/api/essays/{id}/human-review-requests", Map.of(), Map.class, essayId);
        assertThat(duplicate.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        HttpHeaders reviewer = reviewerHeaders();
        ResponseEntity<Map> claimed = rest.exchange(
                "/api/reviewer/review-requests/{id}/claim", HttpMethod.POST,
                new HttpEntity<>(null, reviewer), Map.class, requestId);
        assertThat(claimed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(claimed.getBody()).doesNotContainKey("referenceRunId");

        Map<String, Object> evaluation = Map.of(
                "scores", Map.of("C1", 160, "C2", 160, "C3", 120, "C4", 120, "C5", 160),
                "comments", Map.of("C3", "Desenvolver melhor a relação de causa e consequência."),
                "mainGuidance", "Aprofunde o terceiro argumento."
        );
        ResponseEntity<Map> draft = rest.exchange(
                "/api/reviewer/review-requests/{id}/draft", HttpMethod.POST,
                new HttpEntity<>(evaluation, reviewer), Map.class, requestId);
        assertThat(draft.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(draft.getBody()).containsEntry("status", "DRAFT");

        ResponseEntity<Map> resumed = rest.exchange(
                "/api/reviewer/review-requests/{id}", HttpMethod.GET,
                new HttpEntity<>(reviewer), Map.class, requestId);
        assertThat(resumed.getStatusCode()).isEqualTo(HttpStatus.OK);
        Map<?, ?> recoveredDraft = (Map<?, ?>) resumed.getBody().get("draft");
        assertThat(recoveredDraft.get("mainGuidance")).isEqualTo("Aprofunde o terceiro argumento.");
        assertThat(recoveredDraft.get("scores")).isInstanceOf(Map.class);

        ResponseEntity<Map> published = rest.exchange(
                "/api/reviewer/review-requests/{id}/publish", HttpMethod.POST,
                new HttpEntity<>(evaluation, reviewer), Map.class, requestId);
        assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(published.getBody()).containsEntry("total", 720);

        ResponseEntity<Map> status = rest.getForEntity(
                "/api/essays/{id}/human-review-request", Map.class, essayId);
        assertThat(status.getBody()).containsEntry("status", "COMPLETED");
        assertThat(status.getBody()).containsKey("evaluation");
    }

    @Test
    void protectsReviewerEndpoints() {
        ResponseEntity<Map> response = rest.getForEntity(
                "/api/reviewer/review-requests", Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    private Map<?, ?> createAttempt(Long parentId, String text) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("temaId", 1);
        body.put("text", text);
        body.put("title", "Versão de teste");
        body.put("sourceType", "text");
        if (parentId != null) body.put("parentEssayId", parentId);
        ResponseEntity<Map> response = rest.postForEntity("/api/attempts", body, Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (Map<?, ?>) response.getBody().get("essay");
    }

    private static HttpHeaders reviewerHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Reviewer-Key", "local-reviewer");
        headers.set("X-Reviewer-Id", "reviewer-test");
        return headers;
    }

    private static long number(Map<?, ?> map, String key) {
        return ((Number) map.get(key)).longValue();
    }
}
