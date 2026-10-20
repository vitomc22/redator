package com.redator.corretor.service;

import com.redator.corretor.model.HumanEvaluationRequest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Stateless professor-side boundary: it never receives the student's database or profile context. */
@Service
public class ProfessorReviewService {
    private static final int MAX_PACKAGE_BYTES = 2 * 1024 * 1024;
    private static final List<String> CODES = List.of("C1", "C2", "C3", "C4", "C5");
    private final ReviewPackageCrypto crypto;

    public ProfessorReviewService(ReviewPackageCrypto crypto) { this.crypto = crypto; }

    public Map<String, Object> open(byte[] envelope, String secret) {
        requireSize(envelope);
        var metadata = crypto.inspect(envelope);
        if (!"REQUEST".equals(metadata.type())) throw new IllegalArgumentException("Este arquivo não é um pedido de correção");
        var opened = crypto.open(envelope, secret, "REQUEST", metadata.revisionHash());
        Map<String, Object> result = new LinkedHashMap<>(opened.payload());
        result.put("packageId", opened.requestId()); result.put("revisionHash", opened.revisionHash());
        result.put("expiresAt", opened.expiresAt());
        return result;
    }

    public Map<String, Object> respond(byte[] envelope, String secret, String reviewer, HumanEvaluationRequest evaluation) {
        Map<String, Object> request = open(envelope, secret);
        Map<String, Integer> scores = validate(evaluation.scores());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "redator-review-response-v1");
        payload.put("reviewer", reviewer == null || reviewer.isBlank() ? "professor-remoto" : reviewer.strip());
        payload.put("scores", scores); payload.put("comments", evaluation.comments() == null ? Map.of() : evaluation.comments());
        payload.put("mainGuidance", blank(evaluation.mainGuidance())); payload.put("revisionReason", blank(evaluation.revisionReason()));
        payload.put("rubricVersion", "enem-2026-v1");
        var source = crypto.inspect(envelope);
        var sealed = crypto.sealWithSecret("RESPONSE", String.valueOf(request.get("packageId")),
                String.valueOf(request.get("revisionHash")), source.expiresAt(), payload, secret);
        return Map.of("packageBase64", Base64.getEncoder().encodeToString(sealed.envelope()),
                "filename", "correcao-" + request.get("packageId") + ".redator-review");
    }

    private static Map<String, Integer> validate(Map<String, Integer> scores) {
        if (scores == null || !scores.keySet().equals(new java.util.LinkedHashSet<>(CODES)))
            throw new IllegalArgumentException("Preencha exatamente C1–C5");
        scores.forEach((code, score) -> { if (score == null || score < 0 || score > 200 || score % 40 != 0)
            throw new IllegalArgumentException(code + " deve usar 0, 40, 80, 120, 160 ou 200"); });
        return new LinkedHashMap<>(scores);
    }
    private static String blank(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static void requireSize(byte[] envelope) {
        if (envelope == null || envelope.length == 0 || envelope.length > MAX_PACKAGE_BYTES)
            throw new IllegalArgumentException("Pacote vazio ou maior que 2 MiB");
    }
}
