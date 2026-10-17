package com.redator.corretor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.redator.corretor.config.OllamaProperties;
import com.redator.corretor.model.Tema;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class OllamaLlmProvider {
    private final OllamaProperties config;
    private final LocalEvaluationContract contract;
    private final RagService rag;
    private final HttpClient http;
    private final AtomicBoolean uncertainGeneration = new AtomicBoolean();
    public OllamaLlmProvider(OllamaProperties config, LocalEvaluationContract contract, RagService rag) {
        this.config = config;
        this.contract = contract;
        this.rag = rag;
        URI base = URI.create(config.getBaseUrl());
        if (!Set.of("http", "https").contains(base.getScheme()) || base.getHost() == null || base.getUserInfo() != null)
            throw new IllegalArgumentException("Base URL Ollama inválida");
        if (config.getNumCtx() < 1024 || config.getNumPredict() < 1 || config.getNumPredict() >= config.getNumCtx()
                || config.getNumThread() < 1 || config.getTimeoutSeconds() < 1 || config.getConnectTimeoutSeconds() < 1)
            throw new IllegalArgumentException("Configuração Ollama inválida");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.getConnectTimeoutSeconds())).build();
    }
    public Map<String, Object> configuration() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("model", config.getModel()); result.put("options", options());
        result.put("keep_alive", config.getKeepAlive()); result.put("timeoutSeconds", config.getTimeoutSeconds());
        result.put("runtimeQuarantined", uncertainGeneration.get()); result.put("rag", rag.status());
        return result;
    }
    private Map<String, Object> options() {
        return Map.of("num_ctx", config.getNumCtx(), "num_predict", config.getNumPredict(), "num_thread", config.getNumThread(),
                "temperature", config.getTemperature(), "seed", config.getSeed());
    }
    /** No model downloads or inference here. Identity is re-read before cache lookup. */
    public Prepared prepare(String text, Tema theme, long revision) {
        if (uncertainGeneration.get()) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_QUARANTINED",
                "Geração anterior pode continuar no Ollama. Confirme sua interrupção e reinicie o backend antes de avaliar novamente.");
        RagService.Context ragContext = rag.retrieve(text, theme.titulo());
        var messages = contract.messages(text, theme, ragContext);
        JsonNode outputSchema = contract.schemaForText(text);
        // UTF-8 bytes are a conservative token upper estimate, with template reserve.
        // Include schema because constrained decoding overhead varies by runtime.
        int estimate = contract.json(Map.of("messages", messages, "format", outputSchema)).getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (estimate + config.getNumPredict() + 256 > config.getNumCtx() * 3) {
            throw failure(HttpStatus.UNPROCESSABLE_ENTITY, "CONTEXT_LIMIT", "Texto, proposta e rubrica excedem o guard conservador de contexto; nada foi cortado.");
        }
        JsonNode version = request("/api/version", null, false);
        if (!version.path("version").isTextual() || version.path("version").asText().isBlank()) throw invalid();
        JsonNode tags = request("/api/tags", null, false);
        String digest = null;
        for (JsonNode model : tags.path("models")) {
            if (config.getModel().equals(model.path("name").asText()) || config.getModel().equals(model.path("model").asText()))
                digest = model.path("digest").asText();
        }
        if (digest == null) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "MODEL_NOT_FOUND", "Modelo configurado ausente no Ollama; instale-o explicitamente.");
        if (!digest.matches("(?:sha256:)?[0-9a-fA-F]{64}")) throw invalid();
        JsonNode show = request("/api/show", Map.of("model", config.getModel()), false);
        boolean thinking = false, completion = false;
        for (JsonNode capability : show.path("capabilities")) {
            thinking |= capability.asText().equals("thinking");
            completion |= capability.asText().equals("completion");
        }
        if (!completion) throw failure(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_INCOMPATIBLE", "Runtime não declara capacidade de completion para o modelo.");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", config.getModel()); payload.put("messages", messages); payload.put("stream", false);
        payload.put("format", outputSchema); payload.put("options", options()); payload.put("keep_alive", config.getKeepAlive());
        if (thinking) payload.put("think", false);
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("provider", "ollama"); audit.put("model", config.getModel()); audit.put("modelDigest", digest);
        audit.put("runtimeVersion", version.path("version").asText()); audit.put("promptHash", contract.promptHash());
        audit.put("rubricHash", contract.rubricHash()); audit.put("schemaVersionHash", contract.schemaHash());
        audit.put("schemaHash", LocalEvaluationContract.hash(contract.json(outputSchema)));
        audit.put("evidenceCandidateCount", contract.evidenceCandidates(text).size());
        audit.put("generationParameters", options()); audit.put("thinkingControl", thinking ? "disabled" : "unsupported-not-sent");
        audit.put("keepAlive", config.getKeepAlive()); audit.put("essayRevision", revision); audit.put("textHash", LocalEvaluationContract.hash(text));
        audit.put("themeHash", LocalEvaluationContract.hash(contract.json(theme)));
        audit.put("ragEnabled", ragContext.enabled()); audit.put("ragCorpusFingerprint", ragContext.corpusFingerprint());
        audit.put("ragExampleCount", ragContext.references().size()); audit.put("ragExamples", ragContext.auditExamples());
        audit.put("contextGuard", "utf8-bytes/3 estimate + output + 256 template reserve; not exact token count");
        audit.put("estimatedInputTokens", (int) Math.ceil(estimate / 3.0));
        String key = LocalEvaluationContract.hash(contract.json(audit) + contract.json(payload));
        audit.put("cacheKey", key);
        return new Prepared(payload, audit, key);
    }
    public Generated generate(Prepared prepared, String text) {
        long started = System.nanoTime();
        JsonNode envelope = request("/api/chat", prepared.payload(), true);
        Map<String, Object> audit = new LinkedHashMap<>(prepared.audit());
        audit.put("wallMs", (System.nanoTime() - started) / 1_000_000.0);
        if (!envelope.path("done").isBoolean() || !envelope.path("done").asBoolean()
                || !envelope.path("done_reason").asText().equals("stop"))
            throw failure(HttpStatus.BAD_GATEWAY, "TRUNCATED_RESPONSE", "Geração não concluída normalmente; nenhuma nota publicada.");
        if (!config.getModel().equals(envelope.path("model").asText()) || !envelope.path("message").path("role").asText().equals("assistant")
                || !envelope.path("message").path("content").isTextual()) throw invalid();
        if (!envelope.path("message").path("thinking").asText("").isEmpty())
            throw failure(HttpStatus.BAD_GATEWAY, "RUNTIME_INCOMPATIBLE", "Runtime retornou thinking apesar da configuração.");
        for (String key : List.of("total_duration", "load_duration", "prompt_eval_duration", "eval_duration", "prompt_eval_count", "eval_count")) {
            JsonNode value = envelope.path(key);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) throw invalid();
            audit.put(key, value.longValue());
            if (key.endsWith("duration")) audit.put(key + "_ms", value.longValue() / 1_000_000.0);
        }
        if (envelope.path("prompt_eval_count").asLong() + config.getNumPredict() >= config.getNumCtx())
            throw failure(HttpStatus.BAD_GATEWAY, "CONTEXT_LIMIT", "Contagem observada não garante contexto íntegro; resultado rejeitado.");
        double seconds = envelope.path("eval_duration").asLong() / 1e9;
        audit.put("generationTokensPerSecond", seconds > 0 ? envelope.path("eval_count").asLong() / seconds : 0);
        audit.put("costUsd", 0); audit.put("doneReason", "stop");
        var validated = contract.validate(envelope.path("message").path("content").asText(), text);
        return new Generated(validated, audit);
    }
    private JsonNode request(String path, Object body, boolean inference) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(config.getBaseUrl().replaceAll("/$", "") + path))
                    .timeout(Duration.ofSeconds(inference ? config.getTimeoutSeconds() : config.getConnectTimeoutSeconds()));
            if (body != null) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(contract.json(body)));
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw failure(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode() == 404 ? "MODEL_NOT_FOUND" : "RUNTIME_UNAVAILABLE",
                        "Ollama recusou a solicitação (HTTP " + response.statusCode() + "). Nenhuma nota gerada.");
            }
            JsonNode node = contract.parse(response.body());
            if (node == null || !node.isObject() || node.has("error")) throw invalid();
            return node;
        } catch (HttpTimeoutException ex) {
            if (inference) uncertainGeneration.set(true);
            throw failure(HttpStatus.GATEWAY_TIMEOUT, "INFERENCE_TIMEOUT", "Ollama excedeu o prazo; sem retry automático ou nota.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); if (inference) uncertainGeneration.set(true);
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "INFERENCE_INTERRUPTED", "Chamada interrompida; estado do runtime precisa ser verificado.");
        } catch (IOException ex) {
            if (inference && !(ex instanceof java.net.ConnectException)) uncertainGeneration.set(true);
            throw failure(HttpStatus.SERVICE_UNAVAILABLE, "RUNTIME_UNAVAILABLE", "Ollama indisponível ou conexão interrompida; nenhuma nota gerada.");
        }
    }
    private EvaluationFailure invalid() { return failure(HttpStatus.BAD_GATEWAY, "INVALID_RESPONSE", "Envelope Ollama inválido."); }
    private EvaluationFailure failure(HttpStatus status, String code, String message) { return new EvaluationFailure(status, code, message); }
    public record Prepared(Map<String, Object> payload, Map<String, Object> audit, String cacheKey) {}
    public record Generated(LocalEvaluationContract.Validated result, Map<String, Object> audit) {}
}
