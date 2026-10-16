package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CreateAttemptRequest;
import com.redator.corretor.model.HumanEvaluationRequest;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TrainingCycleService {
    public static final String PILOT_STUDENT = "pilot-student";
    private static final List<String> COMPETENCES = List.of("C1", "C2", "C3", "C4", "C5");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;

    public TrainingCycleService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Map<String, Object> createAttempt(CreateAttemptRequest request) {
        Long groupId = null;
        int attempt = 1;
        if (request.parentEssayId() != null) {
            Map<String, Object> parent = essayRow(request.parentEssayId());
            if (!PILOT_STUDENT.equals(parent.get("student_id"))) throw notFound();
            if (((Number) parent.get("tema_id")).longValue() != request.temaId()) {
                throw new IllegalArgumentException("Uma reescrita deve manter o mesmo tema");
            }
            groupId = ((Number) parent.get("group_id")).longValue();
            Integer max = jdbc.queryForObject("SELECT COALESCE(MAX(attempt_number), 0) FROM essay WHERE group_id = ?", Integer.class, groupId);
            attempt = (max == null ? 0 : max) + 1;
        }
        String source = normalizeSource(request.sourceType());
        String status = "text".equals(source) ? "READY" : "NEEDS_REVIEW";
        String now = now();
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        Long finalGroupId = groupId;
        int finalAttempt = attempt;
        jdbc.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO essay (tema_id, text, status, created_at, student_id, title, source_type,
                                       group_id, parent_essay_id, attempt_number, revision_hash, approved_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setLong(1, request.temaId());
            ps.setString(2, request.text().strip());
            ps.setString(3, status);
            ps.setString(4, now);
            ps.setString(5, PILOT_STUDENT);
            ps.setString(6, blankToNull(request.title()));
            ps.setString(7, source);
            if (finalGroupId == null) ps.setObject(8, null); else ps.setLong(8, finalGroupId);
            if (request.parentEssayId() == null) ps.setObject(9, null); else ps.setLong(9, request.parentEssayId());
            ps.setInt(10, finalAttempt);
            ps.setString(11, LocalEvaluationContract.hash(request.text().strip()));
            ps.setString(12, "READY".equals(status) ? now : null);
            return ps;
        }, key);
        long id = requiredKey(key);
        if (groupId == null) jdbc.update("UPDATE essay SET group_id = ? WHERE id = ?", id, id);
        return getEssay(id);
    }

    public Map<String, Object> getEssay(long essayId) {
        Map<String, Object> row = essayRow(essayId);
        if (!PILOT_STUDENT.equals(row.get("student_id"))) throw notFound();
        Map<String, Object> result = new LinkedHashMap<>(row);
        result.put("theme", jdbc.queryForMap("SELECT id, titulo, recorte, motivadores FROM tema WHERE id = ?", row.get("tema_id")));
        result.put("humanReview", currentRequest(essayId, false));
        return result;
    }

    public List<Map<String, Object>> listEssayGroups() {
        return jdbc.queryForList("""
                SELECT e.group_id AS groupId, t.titulo AS theme, COUNT(*) AS attempts,
                       MAX(e.created_at) AS lastActivity,
                       (SELECT e2.id FROM essay e2 WHERE e2.group_id=e.group_id ORDER BY e2.attempt_number DESC LIMIT 1) AS latestEssayId,
                       (SELECT r.total FROM run r JOIN essay e3 ON e3.id=r.essay_id
                        WHERE e3.group_id=e.group_id AND r.status='READY' ORDER BY r.id DESC LIMIT 1) AS latestAiTotal,
                       (SELECT he.total FROM human_evaluation he JOIN human_review_request hr ON hr.id=he.request_id
                        JOIN essay e4 ON e4.id=hr.essay_id WHERE e4.group_id=e.group_id AND he.status='PUBLISHED'
                        ORDER BY he.id DESC LIMIT 1) AS latestHumanTotal
                FROM essay e JOIN tema t ON t.id=e.tema_id
                WHERE e.student_id=? GROUP BY e.group_id, t.titulo ORDER BY lastActivity DESC
                """, PILOT_STUDENT);
    }

    public List<Map<String, Object>> listAttempts(long groupId) {
        return jdbc.queryForList("""
                SELECT e.id, e.group_id AS groupId, e.parent_essay_id AS parentEssayId,
                       e.attempt_number AS attemptNumber, e.title, e.status, e.created_at AS createdAt,
                       t.titulo AS theme,
                       (SELECT total FROM run WHERE essay_id=e.id AND status='READY' ORDER BY id DESC LIMIT 1) AS aiTotal,
                       (SELECT he.total FROM human_evaluation he JOIN human_review_request hr ON hr.id=he.request_id
                        WHERE hr.essay_id=e.id AND he.status='PUBLISHED' ORDER BY he.id DESC LIMIT 1) AS humanTotal
                FROM essay e JOIN tema t ON t.id=e.tema_id
                WHERE e.student_id=? AND e.group_id=? ORDER BY e.attempt_number
                """, PILOT_STUDENT, groupId);
    }

    @Transactional
    public Map<String, Object> requestHumanReview(long essayId, Long runId) {
        Map<String, Object> essay = essayRow(essayId);
        if (!PILOT_STUDENT.equals(essay.get("student_id"))) throw notFound();
        String revision = LocalEvaluationContract.hash(String.valueOf(essay.get("text")));
        if (runId != null) {
            Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM run WHERE id=? AND essay_id=? AND essay_revision=?", Integer.class, runId, essayId, revision);
            if (count == null || count == 0) throw new IllegalArgumentException("runId não pertence à revisão atual");
        }
        Integer active = jdbc.queryForObject("SELECT COUNT(*) FROM human_review_request WHERE essay_id=? AND status IN ('REQUESTED','IN_REVIEW')",
                Integer.class, essayId);
        if (active != null && active > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma correção humana ativa para esta tentativa");
        }
        try {
            jdbc.update("INSERT INTO human_review_request (essay_id, essay_revision, reference_run_id, status, requested_at) VALUES (?, ?, ?, 'REQUESTED', ?)",
                    essayId, revision, runId, now());
        } catch (DataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Já existe uma correção humana ativa para esta tentativa");
        }
        jdbc.update("UPDATE essay SET frozen=1, revision_hash=? WHERE id=?", revision, essayId);
        return currentRequest(essayId, false);
    }

    public Map<String, Object> currentRequest(long essayId, boolean reviewer) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT hr.id, hr.essay_id AS essayId, hr.reference_run_id AS referenceRunId,
                       hr.status, hr.reviewer_id AS reviewerId, hr.requested_at AS requestedAt,
                       hr.claimed_at AS claimedAt, hr.completed_at AS completedAt
                FROM human_review_request hr WHERE hr.essay_id=? ORDER BY hr.id DESC LIMIT 1
                """, essayId);
        if (rows.isEmpty()) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>(rows.getFirst());
        List<Map<String, Object>> published = jdbc.queryForList("""
                SELECT version, scores_json AS scoresJson, comments_json AS commentsJson,
                       main_guidance AS mainGuidance, total, reviewer_id AS reviewerId,
                       published_at AS publishedAt
                FROM human_evaluation WHERE request_id=? AND status='PUBLISHED' ORDER BY version DESC LIMIT 1
                """, result.get("id"));
        if (!published.isEmpty()) {
            Map<String, Object> evaluation = new LinkedHashMap<>(published.getFirst());
            evaluation.put("scores", parseMap(evaluation.remove("scoresJson")));
            evaluation.put("comments", parseMap(evaluation.remove("commentsJson")));
            result.put("evaluation", evaluation);
        }
        if (reviewer && published.isEmpty()) result.remove("referenceRunId");
        return result;
    }

    public List<Map<String, Object>> reviewerQueue(String status) {
        String normalized = status == null || status.isBlank() ? "REQUESTED" : status.toUpperCase();
        if (!List.of("REQUESTED", "IN_REVIEW", "COMPLETED").contains(normalized)) throw new IllegalArgumentException("status inválido");
        return jdbc.queryForList("""
                SELECT hr.id, hr.status, hr.requested_at AS requestedAt, hr.reviewer_id AS reviewerId,
                       e.id AS essayId, e.attempt_number AS attemptNumber, e.text, e.source_type AS sourceType,
                       t.titulo AS theme, t.recorte, t.motivadores
                FROM human_review_request hr JOIN essay e ON e.id=hr.essay_id JOIN tema t ON t.id=e.tema_id
                WHERE hr.status=? ORDER BY hr.requested_at
                """, normalized);
    }

    @Transactional
    public Map<String, Object> claim(long requestId, String reviewer) {
        int changed = jdbc.update("UPDATE human_review_request SET status='IN_REVIEW', reviewer_id=?, claimed_at=? WHERE id=? AND status='REQUESTED'",
                reviewer, now(), requestId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido já assumido ou indisponível");
        return reviewerRequest(requestId, reviewer);
    }

    public Map<String, Object> reviewerRequest(long requestId, String reviewer) {
        try {
            return jdbc.queryForMap("""
                    SELECT hr.id, hr.status, hr.requested_at AS requestedAt, hr.reviewer_id AS reviewerId,
                           e.id AS essayId, e.text, e.attempt_number AS attemptNumber,
                           t.titulo AS theme, t.recorte, t.motivadores
                    FROM human_review_request hr JOIN essay e ON e.id=hr.essay_id JOIN tema t ON t.id=e.tema_id
                    WHERE hr.id=? AND (hr.reviewer_id=? OR hr.status='REQUESTED')
                    """, requestId, reviewer);
        } catch (EmptyResultDataAccessException ex) { throw notFound(); }
    }

    @Transactional
    public Map<String, Object> saveHumanEvaluation(long requestId, String reviewer, HumanEvaluationRequest input, boolean publish) {
        Map<String, Object> request = reviewerRequest(requestId, reviewer);
        if ("REQUESTED".equals(request.get("status"))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Assuma o pedido antes de corrigir");
        Map<String, Integer> scores = validateScores(input.scores(), publish);
        int version = nextVersion(requestId);
        Integer total = null;
        if (scores.size() == 5) {
            int sum = 0;
            for (Integer score : scores.values()) sum += score;
            total = sum;
        }
        String now = now();
        jdbc.update("""
                INSERT INTO human_evaluation (request_id, version, status, reviewer_id, scores_json,
                    comments_json, main_guidance, total, rubric_version, revision_reason, created_at, published_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'enem-2026-v1', ?, ?, ?)
                """, requestId, version, publish ? "PUBLISHED" : "DRAFT", reviewer, json(scores),
                json(input.comments() == null ? Map.of() : input.comments()), blankToNull(input.mainGuidance()), total,
                blankToNull(input.revisionReason()), now, publish ? now : null);
        if (publish) jdbc.update("UPDATE human_review_request SET status='COMPLETED', completed_at=? WHERE id=?", now, requestId);
        return Map.of("requestId", requestId, "version", version, "status", publish ? "PUBLISHED" : "DRAFT",
                "scores", scores, "total", total == null ? 0 : total);
    }

    public Map<String, Object> progress() {
        List<Map<String, Object>> points = jdbc.queryForList("""
                SELECT e.id AS essayId, e.group_id AS groupId, e.attempt_number AS attemptNumber,
                       e.created_at AS createdAt, t.titulo AS theme, r.id AS runId, r.total AS total,
                       r.provider, r.model
                FROM essay e JOIN tema t ON t.id=e.tema_id JOIN run r ON r.id=(
                    SELECT r2.id FROM run r2 WHERE r2.essay_id=e.id AND r2.status='READY' AND r2.provider<>'fake' ORDER BY r2.id DESC LIMIT 1)
                WHERE e.student_id=? ORDER BY e.created_at
                """, PILOT_STUDENT);
        for (Map<String, Object> point : points) {
            point.put("scores", jdbc.queryForList("SELECT competencia, nota FROM competence_result WHERE run_id=? ORDER BY competencia", point.get("runId")));
            List<Map<String, Object>> human = jdbc.queryForList("""
                    SELECT he.total, he.scores_json AS scoresJson, he.published_at AS publishedAt
                    FROM human_evaluation he JOIN human_review_request hr ON hr.id=he.request_id
                    WHERE hr.essay_id=? AND he.status='PUBLISHED' ORDER BY he.version DESC LIMIT 1
                    """, point.get("essayId"));
            if (!human.isEmpty()) {
                Map<String, Object> h = new LinkedHashMap<>(human.getFirst());
                h.put("scores", parseMap(h.remove("scoresJson")));
                point.put("human", h);
            }
        }
        return Map.of("student", PILOT_STUDENT, "points", points, "fakeExcluded", true, "nullExcluded", true);
    }

    public List<Map<String, Object>> checklist(long essayId) {
        Map<String, Object> essay = essayRow(essayId);
        if (!PILOT_STUDENT.equals(essay.get("student_id"))) throw notFound();
        return jdbc.queryForList("SELECT competence, item_key AS itemKey, checked, updated_at AS updatedAt FROM student_checklist WHERE essay_id=? ORDER BY competence, item_key", essayId);
    }

    public Map<String, Object> updateChecklist(long essayId, String competence, String itemKey, boolean checked) {
        Map<String, Object> essay = essayRow(essayId);
        if (!PILOT_STUDENT.equals(essay.get("student_id"))) throw notFound();
        String normalized = competence == null ? "" : competence.toUpperCase();
        if (!COMPETENCES.contains(normalized)) throw new IllegalArgumentException("competência inválida");
        if (itemKey == null || !itemKey.matches("[a-z0-9-]{1,40}")) throw new IllegalArgumentException("itemKey inválido");
        jdbc.update("""
                INSERT INTO student_checklist (essay_id, competence, item_key, checked, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(essay_id, competence, item_key)
                DO UPDATE SET checked=excluded.checked, updated_at=excluded.updated_at
                """, essayId, normalized, itemKey, checked ? 1 : 0, now());
        return Map.of("essayId", essayId, "competence", normalized, "itemKey", itemKey, "checked", checked);
    }

    public void assertMutable(long essayId) {
        Integer frozen = jdbc.queryForObject("SELECT frozen FROM essay WHERE id=?", Integer.class, essayId);
        if (frozen != null && frozen == 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "A revisão desta tentativa está congelada; crie uma nova tentativa");
    }

    public void freeze(long essayId, String revision) {
        jdbc.update("UPDATE essay SET frozen=1, revision_hash=?, approved_at=COALESCE(approved_at, ?) WHERE id=?", revision, now(), essayId);
    }

    private Map<String, Object> essayRow(long id) {
        try {
            return jdbc.queryForMap("""
                    SELECT id, tema_id, text, status, created_at, student_id, title, source_type,
                           group_id, parent_essay_id, attempt_number, revision_hash, approved_at, frozen
                    FROM essay WHERE id=?
                    """, id);
        } catch (EmptyResultDataAccessException ex) { throw notFound(); }
    }

    private static Map<String, Integer> validateScores(Map<String, Integer> input, boolean required) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (input != null) for (String c : COMPETENCES) {
            Integer score = input.get(c);
            if (score == null) continue;
            if (score < 0 || score > 200 || score % 40 != 0) throw new IllegalArgumentException("Nota inválida para " + c);
            result.put(c, score);
        }
        if (required && result.size() != 5) throw new IllegalArgumentException("A publicação exige notas C1–C5");
        return result;
    }

    private int nextVersion(long requestId) {
        Integer value = jdbc.queryForObject("SELECT COALESCE(MAX(version), 0) + 1 FROM human_evaluation WHERE request_id=?", Integer.class, requestId);
        return value == null ? 1 : value;
    }

    private static String normalizeSource(String source) {
        String value = source == null ? "text" : source.strip().toLowerCase();
        return List.of("text", "image", "pdf", "upload").contains(value) ? value : "text";
    }
    private static long requiredKey(GeneratedKeyHolder key) {
        Number generated = key.getKey();
        if (generated == null) throw new IllegalStateException("Banco não retornou o identificador");
        return generated.longValue();
    }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static String now() { return OffsetDateTime.now().toString(); }
    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static Map<String, Object> parseMap(Object value) {
        if (!(value instanceof String text) || text.isBlank()) return Map.of();
        try { return JSON.readValue(text, new TypeReference<>() {}); } catch (Exception ex) { return Map.of(); }
    }
    private static ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso não encontrado"); }
}
