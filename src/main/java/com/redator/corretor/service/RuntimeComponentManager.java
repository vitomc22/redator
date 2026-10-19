package com.redator.corretor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Installs/verifies the pinned local model without ever falling back to a cloud provider. */
@Service
public class RuntimeComponentManager {
    private final URI baseUri;
    private final String model;
    private final String expectedDigest;
    private final String expectedQuantization;
    private final Path dataDirectory;
    private final boolean autoPull;
    private final Duration timeout;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient client;

    @Autowired
    public RuntimeComponentManager(
            @Value("${app.llm.ollama.base-url}") String baseUrl,
            @Value("${app.runtime.model.tag:${app.llm.ollama.model}}") String model,
            @Value("${app.runtime.model.digest:}") String expectedDigest,
            @Value("${app.runtime.model.quantization:Q4_K_M}") String expectedQuantization,
            @Value("${app.data-directory}") String dataDirectory,
            @Value("${app.runtime.auto-pull:true}") boolean autoPull,
            @Value("${app.runtime.bootstrap-timeout-seconds:1800}") int timeoutSeconds) {
        this(baseUrl, model, expectedDigest, expectedQuantization, Path.of(dataDirectory), autoPull, timeoutSeconds);
    }

    public RuntimeComponentManager(String baseUrl, String model, String expectedDigest,
                                   String expectedQuantization, Path dataDirectory,
                                   boolean autoPull, int timeoutSeconds) {
        this.baseUri = validateLoopback(baseUrl);
        this.model = requireText(model, "tag do modelo");
        this.expectedDigest = normalizeDigest(expectedDigest);
        this.expectedQuantization = requireText(expectedQuantization, "quantização");
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
        this.autoPull = autoPull;
        this.timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public RuntimeState ensureReady() {
        JsonNode version = get("/api/version");
        JsonNode installed = findModel(get("/api/tags"));
        if (installed == null) {
            if (!autoPull) throw new IllegalStateException("Gemma obrigatório ausente e download automático desativado");
            post("/api/pull", Map.of("model", model, "stream", false));
            installed = findModel(get("/api/tags"));
            if (installed == null) throw new IllegalStateException("Ollama concluiu o download sem publicar o modelo " + model);
        }
        String digest = normalizeDigest(installed.path("digest").asText());
        if (digest.isBlank()) throw new IllegalStateException("Modelo instalado não informou digest verificável");
        if (!expectedDigest.isBlank() && !expectedDigest.equals(digest)) {
            throw new IllegalStateException("Modelo recusado: digest diferente do manifesto fixado");
        }
        JsonNode show = post("/api/show", Map.of("model", model));
        boolean completion = false;
        for (JsonNode capability : show.path("capabilities")) completion |= "completion".equals(capability.asText());
        if (!completion) throw new IllegalStateException("Modelo não oferece capacidade completion");
        String quantization = show.path("details").path("quantization_level").asText();
        if (!expectedQuantization.equalsIgnoreCase(quantization)) {
            throw new IllegalStateException("Modelo recusado: quantização " + quantization + " difere de " + expectedQuantization);
        }
        RuntimeState state = new RuntimeState(version.path("version").asText(), model, digest,
                quantization, true, Instant.now().toString());
        persist(state);
        return state;
    }

    private JsonNode findModel(JsonNode tags) {
        for (JsonNode candidate : tags.path("models")) {
            if (model.equals(candidate.path("name").asText()) || model.equals(candidate.path("model").asText())) return candidate;
        }
        return null;
    }

    private JsonNode get(String path) {
        return send(HttpRequest.newBuilder(baseUri.resolve(path)).timeout(Duration.ofSeconds(10)).GET().build());
    }

    private JsonNode post(String path, Map<String, Object> payload) {
        try {
            String body = mapper.writeValueAsString(payload);
            return send(HttpRequest.newBuilder(baseUri.resolve(path)).timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build());
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível criar requisição ao Ollama", exception);
        }
    }

    private JsonNode send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Ollama respondeu HTTP " + response.statusCode() + " em " + request.uri().getPath());
            }
            return mapper.readTree(response.body());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Verificação do runtime interrompida", exception);
        } catch (IOException exception) {
            throw new IllegalStateException("Ollama local indisponível em " + baseUri, exception);
        }
    }

    private void persist(RuntimeState state) {
        Path runtime = dataDirectory.resolve("runtime");
        Path target = runtime.resolve("manifest.json");
        Path temporary = runtime.resolve("manifest.json.tmp");
        try {
            Files.createDirectories(runtime);
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("schemaVersion", 1);
            document.put("ollamaVersion", state.ollamaVersion());
            document.put("model", state.model());
            document.put("digest", state.digest());
            document.put("quantization", state.quantization());
            document.put("verified", state.verified());
            document.put("verifiedAt", state.verifiedAt());
            mapper.writeValue(temporary.toFile(), document);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Não foi possível persistir o manifesto verificado do runtime", exception);
        }
    }

    private static URI validateLoopback(String value) {
        URI uri = URI.create(value.endsWith("/") ? value : value + "/");
        String host = uri.getHost();
        if (!"http".equalsIgnoreCase(uri.getScheme()) ||
                !("127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host) || "::1".equals(host))) {
            throw new IllegalArgumentException("Ollama obrigatório deve usar HTTP em loopback");
        }
        return uri;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " obrigatório");
        return value.trim();
    }

    private static String normalizeDigest(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase().replaceFirst("^sha256:", "");
    }

    public record RuntimeState(String ollamaVersion, String model, String digest,
                               String quantization, boolean verified, String verifiedAt) {}
}
