package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.HumanEvaluationRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ReviewExchangeService {
    private static final int MAX_PACKAGE_BYTES = 2 * 1024 * 1024;
    private static final List<String> COMPETENCES = List.of("C1", "C2", "C3", "C4", "C5");
    private final JdbcTemplate jdbc;
    private final LocalUserContext users;
    private final ReviewPackageCrypto crypto;
    private final ObjectMapper mapper;
    private final Clock clock;

    @Autowired
    public ReviewExchangeService(JdbcTemplate jdbc, LocalUserContext users, ReviewPackageCrypto crypto,
                                 ObjectMapper mapper) {
        this(jdbc, users, crypto, mapper, Clock.systemUTC());
    }

    ReviewExchangeService(JdbcTemplate jdbc, LocalUserContext users, ReviewPackageCrypto crypto,
                          ObjectMapper mapper, Clock clock) {
        this.jdbc = jdbc; this.users = users; this.crypto = crypto; this.mapper = mapper; this.clock = clock;
    }

    @Transactional
    public Map<String, Object> exportRequest(long essayId, Long runId, int validityDays) {
        if (validityDays < 1 || validityDays > 30) throw new IllegalArgumentException("Validade deve ficar entre 1 e 30 dias");
        Map<String, Object> essay = ownedEssay(essayId);
        Map<String, Object> request = activeRequest(essayId);
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Solicite a correção humana antes de exportar o pacote");
        }
        long requestId = ((Number) request.get("id")).longValue();
        String revision = String.valueOf(request.get("essay_revision"));
        String packageId = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(validityDays, ChronoUnit.DAYS);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "redator-review-request-v1");
        payload.put("theme", essay.get("theme"));
        payload.put("prompt", essay.get("recorte"));
        payload.put("motivators", essay.get("motivadores"));
        payload.put("essayText", essay.get("text"));
        payload.put("attemptNumber", essay.get("attempt_number"));
        payload.put("rubricVersion", "enem-2026-v1");
        if (runId != null) payload.put("referenceRunId", runId);
        ReviewPackageCrypto.Sealed sealed = crypto.seal("REQUEST", packageId, revision, expiresAt, payload);
        jdbc.update("UPDATE review_package SET status='REVOKED' WHERE request_id=? AND status='EXPORTED'", requestId);
        jdbc.update("""
                INSERT INTO review_package(package_id, request_id, student_id, revision_hash, secret_hash,
                    status, expires_at, created_at) VALUES (?, ?, ?, ?, ?, 'EXPORTED', ?, ?)
                """, packageId, requestId, users.current(), revision, sha256(sealed.secret()),
                expiresAt.toString(), now());
        return encoded(sealed.envelope(), sealed.secret(), "pedido-correcao-" + packageId + ".redator-review");
    }

    public Map<String, Object> openRequest(byte[] envelope, String secret) {
        requireSize(envelope);
        ReviewPackageCrypto.Metadata metadata = crypto.inspect(envelope);
        if (!"REQUEST".equals(metadata.type())) throw new IllegalArgumentException("Este arquivo não é um pedido de correção");
        ReviewPackageCrypto.Opened opened = crypto.open(envelope, secret, "REQUEST", metadata.revisionHash());
        Map<String, Object> result = new LinkedHashMap<>(opened.payload());
        result.put("packageId", opened.requestId());
        result.put("revisionHash", opened.revisionHash());
        result.put("expiresAt", opened.expiresAt());
        return result;
    }

    public Map<String, Object> createResponse(byte[] requestEnvelope, String secret, String reviewer,
                                              HumanEvaluationRequest evaluation) {
        Map<String, Object> request = openRequest(requestEnvelope, secret);
        String packageId = String.valueOf(request.get("packageId"));
        String revision = String.valueOf(request.get("revisionHash"));
        ReviewPackageCrypto.Metadata source = crypto.inspect(requestEnvelope);
        Map<String, Integer> scores = validateScores(evaluation.scores());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "redator-review-response-v1");
        payload.put("reviewer", normalizeReviewer(reviewer));
        payload.put("scores", scores);
        payload.put("comments", evaluation.comments() == null ? Map.of() : evaluation.comments());
        payload.put("mainGuidance", blankToNull(evaluation.mainGuidance()));
        payload.put("revisionReason", blankToNull(evaluation.revisionReason()));
        payload.put("rubricVersion", "enem-2026-v1");
        ReviewPackageCrypto.Sealed sealed = crypto.sealWithSecret("RESPONSE", packageId, revision,
                source.expiresAt(), payload, secret);
        return encoded(sealed.envelope(), null, "correcao-" + packageId + ".redator-review");
    }

    @Transactional
    public Map<String, Object> cancel(long essayId) {
        ownedEssay(essayId);
        Map<String, Object> request = activeRequest(essayId);
        if (request == null)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Não existe pedido ativo para cancelar");
        long requestId = ((Number) request.get("id")).longValue();
        String cancelledAt = now();
        int changed = jdbc.update("""
                UPDATE human_review_request SET status='CANCELLED', cancelled_at=?
                WHERE id=? AND status IN ('REQUESTED','IN_REVIEW')
                """, cancelledAt, requestId);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido não está mais ativo");
        jdbc.update("UPDATE review_package SET status='REVOKED' WHERE request_id=? AND status='EXPORTED'", requestId);
        return Map.of("id", requestId, "essayId", essayId, "status", "CANCELLED", "cancelledAt", cancelledAt);
    }

    @Transactional
    public Map<String, Object> importResponse(byte[] envelope, String secret) {
        requireSize(envelope);
        ReviewPackageCrypto.Metadata metadata = crypto.inspect(envelope);
        if (!"RESPONSE".equals(metadata.type())) throw new IllegalArgumentException("Este arquivo não é uma resposta de correção");
        Map<String, Object> pkg;
        try {
            pkg = jdbc.queryForMap("SELECT * FROM review_package WHERE package_id=? AND student_id=?",
                    metadata.requestId(), users.current());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pacote de correção não encontrado neste perfil local");
        }
        if (!"EXPORTED".equals(pkg.get("status")))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta resposta já foi importada ou o pacote não está ativo");
        if (!MessageDigest.isEqual(String.valueOf(pkg.get("secret_hash")).getBytes(StandardCharsets.UTF_8),
                sha256(secret).getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException("Segredo incorreto");
        String revision = String.valueOf(pkg.get("revision_hash"));
        ReviewPackageCrypto.Opened opened = crypto.open(envelope, secret, "RESPONSE", revision);
        Map<String, Object> payload = opened.payload();
        Map<String, Integer> scores = mapper.convertValue(payload.get("scores"), new TypeReference<>() {});
        scores = validateScores(scores);
        Map<String, String> comments = mapper.convertValue(payload.getOrDefault("comments", Map.of()), new TypeReference<>() {});
        String responseHash = sha256(envelope);
        long requestId = ((Number) pkg.get("request_id")).longValue();
        int version = jdbc.queryForObject("SELECT COALESCE(MAX(version),0)+1 FROM human_evaluation WHERE request_id=?",
                Integer.class, requestId);
        int total = scores.values().stream().mapToInt(Integer::intValue).sum();
        String importedAt = now();
        int claimed = jdbc.update("""
                UPDATE review_package SET status='IMPORTED', response_hash=?, imported_at=?
                WHERE package_id=? AND status='EXPORTED'
                """, responseHash, importedAt, metadata.requestId());
        if (claimed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Esta resposta já foi importada");
        jdbc.update("""
                INSERT INTO human_evaluation(request_id, version, status, reviewer_id, scores_json,
                    comments_json, main_guidance, total, rubric_version, revision_reason, created_at, published_at)
                VALUES (?, ?, 'PUBLISHED', ?, ?, ?, ?, ?, 'enem-2026-v1', ?, ?, ?)
                """, requestId, version, normalizeReviewer(String.valueOf(payload.getOrDefault("reviewer", "professor-remoto"))),
                json(scores), json(comments), blankToNull(asString(payload.get("mainGuidance"))), total,
                blankToNull(asString(payload.get("revisionReason"))), importedAt, importedAt);
        int completed = jdbc.update("""
                UPDATE human_review_request SET status='COMPLETED', reviewer_id=?, claimed_at=COALESCE(claimed_at, ?), completed_at=?
                WHERE id=? AND status IN ('REQUESTED','IN_REVIEW')
                """, normalizeReviewer(String.valueOf(payload.getOrDefault("reviewer", "professor-remoto"))),
                importedAt, importedAt, requestId);
        if (completed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Pedido de correção não está mais ativo");
        return Map.of("packageId", metadata.requestId(), "requestId", requestId, "status", "PUBLISHED",
                "scores", scores, "total", total, "importedAt", importedAt);
    }

    private Map<String, Object> ownedEssay(long essayId) {
        try {
            return jdbc.queryForMap("""
                    SELECT e.*, t.titulo AS theme, t.recorte, t.motivadores FROM essay e
                    JOIN tema t ON t.id=e.tema_id WHERE e.id=? AND e.student_id=?
                    """, essayId, users.current());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Recurso não encontrado");
        }
    }

    private Map<String, Object> activeRequest(long essayId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM human_review_request WHERE essay_id=? AND status IN ('REQUESTED','IN_REVIEW')
                ORDER BY id DESC LIMIT 1
                """, essayId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private static Map<String, Integer> validateScores(Map<String, Integer> input) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (input != null) for (String competence : COMPETENCES) {
            Integer score = input.get(competence);
            if (score == null || score < 0 || score > 200 || score % 40 != 0)
                throw new IllegalArgumentException("A resposta exige notas C1–C5 válidas em intervalos de 40");
            result.put(competence, score);
        }
        if (result.size() != 5) throw new IllegalArgumentException("A resposta exige notas C1–C5");
        return result;
    }

    private Map<String, Object> encoded(byte[] envelope, String secret, String filename) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("packageBase64", Base64.getEncoder().encodeToString(envelope));
        if (secret != null) result.put("secret", secret);
        result.put("filename", filename);
        return result;
    }

    private static void requireSize(byte[] value) {
        if (value == null || value.length == 0 || value.length > MAX_PACKAGE_BYTES)
            throw new IllegalArgumentException("Pacote vazio ou maior que 2 MB");
    }
    private static String normalizeReviewer(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[\\p{L}0-9 ._-]{2,80}")) throw new IllegalArgumentException("Identificação do professor inválida");
        return normalized;
    }
    private static String asString(Object value) { return value == null ? null : String.valueOf(value); }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    private static String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 indisponível", ex);
        }
    }
    private String now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(); }
}
