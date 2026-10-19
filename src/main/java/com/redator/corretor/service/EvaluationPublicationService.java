package com.redator.corretor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CompetenceResult;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Publica auditoria, resultado e as cinco competências como uma única unidade SQLite. */
@Service
public class EvaluationPublicationService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;

    public EvaluationPublicationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public Publication publish(long runId, String revisionHash, String createdAt,
                               LlmGateway.EvaluationOutput output) {
        Map<String, CompetenceResult> competences = output.competences();
        Map<String, Object> audit = output.audit();
        String provider = String.valueOf(audit.getOrDefault("provider", "unknown"));
        String model = String.valueOf(audit.getOrDefault("model", "unknown"));
        long latencyMs = Math.round(number(audit.get("wallMs")));
        int total = competences.values().stream().mapToInt(CompetenceResult::nota).sum();
        boolean needsReview = output.needsReview()
                || competences.values().stream().anyMatch(CompetenceResult::needsReview);

        jdbc.update("""
                INSERT INTO llm_call (run_id, provider, model, prompt_hash, schema_hash, cost_usd,
                                      latency_ms, status, created_at, audit_json, cache_hit)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'ok', ?, ?, ?)
                """, runId, provider, model, text(audit.get("promptHash")), text(audit.get("schemaHash")),
                number(audit.get("costUsd")), latencyMs, createdAt, json(audit), output.cacheHit() ? 1 : 0);
        jdbc.update("""
                UPDATE run SET total=?, status=?, essay_revision=?, provider=?, model=?,
                               limitations=?, review_reasons=? WHERE id=?
                """, total, needsReview ? "NEEDS_REVIEW" : "READY", revisionHash, provider, model,
                json(output.limitations()), json(output.reviewReasons()), runId);

        for (Map.Entry<String, CompetenceResult> entry : competences.entrySet()) {
            CompetenceResult result = entry.getValue();
            jdbc.update("""
                    INSERT INTO competence_result
                        (run_id, competencia, nota, nivel_sugerido, evidencias, problemas, needs_review, motivos)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, runId, entry.getKey(), result.nota(), result.nivelSugerido(),
                    String.join(" | ", result.evidencias()), String.join(" | ", result.problemas()),
                    result.needsReview() ? 1 : 0, String.join(" | ", result.motivos()));
        }
        return new Publication(total, needsReview, provider, model);
    }

    private static String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Falha ao persistir auditoria", ex);
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static double number(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    public record Publication(int total, boolean needsReview, String provider, String model) {}
}
