package com.redator.corretor.controller;

import com.redator.corretor.model.BenchmarkRequest;
import com.redator.corretor.model.CreateEssayRequest;
import com.redator.corretor.model.EssayResponse;
import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.model.EvaluationResult;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.EssayService;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class EssayController {

    private final EssayService essayService;

    public EssayController(EssayService essayService) {
        this.essayService = essayService;
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

    @PutMapping("/essays/{id}/text")
    public ResponseEntity<Map<String, Object>> updateEssayText(@PathVariable Long id, @RequestBody Map<String, String> payload) {
        if (!payload.containsKey("text") || payload.get("text") == null || payload.get("text").isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "text obrigatorio");
        }
        EssayResponse essay = essayService.updateEssayText(id, payload.get("text"));
        return ResponseEntity.ok(Map.of("essay", essay));
    }

    @PostMapping("/essays/{id}/evaluate")
    public ResponseEntity<Map<String, Object>> evaluateEssay(@PathVariable Long id, @RequestParam(defaultValue = "cheap") String profile) {
        EvaluationProfile evaluationProfile = EvaluationProfile.from(profile);
        EvaluationResult result = essayService.evaluateEssay(id, evaluationProfile.name().toLowerCase());
        return ResponseEntity.ok(Map.of(
                "runId", result.runId(),
                "total", result.total(),
                "needsReview", result.needsReview(),
                "profile", result.profile(),
                "competences", result.competences()
        ));
    }

    @GetMapping("/essays/{id}/result")
    public ResponseEntity<Map<String, Object>> getResult(@PathVariable Long id) {
        return ResponseEntity.ok(essayService.getResult(id));
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<Map<String, Object>> getRunDetails(@PathVariable Long id) {
        return ResponseEntity.ok(essayService.getRunDetails(id));
    }

    @PostMapping("/benchmark")
    public ResponseEntity<Map<String, Object>> runBenchmark(@Validated @RequestBody BenchmarkRequest request) {
        return ResponseEntity.ok(essayService.runBenchmark(request.temaId(), request.texts(), request.profile()));
    }
}
