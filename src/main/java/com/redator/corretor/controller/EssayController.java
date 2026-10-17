package com.redator.corretor.controller;

import com.redator.corretor.model.BenchmarkComparisonRequest;
import com.redator.corretor.model.BenchmarkRequest;
import com.redator.corretor.model.CreateEssayRequest;
import com.redator.corretor.model.CreateAttemptRequest;
import com.redator.corretor.model.HumanEvaluationRequest;
import com.redator.corretor.model.EssayResponse;
import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.model.EvaluationResult;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.DocumentTranscriptionService;
import com.redator.corretor.service.EssayService;
import com.redator.corretor.service.LlmGateway;
import com.redator.corretor.service.UploadValidationService;
import com.redator.corretor.service.TrainingCycleService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class EssayController {

    private final EssayService essayService;
    private final UploadValidationService uploadValidationService;
    private final LlmGateway llmGateway;
    private final TrainingCycleService trainingCycle;

    public EssayController(EssayService essayService, UploadValidationService uploadValidationService, LlmGateway llmGateway,
                           TrainingCycleService trainingCycle) {
        this.essayService = essayService;
        this.uploadValidationService = uploadValidationService;
        this.llmGateway = llmGateway;
        this.trainingCycle = trainingCycle;
    }

    @GetMapping("/temas")
    public List<Tema> listTemas() {
        return essayService.listTemas();
    }

    @PostMapping("/essays")
    public ResponseEntity<Map<String, Object>> createEssay(@Validated @RequestBody CreateEssayRequest request) {
        EssayResponse essay = essayService.createEssay(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("essay", essay));
    }

    @PostMapping("/attempts")
    public ResponseEntity<Map<String, Object>> createAttempt(@Validated @RequestBody CreateAttemptRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("essay", trainingCycle.createAttempt(request)));
    }

    @PostMapping(value = "/attempts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createAttemptFromDocument(
            @RequestParam("temaId") Long temaId,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "parentEssayId", required = false) Long parentEssayId,
            @RequestPart("file") MultipartFile file) {
        String text = resolveUploadedText(null, file);
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        String sourceType = name.endsWith(".pdf") ? "pdf" : "image";
        CreateAttemptRequest request = new CreateAttemptRequest(temaId, text, title, parentEssayId, sourceType);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("essay", trainingCycle.createAttempt(request)));
    }

    @GetMapping("/essays/{id}")
    public ResponseEntity<Map<String, Object>> getEssay(@PathVariable Long id) {
        return ResponseEntity.ok(trainingCycle.getEssay(id));
    }

    @GetMapping("/me/essays")
    public ResponseEntity<List<Map<String, Object>>> listMyEssays() {
        return ResponseEntity.ok(trainingCycle.listEssayGroups());
    }

    @GetMapping("/me/essays/{groupId}/attempts")
    public ResponseEntity<List<Map<String, Object>>> listAttempts(@PathVariable Long groupId) {
        return ResponseEntity.ok(trainingCycle.listAttempts(groupId));
    }

    @GetMapping("/me/progress")
    public ResponseEntity<Map<String, Object>> progress() {
        return ResponseEntity.ok(trainingCycle.progress());
    }

    @GetMapping("/essays/{id}/checklist")
    public ResponseEntity<List<Map<String, Object>>> checklist(@PathVariable Long id) {
        return ResponseEntity.ok(trainingCycle.checklist(id));
    }

    @PutMapping("/essays/{id}/checklist")
    public ResponseEntity<Map<String, Object>> updateChecklist(@PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        String competence = String.valueOf(payload.getOrDefault("competence", ""));
        String itemKey = String.valueOf(payload.getOrDefault("itemKey", ""));
        boolean checked = Boolean.TRUE.equals(payload.get("checked"));
        return ResponseEntity.ok(trainingCycle.updateChecklist(id, competence, itemKey, checked));
    }

    @PostMapping(value = "/essays", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> createEssayMultipart(
            @RequestParam("temaId") Long temaId,
            @RequestParam(value = "text", required = false) String text,
            @RequestPart(value = "file", required = false) MultipartFile file) {

        String finalText = resolveUploadedText(text, file);
        if (finalText == null || finalText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text ou arquivo sao obrigatorios");
        }

        EssayResponse essay = essayService.createUploadedEssay(temaId, finalText, file != null && !file.isEmpty() ? "upload" : "text");
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("essay", essay));
    }

    @PostMapping("/essays/upload")
    public ResponseEntity<Map<String, Object>> createUploadedEssay(@RequestBody Map<String, Object> payload) {
        if (!payload.containsKey("temaId") || !(payload.get("temaId") instanceof Number)
                || !payload.containsKey("text") || payload.get("text") == null || String.valueOf(payload.get("text")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "temaId e text sao obrigatorios");
        }

        String sourceType = payload.get("sourceType") == null ? "upload" : String.valueOf(payload.get("sourceType"));
        Long temaId = ((Number) payload.get("temaId")).longValue();
        String text = String.valueOf(payload.get("text"));

        EssayResponse essay = essayService.createUploadedEssay(temaId, text, sourceType);
        return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("essay", essay));
    }

    @PutMapping("/essays/{id}/text")
    public ResponseEntity<Map<String, Object>> updateEssayText(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        if (!payload.containsKey("text") || payload.get("text") == null || payload.get("text").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text obrigatorio");
        }
        EssayResponse essay = essayService.updateEssayText(id, payload.get("text"));
        return ResponseEntity.ok(Map.of("essay", essay));
    }

    private String resolveUploadedText(String text, MultipartFile file) {
        if (text != null && !text.isBlank()) {
            return text.trim();
        }
        if (file == null || file.isEmpty()) {
            return null;
        }

        byte[] content;
        String fileName;
        try {
            content = file.getBytes();
            fileName = file.getOriginalFilename() == null ? "arquivo" : file.getOriginalFilename();
            uploadValidationService.validate(content, fileName);
        } catch (Exception e) {
            if (e instanceof ResponseStatusException rse) {
                throw rse;
            }
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "arquivo invalido");
        }

        return DocumentTranscriptionService.transcribeDocument(content, fileName);
    }

    @PostMapping("/essays/{id}/review")
    public ResponseEntity<Map<String, Object>> reviewEssay(@PathVariable Long id, @RequestBody Map<String, Object> payload) {
        if (!payload.containsKey("reviewedText") || payload.get("reviewedText") == null || String.valueOf(payload.get("reviewedText")).isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "reviewedText obrigatorio");
        }

        boolean approved = payload.containsKey("approved") && payload.get("approved") instanceof Boolean
                ? (Boolean) payload.get("approved")
                : Boolean.parseBoolean(String.valueOf(payload.getOrDefault("approved", false)));

        String reviewedText = String.valueOf(payload.get("reviewedText"));
        EssayResponse essay = essayService.reviewEssay(id, reviewedText, approved);
        DocumentTranscriptionService.Summary quality = essayService.analyzeTranscriptionQuality(id);
        boolean qualityGatePassed = essayService.passesTranscriptionQualityGate(id, reviewedText);
        return ResponseEntity.ok(Map.of(
                "essayId", essay.id(),
                "status", essay.status(),
                "approved", approved,
                "qualityGatePassed", qualityGatePassed,
                "text", essay.text(),
                "transcriptionQuality", Map.of(
                        "palavras", quality.palavras(),
                        "linhas", quality.linhas(),
                        "paragrafos", quality.paragrafos(),
                        "ilegiveis", quality.ilegiveis(),
                        "tokensForaDoDicionario", quality.tokensForaDoDicionario(),
                        "qualidade", quality.quality(),
                        "tokensSuspeitos", quality.suspiciousTokens()
                )
        ));
    }

    @GetMapping("/essays/{id}/quality")
    public ResponseEntity<Map<String, Object>> getTranscriptionQuality(@PathVariable Long id) {
        DocumentTranscriptionService.Summary quality = essayService.analyzeTranscriptionQuality(id);
        return ResponseEntity.ok(Map.of(
                "essayId", id,
                "palavras", quality.palavras(),
                "linhas", quality.linhas(),
                "paragrafos", quality.paragrafos(),
                "ilegiveis", quality.ilegiveis(),
                "tokensForaDoDicionario", quality.tokensForaDoDicionario(),
                "qualidade", quality.quality(),
                "tokensSuspeitos", quality.suspiciousTokens()
        ));
    }

    @PostMapping("/essays/{id}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateEssay(@PathVariable Long id, @RequestParam(defaultValue = "cheap") String profile) {
        EvaluationProfile evaluationProfile = EvaluationProfile.from(profile);
        EvaluationResult result = essayService.evaluateEssay(id, evaluationProfile.name().toLowerCase());
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("runId", result.runId());
        body.put("total", result.total());
        body.put("needsReview", result.needsReview());
        body.put("profile", result.profile());
        body.put("status", result.status());
        body.put("provider", result.provider());
        body.put("model", result.model());
        body.put("competences", result.competences());
        body.put("limitations", result.limitations());
        body.put("reviewStage", result.needsReview() ? "EVALUATION" : null);
        body.put("reviewReasons", result.reviewReasons());
        body.put("cacheHit", result.cacheHit());
        body.put("experimental", result.experimental());
        return ResponseEntity.ok(body);
    }

    @GetMapping("/runtime")
    public ResponseEntity<Map<String, Object>> runtime() {
        return ResponseEntity.ok(llmGateway.runtime());
    }

    @GetMapping("/essays/{id}/result")
    public ResponseEntity<Map<String, Object>> getResult(@PathVariable Long id) {
        return ResponseEntity.ok(essayService.getResult(id));
    }

    @PostMapping("/essays/{id}/human-scores")
    public ResponseEntity<Map<String, Object>> saveHumanScores(@PathVariable Long id,
            @RequestBody Map<String, Object> payload) {
        return ResponseEntity.status(HttpStatus.CREATED).body(essayService.saveHumanScores(id, payload));
    }

    @GetMapping("/essays/{id}/human-scores")
    public ResponseEntity<List<Map<String, Object>>> listHumanScores(@PathVariable Long id) {
        return ResponseEntity.ok(essayService.listHumanScores(id));
    }

    @PostMapping("/essays/{id}/human-review-requests")
    public ResponseEntity<Map<String, Object>> requestHumanReview(@PathVariable Long id,
            @RequestBody(required = false) Map<String, Object> payload) {
        Long runId = payload != null && payload.get("runId") instanceof Number n ? n.longValue() : null;
        return ResponseEntity.status(HttpStatus.CREATED).body(trainingCycle.requestHumanReview(id, runId));
    }

    @GetMapping("/essays/{id}/human-review-request")
    public ResponseEntity<Map<String, Object>> humanReviewStatus(@PathVariable Long id) {
        return ResponseEntity.ok(trainingCycle.currentRequest(id, false));
    }

    @GetMapping("/reviewer/review-requests")
    public ResponseEntity<List<Map<String, Object>>> reviewerQueue(
            @RequestParam(required = false) String status) {
        return ResponseEntity.ok(trainingCycle.reviewerQueue(status));
    }

    @PostMapping("/reviewer/review-requests/{id}/claim")
    public ResponseEntity<Map<String, Object>> claimReview(@PathVariable Long id,
            @RequestHeader(value = "X-Reviewer-Id", defaultValue = "pilot-reviewer") String reviewer) {
        return ResponseEntity.ok(trainingCycle.claim(id, reviewer));
    }

    @GetMapping("/reviewer/review-requests/{id}")
    public ResponseEntity<Map<String, Object>> reviewerRequest(@PathVariable Long id,
            @RequestHeader(value = "X-Reviewer-Id", defaultValue = "pilot-reviewer") String reviewer) {
        return ResponseEntity.ok(trainingCycle.reviewerRequest(id, reviewer));
    }

    @PostMapping("/reviewer/review-requests/{id}/draft")
    public ResponseEntity<Map<String, Object>> saveReviewDraft(@PathVariable Long id,
            @RequestHeader(value = "X-Reviewer-Id", defaultValue = "pilot-reviewer") String reviewer,
            @RequestBody HumanEvaluationRequest request) {
        return ResponseEntity.ok(trainingCycle.saveHumanEvaluation(id, reviewer, request, false));
    }

    @PostMapping("/reviewer/review-requests/{id}/publish")
    public ResponseEntity<Map<String, Object>> publishReview(@PathVariable Long id,
            @RequestHeader(value = "X-Reviewer-Id", defaultValue = "pilot-reviewer") String reviewer,
            @RequestBody HumanEvaluationRequest request) {
        return ResponseEntity.ok(trainingCycle.saveHumanEvaluation(id, reviewer, request, true));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRunDetails(@PathVariable Long id) {
        return ResponseEntity.ok(essayService.getRunDetails(id));
    }

    @PostMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> runBenchmark(@Validated @RequestBody BenchmarkRequest request) {
        return ResponseEntity.ok(essayService.runBenchmark(request.temaId(), request.texts(), request.profile()));
    }

    @PostMapping("/benchmark/compare")
    public ResponseEntity<Map<String, Object>> compareBenchmark(@Validated @RequestBody BenchmarkComparisonRequest request) {
        return ResponseEntity.ok(essayService.compareProfiles(request.temaId(), request.profiles(), request.texts()));
    }

}
