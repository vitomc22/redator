package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite:./target/training-card-test.db", "app.llm.provider=fake",
                "app.local-api-token.enabled=false"})
class TrainingCardIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM training_card_review");
        jdbc.update("DELETE FROM training_session");
        jdbc.update("DELETE FROM training_goal");
        jdbc.update("DELETE FROM training_card");
        jdbc.update("DELETE FROM competence_result");
        jdbc.update("DELETE FROM run");
        jdbc.update("DELETE FROM essay");
    }

    @Test
    void derivesCardsFromPublishedCompetencesAndDoesNotDuplicateThem() {
        long essayId = evaluatedEssay("cards-alice");
        HttpHeaders alice = user("cards-alice");

        ResponseEntity<List> created = exchange("/api/essays/" + essayId + "/training-cards",
                HttpMethod.POST, Map.of(), alice, List.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).hasSize(5);
        assertThat(created.getBody()).allSatisfy(card -> {
            Map<?, ?> value = (Map<?, ?>) card;
            for (String key : List.of("id", "competence", "question", "dueAt", "intervalDays"))
                assertThat(value.containsKey(key)).as(key).isTrue();
        });

        ResponseEntity<List> repeated = exchange("/api/essays/" + essayId + "/training-cards",
                HttpMethod.POST, Map.of(), alice, List.class);
        assertThat(repeated.getBody()).hasSize(5);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM training_card", Integer.class)).isEqualTo(5);
    }

    @Test
    void schedulesReviewsAndKeepsProfilesIsolated() {
        long essayId = evaluatedEssay("cards-owner");
        HttpHeaders owner = user("cards-owner");
        List cards = exchange("/api/essays/" + essayId + "/training-cards", HttpMethod.POST,
                Map.of(), owner, List.class).getBody();
        long cardId = ((Number) ((Map<?, ?>) cards.getFirst()).get("id")).longValue();

        ResponseEntity<Map> first = exchange("/api/training/cards/" + cardId + "/review", HttpMethod.POST,
                Map.of("quality", 5), owner, Map.class);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).containsEntry("repetitions", 1).containsEntry("intervalDays", 1);
        ResponseEntity<Map> second = exchange("/api/training/cards/" + cardId + "/review", HttpMethod.POST,
                Map.of("quality", 5), owner, Map.class);
        assertThat(second.getBody()).containsEntry("repetitions", 2).containsEntry("intervalDays", 6);

        ResponseEntity<Map> denied = exchange("/api/training/cards/" + cardId + "/review", HttpMethod.POST,
                Map.of("quality", 5), user("other-profile"), Map.class);
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<List> otherCards = exchange("/api/training/cards", HttpMethod.GET,
                null, user("other-profile"), List.class);
        assertThat(otherCards.getBody()).isEmpty();
    }

    @Test
    void persistsDailyGoalAndTracksAnExplainableSessionPerProfile() {
        long essayId = evaluatedEssay("goal-owner");
        HttpHeaders owner = user("goal-owner");
        List cards = exchange("/api/essays/" + essayId + "/training-cards", HttpMethod.POST,
                Map.of(), owner, List.class).getBody();
        long firstCard = ((Number) ((Map<?, ?>) cards.getFirst()).get("id")).longValue();

        ResponseEntity<Map> goal = exchange("/api/training/goal", HttpMethod.PUT,
                Map.of("dailyReviews", 3), owner, Map.class);
        assertThat(goal.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(goal.getBody()).containsEntry("dailyReviews", 3);

        Map session = exchange("/api/training/sessions", HttpMethod.POST, Map.of(), owner, Map.class).getBody();
        long sessionId = ((Number) session.get("id")).longValue();
        exchange("/api/training/cards/" + firstCard + "/review", HttpMethod.POST,
                Map.of("quality", 4, "sessionId", sessionId), owner, Map.class);
        Map completed = exchange("/api/training/sessions/" + sessionId + "/complete",
                HttpMethod.POST, Map.of(), owner, Map.class).getBody();
        assertThat(completed).containsEntry("status", "COMPLETED").containsEntry("reviewCount", 1);

        Map stats = exchange("/api/training/stats", HttpMethod.GET, null, owner, Map.class).getBody();
        assertThat(stats).containsEntry("dailyGoal", 3).containsEntry("reviewsToday", 1)
                .containsEntry("goalReached", false).containsEntry("completedSessions", 1);
        Map otherStats = exchange("/api/training/stats", HttpMethod.GET, null,
                user("goal-other"), Map.class).getBody();
        assertThat(otherStats).containsEntry("dailyGoal", 5).containsEntry("reviewsToday", 0)
                .containsEntry("completedSessions", 0);
    }

    private long evaluatedEssay(String profile) {
        HttpHeaders headers = user(profile);
        Map<String, Object> body = Map.of("temaId", 1, "title", "Treino", "sourceType", "text",
                "text", "A educação midiática fortalece a cidadania e o enfrentamento da desinformação.");
        Map created = exchange("/api/attempts", HttpMethod.POST, body, headers, Map.class).getBody();
        long essayId = ((Number) ((Map<?, ?>) created.get("essay")).get("id")).longValue();
        String now = OffsetDateTime.now().toString();
        jdbc.update("""
                INSERT INTO run(essay_id, profile, total, status, created_at, provider, model)
                VALUES (?, 'normal', 720, 'READY', ?, 'fixture', 'deterministic')
                """, essayId, now);
        long runId = jdbc.queryForObject("SELECT MAX(id) FROM run", Long.class);
        for (String competence : List.of("C1", "C2", "C3", "C4", "C5")) {
            jdbc.update("""
                    INSERT INTO competence_result(run_id, competencia, nota, nivel_sugerido, evidencias,
                        problemas, needs_review, motivos) VALUES (?, ?, 160, 4, 'trecho', 'Praticar este ponto', 0, 'motivo')
                    """, runId, competence);
        }
        return essayId;
    }

    private <T> ResponseEntity<T> exchange(String path, HttpMethod method, Object body,
                                            HttpHeaders headers, Class<T> type) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
    private static HttpHeaders user(String value) {
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Local-User", value); return headers;
    }
}
