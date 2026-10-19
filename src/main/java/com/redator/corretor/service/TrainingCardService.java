package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrainingCardService {
    private static final Map<String, String> NAMES = Map.of(
            "C1", "escrita formal", "C2", "tema e repertório", "C3", "argumentação",
            "C4", "coesão", "C5", "proposta de intervenção");
    private final JdbcTemplate jdbc;
    private final LocalUserContext users;
    private final Clock clock;
    private final ObjectMapper mapper;

    @Autowired
    public TrainingCardService(JdbcTemplate jdbc, LocalUserContext users, ObjectMapper mapper) {
        this(jdbc, users, mapper, Clock.systemUTC());
    }

    TrainingCardService(JdbcTemplate jdbc, LocalUserContext users, ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public List<Map<String, Object>> generate(long essayId) {
        assertOwned(essayId);
        Long runId = null;
        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT id FROM run WHERE essay_id=? AND status='READY' ORDER BY id DESC LIMIT 1", essayId);
        List<Map<String, Object>> competences;
        if (!runs.isEmpty()) {
            runId = ((Number) runs.getFirst().get("id")).longValue();
            competences = jdbc.queryForList("""
                    SELECT competencia, nota, problemas, motivos FROM competence_result
                    WHERE run_id=? ORDER BY competencia
                    """, runId);
        } else {
            competences = humanCompetences(essayId);
        }
        if (competences.size() != 5)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "É necessária uma avaliação C1–C5 publicada para gerar cartões");
        String now = now();
        for (Map<String, Object> competence : competences) {
            String code = String.valueOf(competence.get("competencia"));
            int score = ((Number) competence.get("nota")).intValue();
            String weakness = firstUseful(competence.get("problemas"), competence.get("motivos"));
            String question = "Como melhorar " + code + " — " + NAMES.getOrDefault(code, code) + " nesta redação?";
            String answer = weakness.isBlank()
                    ? "Releia o descritor da competência e compare o texto com a evidência da correção. Nota atual: " + score + "."
                    : weakness + " Nota de referência: " + score + "/200.";
            jdbc.update("""
                    INSERT INTO training_card(student_id, essay_id, competence, question, answer, source_run_id,
                        due_at, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(student_id, essay_id, competence) DO UPDATE SET
                        question=excluded.question, answer=excluded.answer, source_run_id=excluded.source_run_id
                    """, users.current(), essayId, code, question, answer, runId, now, now);
        }
        return cards(false);
    }

    private List<Map<String, Object>> humanCompetences(long essayId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT he.scores_json AS scoresJson, he.comments_json AS commentsJson,
                       he.main_guidance AS mainGuidance
                FROM human_evaluation he JOIN human_review_request hr ON hr.id=he.request_id
                WHERE hr.essay_id=? AND he.status='PUBLISHED' ORDER BY he.id DESC LIMIT 1
                """, essayId);
        if (rows.isEmpty()) return List.of();
        try {
            Map<String, Integer> scores = mapper.readValue(String.valueOf(rows.getFirst().get("scoresJson")),
                    new TypeReference<>() {});
            Map<String, String> comments = mapper.readValue(String.valueOf(rows.getFirst().get("commentsJson")),
                    new TypeReference<>() {});
            String guidance = String.valueOf(rows.getFirst().getOrDefault("mainGuidance", ""));
            return NAMES.keySet().stream().sorted().map(code -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("competencia", code); value.put("nota", scores.get(code));
                value.put("problemas", comments.getOrDefault(code, "")); value.put("motivos", guidance);
                return value;
            }).toList();
        } catch (Exception ex) {
            throw new IllegalStateException("Correção humana publicada possui formato inválido", ex);
        }
    }

    public List<Map<String, Object>> cards(boolean dueOnly) {
        String sql = """
                SELECT tc.id, tc.essay_id AS essayId, tc.competence, tc.question, tc.answer,
                       tc.repetitions, tc.interval_days AS intervalDays, tc.ease_factor AS easeFactor,
                       tc.due_at AS dueAt, tc.last_reviewed_at AS lastReviewedAt, t.titulo AS theme
                FROM training_card tc JOIN essay e ON e.id=tc.essay_id JOIN tema t ON t.id=e.tema_id
                WHERE tc.student_id=?
                """ + (dueOnly ? " AND tc.due_at<=?" : "") + " ORDER BY tc.due_at, tc.id";
        return dueOnly ? jdbc.queryForList(sql, users.current(), now()) : jdbc.queryForList(sql, users.current());
    }

    @Transactional
    public Map<String, Object> review(long cardId, int quality, Long sessionId) {
        if (quality < 0 || quality > 5) throw new IllegalArgumentException("Qualidade deve ficar entre 0 e 5");
        Map<String, Object> card = ownedCard(cardId);
        if (sessionId != null) activeSession(sessionId);
        int repetitions = ((Number) card.get("repetitions")).intValue();
        int previousInterval = ((Number) card.get("interval_days")).intValue();
        double ease = ((Number) card.get("ease_factor")).doubleValue();
        int nextInterval;
        if (quality < 3) {
            repetitions = 0; nextInterval = 1;
        } else {
            repetitions++;
            nextInterval = repetitions == 1 ? 1 : repetitions == 2 ? 6
                    : Math.max(1, (int) Math.round(previousInterval * ease));
        }
        ease = Math.max(1.3, ease + 0.1 - (5 - quality) * (0.08 + (5 - quality) * 0.02));
        String reviewedAt = now();
        String dueAt = OffsetDateTime.ofInstant(clock.instant().plus(nextInterval, ChronoUnit.DAYS), ZoneOffset.UTC).toString();
        jdbc.update("""
                UPDATE training_card SET repetitions=?, interval_days=?, ease_factor=?, due_at=?, last_reviewed_at=?
                WHERE id=? AND student_id=?
                """, repetitions, nextInterval, ease, dueAt, reviewedAt, cardId, users.current());
        jdbc.update("""
                INSERT INTO training_card_review(card_id, quality, previous_interval_days, next_interval_days,
                    reviewed_at, session_id) VALUES (?, ?, ?, ?, ?, ?)
                """, cardId, quality, previousInterval, nextInterval, reviewedAt, sessionId);
        if (sessionId != null) jdbc.update("UPDATE training_session SET review_count=review_count+1 WHERE id=?", sessionId);
        Map<String, Object> result = new LinkedHashMap<>(ownedCard(cardId));
        result.put("dueAt", result.remove("due_at"));
        result.put("intervalDays", result.remove("interval_days"));
        result.put("easeFactor", result.remove("ease_factor"));
        result.put("lastReviewedAt", result.remove("last_reviewed_at"));
        return result;
    }

    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("total", jdbc.queryForObject("SELECT COUNT(*) FROM training_card WHERE student_id=?", Integer.class, users.current()));
        result.put("due", jdbc.queryForObject("SELECT COUNT(*) FROM training_card WHERE student_id=? AND due_at<=?", Integer.class, users.current(), now()));
        result.put("reviews", jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_card_review r JOIN training_card c ON c.id=r.card_id
                WHERE c.student_id=?
                """, Integer.class, users.current()));
        String today = now().substring(0, 10);
        int dailyGoal = jdbc.query("SELECT daily_reviews FROM training_goal WHERE student_id=?",
                rs -> rs.next() ? rs.getInt(1) : 5, users.current());
        int reviewsToday = jdbc.queryForObject("""
                SELECT COUNT(*) FROM training_card_review r JOIN training_card c ON c.id=r.card_id
                WHERE c.student_id=? AND substr(r.reviewed_at,1,10)=?
                """, Integer.class, users.current(), today);
        result.put("dailyGoal", dailyGoal);
        result.put("reviewsToday", reviewsToday);
        result.put("remainingToday", Math.max(0, dailyGoal - reviewsToday));
        result.put("goalReached", reviewsToday >= dailyGoal);
        result.put("completedSessions", jdbc.queryForObject(
                "SELECT COUNT(*) FROM training_session WHERE student_id=? AND status='COMPLETED'",
                Integer.class, users.current()));
        return result;
    }

    @Transactional
    public Map<String, Object> setGoal(int dailyReviews) {
        if (dailyReviews < 1 || dailyReviews > 100)
            throw new IllegalArgumentException("A meta diária deve ficar entre 1 e 100 revisões");
        jdbc.update("""
                INSERT INTO training_goal(student_id, daily_reviews, updated_at) VALUES (?, ?, ?)
                ON CONFLICT(student_id) DO UPDATE SET daily_reviews=excluded.daily_reviews,
                    updated_at=excluded.updated_at
                """, users.current(), dailyReviews, now());
        return Map.of("dailyReviews", dailyReviews, "updatedAt", now());
    }

    @Transactional
    public Map<String, Object> startSession() {
        List<Map<String, Object>> active = jdbc.queryForList("""
                SELECT id, status, review_count AS reviewCount, started_at AS startedAt
                FROM training_session WHERE student_id=? AND status='ACTIVE' ORDER BY id DESC LIMIT 1
                """, users.current());
        if (!active.isEmpty()) return active.getFirst();
        jdbc.update("INSERT INTO training_session(student_id, status, started_at) VALUES (?, 'ACTIVE', ?)",
                users.current(), now());
        return activeSession(jdbc.queryForObject("SELECT last_insert_rowid()", Long.class));
    }

    @Transactional
    public Map<String, Object> completeSession(long sessionId) {
        activeSession(sessionId);
        String completedAt = now();
        jdbc.update("UPDATE training_session SET status='COMPLETED', completed_at=? WHERE id=? AND student_id=?",
                completedAt, sessionId, users.current());
        return jdbc.queryForMap("""
                SELECT id, status, review_count AS reviewCount, started_at AS startedAt,
                       completed_at AS completedAt FROM training_session WHERE id=? AND student_id=?
                """, sessionId, users.current());
    }

    private void assertOwned(long essayId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM essay WHERE id=? AND student_id=?", Integer.class,
                essayId, users.current());
        if (count == null || count == 0) throw notFound();
    }
    private Map<String, Object> ownedCard(long cardId) {
        try { return jdbc.queryForMap("SELECT * FROM training_card WHERE id=? AND student_id=?", cardId, users.current()); }
        catch (EmptyResultDataAccessException ex) { throw notFound(); }
    }
    private Map<String, Object> activeSession(long sessionId) {
        try {
            return jdbc.queryForMap("""
                    SELECT id, status, review_count AS reviewCount, started_at AS startedAt
                    FROM training_session WHERE id=? AND student_id=? AND status='ACTIVE'
                    """, sessionId, users.current());
        } catch (EmptyResultDataAccessException ex) { throw notFound(); }
    }
    private static String firstUseful(Object first, Object second) {
        for (Object value : List.of(first == null ? "" : first, second == null ? "" : second)) {
            String text = String.valueOf(value).strip();
            if (!text.isBlank()) return text.split(" \\| ")[0];
        }
        return "";
    }
    private String now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(); }
    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso não encontrado");
    }
}
