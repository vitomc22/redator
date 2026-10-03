package com.redator.corretor.service;

import com.redator.corretor.model.CreateEssayRequest;
import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.model.EssayResponse;
import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.model.EvaluationResult;
import com.redator.corretor.model.Tema;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class EssayService {

    private final JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;

    public EssayService(JdbcTemplate jdbcTemplate, LlmGateway llmGateway) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmGateway = llmGateway;
    }

    public List<Tema> listTemas() {
        return jdbcTemplate.query(
                "SELECT id, titulo, recorte, motivadores FROM tema ORDER BY id",
                (rs, rowNum) -> new Tema(
                        rs.getLong("id"),
                        rs.getString("titulo"),
                        rs.getString("recorte"),
                        rs.getString("motivadores")
                )
        );
    }

    public EssayResponse createEssay(CreateEssayRequest request) {
        if (request == null || request.temaId() == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("temaId e text sao obrigatorios");
        }

        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String sql = "INSERT INTO essay (tema_id, text, status, created_at) VALUES (?, ?, ?, ?)";
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement preparedStatement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            preparedStatement.setLong(1, request.temaId());
            preparedStatement.setString(2, request.text());
            preparedStatement.setString(3, "READY");
            preparedStatement.setString(4, now);
            return preparedStatement;
        }, keyHolder);

        Long essayId = keyHolder.getKey() == null ? 1L : keyHolder.getKey().longValue();
        return findEssayById(essayId);
    }

    public EssayResponse updateEssayText(Long essayId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("texto nao pode ser vazio");
        }
        jdbcTemplate.update("UPDATE essay SET text = ?, status = 'READY' WHERE id = ?", text, essayId);
        return findEssayById(essayId);
    }

    @Transactional
    public EvaluationResult evaluateEssay(Long essayId, String profile) {
        EssayResponse essay = findEssayById(essayId);
        DeterministicEssayAnalysis analysis = DeterministicEssayAnalysis.analyze(essay.text());
        Map<String, CompetenceResult> baseCompetences = RubricEngine.evaluate(analysis);
        EvaluationProfile evaluationProfile = EvaluationProfile.from(profile);
        Map<String, CompetenceResult> competences = new HashMap<>();

        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        KeyHolder runKey = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(
                    "INSERT INTO run (essay_id, profile, total, status, created_at) VALUES (?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS
            );
            ps.setLong(1, essay.id());
            ps.setString(2, evaluationProfile.name().toLowerCase());
            ps.setInt(3, 0);
            ps.setString(4, "RUNNING");
            ps.setString(5, now);
            return ps;
        }, runKey);

        Long runId = runKey.getKey() == null ? 1L : runKey.getKey().longValue();

        for (Map.Entry<String, CompetenceResult> entry : baseCompetences.entrySet()) {
            CompetenceResult result = entry.getValue();
            if (List.of("C2", "C3", "C4", "C5").contains(entry.getKey())) {
                LlmCallResult llmCall = llmGateway.generate(entry.getKey(), essay.text(), evaluationProfile);
                jdbcTemplate.update(
                        "INSERT INTO llm_call (run_id, provider, model, prompt_hash, schema_hash, cost_usd, latency_ms, status, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        runId,
                        llmCall.provider(),
                        llmCall.model(),
                        llmCall.promptHash(),
                        llmCall.schemaHash(),
                        llmCall.costUsd(),
                        llmCall.latencyMs(),
                        llmCall.status(),
                        now
                );

                List<String> evidence = new ArrayList<>(result.evidencias());
                evidence.add("Gateway " + evaluationProfile.name() + ": " + llmCall.summary());
                List<String> reasons = new ArrayList<>(result.motivos());
                reasons.add("Resposta do provider " + llmCall.provider() + " validada pelo perfil " + evaluationProfile.name().toLowerCase());
                result = new CompetenceResult(
                        result.competencia(),
                        result.nota(),
                        result.nivelSugerido(),
                        evidence,
                        result.problemas(),
                        result.needsReview(),
                        reasons
                );
            }
            competences.put(entry.getKey(), result);
        }

        int total = competences.values().stream().mapToInt(CompetenceResult::nota).sum();
        boolean needsReview = competences.values().stream().anyMatch(CompetenceResult::needsReview);

        jdbcTemplate.update("UPDATE run SET total = ?, status = ? WHERE id = ?", total, needsReview ? "NEEDS_REVIEW" : "READY", runId);

        for (Map.Entry<String, CompetenceResult> entry : competences.entrySet()) {
            jdbcTemplate.update(
                    "INSERT INTO competence_result (run_id, competencia, nota, nivel_sugerido, evidencias, problemas, needs_review, motivos) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    runId,
                    entry.getKey(),
                    entry.getValue().nota(),
                    entry.getValue().nivelSugerido(),
                    String.join(" | ", entry.getValue().evidencias()),
                    String.join(" | ", entry.getValue().problemas()),
                    entry.getValue().needsReview() ? 1 : 0,
                    String.join(" | ", entry.getValue().motivos())
            );
        }

        List<String> problems = new ArrayList<>();
        competences.values().forEach(c -> problems.addAll(c.problemas()));
        return new EvaluationResult(runId, total, competences, needsReview, problems, evaluationProfile.name().toLowerCase());
    }

    public Map<String, Object> getResult(Long essayId) {
        EssayResponse essay = findEssayById(essayId);
        Long runId = jdbcTemplate.queryForObject(
                "SELECT id FROM run WHERE essay_id = ? ORDER BY created_at DESC LIMIT 1",
                Long.class,
                essay.id()
        );

        if (runId == null) {
            return Map.of("essayId", essay.id(), "status", essay.status(), "total", 0, "competences", Map.of());
        }

        Map<String, Object> response = new HashMap<>();
        response.put("essayId", essay.id());
        response.put("status", essay.status());
        response.put("runId", runId);
        response.put("total", jdbcTemplate.queryForObject("SELECT total FROM run WHERE id = ?", Integer.class, runId));

        List<Map<String, Object>> competences = jdbcTemplate.queryForList(
                "SELECT competencia, nota, nivel_sugerido, evidencias, problemas, needs_review FROM competence_result WHERE run_id = ? ORDER BY competencia",
                runId
        );
        response.put("competences", competences);
        return response;
    }

    public Map<String, Object> getRunDetails(Long runId) {
        Map<String, Object> run = jdbcTemplate.queryForMap(
                "SELECT id, essay_id, profile, total, status, created_at FROM run WHERE id = ?",
                runId
        );
        List<Map<String, Object>> competences = jdbcTemplate.queryForList(
                "SELECT competencia, nota, nivel_sugerido, evidencias, problemas, needs_review FROM competence_result WHERE run_id = ? ORDER BY competencia",
                runId
        );
        run.put("competences", competences);
        return run;
    }

    private EssayResponse findEssayById(Long essayId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, tema_id, text, status, created_at FROM essay WHERE id = ?",
                essayId
        );
        return new EssayResponse(
                ((Number) row.get("id")).longValue(),
                ((Number) row.get("tema_id")).longValue(),
                (String) row.get("text"),
                (String) row.get("status"),
                (String) row.get("created_at")
        );
    }
}
