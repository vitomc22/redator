package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.http.HttpStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class EssayService {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;
    private final LlmGateway llmGateway;
    private final CartilhaEnem2026Service cartilha;
    private final TrainingCycleService trainingCycle;

    public EssayService(JdbcTemplate jdbcTemplate, LlmGateway llmGateway, CartilhaEnem2026Service cartilha,
                        TrainingCycleService trainingCycle) {
        this.jdbcTemplate = jdbcTemplate;
        this.llmGateway = llmGateway;
        this.cartilha = cartilha;
        this.trainingCycle = trainingCycle;
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
        jdbcTemplate.update("UPDATE essay SET group_id=id, revision_hash=?, approved_at=?, source_type='text' WHERE id=?",
                LocalEvaluationContract.hash(request.text()), now, essayId);
        return findEssayById(essayId);
    }

    public EssayResponse createUploadedEssay(Long temaId, String text, String sourceType) {
        if (temaId == null || text == null || text.isBlank()) {
            throw new IllegalArgumentException("temaId e text sao obrigatorios");
        }

        String normalizedSourceType = sourceType == null ? "upload" : sourceType.trim().toLowerCase();
        String status = "upload".equals(normalizedSourceType) || "image".equals(normalizedSourceType) || "pdf".equals(normalizedSourceType)
                ? "NEEDS_REVIEW"
                : "READY";

        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement preparedStatement = connection.prepareStatement(
                    "INSERT INTO essay (tema_id, text, status, created_at) VALUES (?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
            preparedStatement.setLong(1, temaId);
            preparedStatement.setString(2, text);
            preparedStatement.setString(3, status);
            preparedStatement.setString(4, now);
            return preparedStatement;
        }, keyHolder);

        Long essayId = keyHolder.getKey() == null ? 1L : keyHolder.getKey().longValue();
        jdbcTemplate.update("UPDATE essay SET group_id=id, revision_hash=?, approved_at=?, source_type=? WHERE id=?",
                LocalEvaluationContract.hash(text), "READY".equals(status) ? now : null, normalizedSourceType, essayId);
        return findEssayById(essayId);
    }

    public EssayResponse updateEssayText(Long essayId, String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("texto nao pode ser vazio");
        }
        trainingCycle.assertMutable(essayId);
        jdbcTemplate.update("UPDATE essay SET text = ?, status = 'READY', revision_hash=?, approved_at=? WHERE id = ?",
                text, LocalEvaluationContract.hash(text), OffsetDateTime.now().toString(), essayId);
        return findEssayById(essayId);
    }

    public EssayResponse reviewEssay(Long essayId, String reviewedText, boolean approved) {
        if (reviewedText == null || reviewedText.isBlank()) {
            throw new IllegalArgumentException("reviewedText obrigatorio");
        }

        trainingCycle.assertMutable(essayId);
        findEssayById(essayId);
        String status = approved && passesTranscriptionQualityGate(essayId, reviewedText) ? "READY" : "NEEDS_REVIEW";
        jdbcTemplate.update("UPDATE essay SET text = ?, status = ?, revision_hash=?, approved_at=? WHERE id = ?",
                reviewedText, status, LocalEvaluationContract.hash(reviewedText), "READY".equals(status) ? OffsetDateTime.now().toString() : null, essayId);
        return findEssayById(essayId);
    }

    @Transactional
    public Map<String, Object> saveHumanScores(Long essayId, Map<String, Object> payload) {
        findEssayById(essayId);
        String evaluator = payload.get("evaluator") == null ? "anonimo" : String.valueOf(payload.get("evaluator")).trim();
        String source = payload.get("source") == null ? null : String.valueOf(payload.get("source")).trim();
        if (evaluator.isBlank()) {
            throw new IllegalArgumentException("avaliador obrigatorio");
        }
        Map<String, Object> scores = payload.get("scores") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        List<String> competences = List.of("C1", "C2", "C3", "C4", "C5");
        Map<String, Integer> saved = new LinkedHashMap<>();
        String now = OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        for (String competence : competences) {
            Object raw = scores.get(competence);
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException("nota ausente para " + competence);
            }
            int score = number.intValue();
            if (score < 0 || score > 200 || score % 40 != 0) {
                throw new IllegalArgumentException("nota de " + competence + " deve ser 0, 40, 80, 120, 160 ou 200");
            }
            jdbcTemplate.update("INSERT INTO human_score (essay_id, competencia, nota, avaliador, fonte, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    essayId, competence, score, evaluator, source, now);
            saved.put(competence, score);
        }
        return Map.of("essayId", essayId, "evaluator", evaluator, "source", source == null ? "" : source, "scores", saved,
                "total", saved.values().stream().mapToInt(Integer::intValue).sum());
    }

    public List<Map<String, Object>> listHumanScores(Long essayId) {
        findEssayById(essayId);
        return jdbcTemplate.queryForList("SELECT competencia, nota, avaliador, fonte, created_at FROM human_score WHERE essay_id = ? ORDER BY id", essayId);
    }

    public boolean passesTranscriptionQualityGate(Long essayId, String reviewedText) {
        EssayResponse essay = findEssayById(essayId);
        if (essay.text() == null || essay.text().isBlank() || reviewedText == null || reviewedText.isBlank()) {
            return false;
        }

        try {
            return TranscriptionQualityMetricsCalculator.calculate(essay.text(), reviewedText).goNoGo();
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public DocumentTranscriptionService.Summary analyzeTranscriptionQuality(Long essayId) {
        EssayResponse essay = findEssayById(essayId);
        return DocumentTranscriptionService.analyze(essay.text());
    }

    public EvaluationResult evaluateEssay(Long essayId, String profile) {
        EssayResponse essay = findEssayById(essayId);
        if ("NEEDS_REVIEW".equalsIgnoreCase(essay.status())) {
            throw new EvaluationFailure(HttpStatus.CONFLICT, "DOCUMENT_REVIEW_REQUIRED",
                    "A redação precisa de revisão humana antes da avaliação.");
        }

        DeterministicEssayAnalysis analysis = DeterministicEssayAnalysis.analyze(essay.text());
        Map<String, CompetenceResult> baseCompetences = RubricEngine.evaluate(analysis);
        baseCompetences = applyCartilhaRules(essay.text(), baseCompetences);
        baseCompetences = applyThemeAlignmentRules(essay.text(), findTemaById(essay.temaId()), baseCompetences);
        EvaluationProfile evaluationProfile = EvaluationProfile.from(profile);
        Tema theme = findTemaById(essay.temaId());
        String revisionHash = LocalEvaluationContract.hash(essay.text());
        trainingCycle.freeze(essayId, revisionHash);

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

        LlmGateway.EvaluationOutput output;
        try {
            output = llmGateway.evaluate(essay.text(), theme, essay.id(), evaluationProfile, baseCompetences);
        } catch (EvaluationFailure failure) {
            jdbcTemplate.update("UPDATE run SET status = 'FAILED', essay_revision = ?, failure_code = ?, failure_message = ? WHERE id = ?",
                    revisionHash, failure.code(), failure.getReason(), runId);
            throw failure.withRun(runId);
        } catch (RuntimeException failure) {
            jdbcTemplate.update("UPDATE run SET status = 'FAILED', essay_revision = ?, failure_code = 'INTERNAL_FAILURE', failure_message = ? WHERE id = ?",
                    revisionHash, "Falha interna sem nota publicada", runId);
            throw new EvaluationFailure(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_FAILURE",
                    "Falha interna sem nota publicada.").withRun(runId);
        }

        EssayResponse current = findEssayById(essayId);
        if (!revisionHash.equals(LocalEvaluationContract.hash(current.text()))) {
            jdbcTemplate.update("UPDATE run SET status = 'STALE' WHERE id = ?", runId);
            throw new EvaluationFailure(HttpStatus.CONFLICT, "STALE_REVISION",
                    "A redação mudou durante a inferência; o resultado antigo foi descartado.").withRun(runId);
        }

        Map<String, CompetenceResult> competences = output.competences();
        Map<String, Object> audit = output.audit();
        String provider = String.valueOf(audit.getOrDefault("provider", "unknown"));
        String model = String.valueOf(audit.getOrDefault("model", "unknown"));
        long latencyMs = Math.round(number(audit.get("wallMs")));
        jdbcTemplate.update(
                "INSERT INTO llm_call (run_id, provider, model, prompt_hash, schema_hash, cost_usd, latency_ms, status, created_at, audit_json, cache_hit) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                runId, provider, model, text(audit.get("promptHash")), text(audit.get("schemaHash")),
                number(audit.get("costUsd")), latencyMs, "ok", now, json(audit), output.cacheHit() ? 1 : 0);

        int total = competences.values().stream().mapToInt(CompetenceResult::nota).sum();
        boolean needsReview = output.needsReview() || competences.values().stream().anyMatch(CompetenceResult::needsReview);

        jdbcTemplate.update("UPDATE run SET total = ?, status = ?, essay_revision = ?, provider = ?, model = ?, limitations = ?, review_reasons = ? WHERE id = ?",
                total, needsReview ? "NEEDS_REVIEW" : "READY", revisionHash, provider, model,
                json(output.limitations()), json(output.reviewReasons()), runId);

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
        return new EvaluationResult(runId, needsReview ? null : total, competences, needsReview, problems,
                evaluationProfile.name().toLowerCase(), needsReview ? "NEEDS_REVIEW" : "READY",
                provider, model, output.limitations(), output.reviewReasons(), output.cacheHit(), true);
    }

    private static String json(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Falha ao persistir auditoria", ex); }
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static double number(Object value) { return value instanceof Number number ? number.doubleValue() : 0.0; }

    private Map<String, CompetenceResult> applyCartilhaRules(String text, Map<String, CompetenceResult> competences) {
        Map<String, CompetenceResult> adjusted = new LinkedHashMap<>(competences);
        if (cartilha.detectsHumanRightsViolation(text)) {
            CompetenceResult current = adjusted.get("C5");
            List<String> problems = new ArrayList<>(current.problemas());
            problems.add("A cartilha determina nota 0 na C5 para proposta que desrespeita direitos humanos");
            List<String> evidence = new ArrayList<>(current.evidencias());
            evidence.add(cartilha.evidenceSource());
            adjusted.put("C5", new CompetenceResult("C5", 0, 0, evidence, problems, true,
                    List.of("Violacao de direitos humanos conforme Cartilha ENEM 2026")));
        }
        return adjusted;
    }

    private Map<String, CompetenceResult> applyThemeAlignmentRules(String text, Tema tema, Map<String, CompetenceResult> competences) {
        ThemeAlignmentService.Assessment assessment = new ThemeAlignmentService().assess(text, tema);
        if (assessment.alignment() == ThemeAlignmentService.Alignment.PLENO) {
            return competences;
        }

        int cap = assessment.alignment() == ThemeAlignmentService.Alignment.FUGA ? 0 : 40;
        Map<String, CompetenceResult> adjusted = new LinkedHashMap<>(competences);
        for (String competence : List.of("C2", "C3", "C5")) {
            CompetenceResult current = adjusted.get(competence);
            List<String> evidence = new ArrayList<>(current.evidencias());
            evidence.add(assessment.evidence());
            List<String> problems = new ArrayList<>(current.problemas());
            problems.add(assessment.alignment() == ThemeAlignmentService.Alignment.FUGA
                    ? "Possivel fuga ao tema; revisar antes de considerar a anulacao"
                    : "Possivel tangenciamento; a cartilha limita C2, C3 e C5 a 40 pontos");
            adjusted.put(competence, new CompetenceResult(competence, Math.min(current.nota(), cap),
                    Math.min(current.nivelSugerido(), cap), evidence, problems, true, current.motivos()));
        }
        return adjusted;
    }

    public Map<String, Object> getResult(Long essayId) {
        EssayResponse essay = findEssayById(essayId);
        List<Long> runIds = jdbcTemplate.queryForList(
                "SELECT id FROM run WHERE essay_id = ? ORDER BY id DESC LIMIT 1", Long.class, essay.id());
        if (runIds.isEmpty()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("essayId", essay.id());
            response.put("status", "NOT_EVALUATED");
            response.put("total", null);
            response.put("competences", List.of());
            response.put("experimental", true);
            return response;
        }
        Map<String, Object> response = getRunDetails(runIds.getFirst());
        response.put("essayId", essay.id());
        return response;
    }

    public Map<String, Object> getRunDetails(Long runId) {
        Map<String, Object> stored;
        try {
            stored = jdbcTemplate.queryForMap(
                    "SELECT id, essay_id, profile, total, status, created_at, provider, model, limitations, review_reasons, failure_code, failure_message FROM run WHERE id = ?",
                    runId);
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "execucao nao encontrada");
        }
        String status = String.valueOf(stored.get("status"));
        boolean publishCompetences = "READY".equals(status) || "NEEDS_REVIEW".equals(status);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("runId", stored.get("id"));
        response.put("essayId", stored.get("essay_id"));
        response.put("profile", stored.get("profile"));
        response.put("status", status);
        response.put("total", "READY".equals(status) ? stored.get("total") : null);
        response.put("provider", stored.get("provider"));
        response.put("model", stored.get("model"));
        response.put("limitations", jsonList(stored.get("limitations")));
        response.put("reviewStage", "NEEDS_REVIEW".equals(status) ? "EVALUATION" : null);
        response.put("reviewReasons", jsonList(stored.get("review_reasons")));
        response.put("failureCode", stored.get("failure_code"));
        response.put("failureMessage", stored.get("failure_message"));
        response.put("createdAt", stored.get("created_at"));
        response.put("experimental", true);
        response.put("competences", publishCompetences ? jdbcTemplate.queryForList(
                "SELECT competencia, nota, nivel_sugerido AS nivelSugerido, evidencias, problemas, needs_review AS needsReview, motivos FROM competence_result WHERE run_id = ? ORDER BY competencia",
                runId) : List.of());
        response.put("llmCalls", jdbcTemplate.queryForList(
                "SELECT provider, model, prompt_hash AS promptHash, schema_hash AS schemaHash, cost_usd AS costUsd, latency_ms AS latencyMs, status, created_at AS createdAt, cache_hit AS cacheHit, audit_json AS auditJson FROM llm_call WHERE run_id = ? ORDER BY id",
                runId));
        return response;
    }

    private static List<?> jsonList(Object stored) {
        if (!(stored instanceof String text) || text.isBlank()) return List.of();
        try {
            Object parsed = JSON.readValue(text, Object.class);
            return parsed instanceof List<?> list ? list : List.of();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    public Map<String, Object> runBenchmark(Long temaId, List<String> texts, String profile) {
        if (temaId == null) {
            throw new IllegalArgumentException("temaId e obrigatorio");
        }
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts nao pode ser vazio");
        }

        EvaluationProfile evaluationProfile = EvaluationProfile.from(profile);
        List<Map<String, Object>> results = new ArrayList<>();
        int totalScore = 0;
        int scoredCount = 0;
        double totalCostUsd = 0.0;
        long totalLatencyMs = 0L;

        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }

            EssayResponse essay = createEssay(new CreateEssayRequest(temaId, text));
            EvaluationResult evaluation = evaluateEssay(essay.id(), evaluationProfile.name().toLowerCase());
            double cost = sumCostForRun(evaluation.runId());
            long latency = averageLatencyMsForRun(evaluation.runId());
            if (evaluation.total() != null) {
                totalScore += evaluation.total();
                scoredCount++;
            }
            totalCostUsd += cost;
            totalLatencyMs += latency;

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("essayId", essay.id());
            item.put("total", evaluation.total());
            item.put("needsReview", evaluation.needsReview());
            item.put("profile", evaluation.profile());
            item.put("provider", evaluation.provider());
            item.put("model", evaluation.model());
            item.put("costUsd", round(cost));
            item.put("averageLatencyMs", latency);
            results.add(item);
        }

        int count = results.size();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("profile", evaluationProfile.name().toLowerCase());
        summary.put("count", count);
        summary.put("scoredCount", scoredCount);
        summary.put("averageTotal", scoredCount == 0 ? null : Math.round((double) totalScore / scoredCount));
        summary.put("totalCostUsd", round(totalCostUsd));
        summary.put("averageLatencyMs", count == 0 ? 0 : Math.round((double) totalLatencyMs / count));
        summary.put("results", results);
        return summary;
    }

    public Map<String, Object> compareProfiles(Long temaId, List<String> profiles, List<String> texts) {
        if (temaId == null) {
            throw new IllegalArgumentException("temaId e obrigatorio");
        }
        if (texts == null || texts.isEmpty()) {
            throw new IllegalArgumentException("texts nao pode ser vazio");
        }

        List<String> selectedProfiles = profiles == null || profiles.isEmpty()
                ? List.of("cheap", "normal", "premium")
                : profiles.stream().filter(p -> p != null && !p.isBlank()).map(String::trim).toList();

        Map<String, Map<String, Object>> comparisons = new LinkedHashMap<>();
        for (String profile : selectedProfiles) {
            comparisons.put(profile.toLowerCase(), runBenchmark(temaId, texts, profile));
        }

        return Map.of(
                "temaId", temaId,
                "profiles", selectedProfiles,
                "profilesAreAliases", true,
                "qualityRanking", "not-measured",
                "comparisons", comparisons
        );
    }

    private double sumCostForRun(Long runId) {
        Double value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(cost_usd), 0) FROM llm_call WHERE run_id = ?",
                Double.class,
                runId
        );
        return value == null ? 0.0 : value;
    }

    private long averageLatencyMsForRun(Long runId) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COALESCE(AVG(latency_ms), 0) FROM llm_call WHERE run_id = ?",
                Long.class,
                runId
        );
        return value == null ? 0L : value;
    }

    private double round(double value) {
        return Math.round(value * 100.0) / 100.0;
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

    private Tema findTemaById(Long temaId) {
        return jdbcTemplate.queryForObject(
                "SELECT id, titulo, recorte, motivadores FROM tema WHERE id = ?",
                (rs, rowNum) -> new Tema(rs.getLong("id"), rs.getString("titulo"), rs.getString("recorte"), rs.getString("motivadores")),
                temaId
        );
    }
}
