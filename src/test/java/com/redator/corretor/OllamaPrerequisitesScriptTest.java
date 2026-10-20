package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaPrerequisitesScriptTest {
    private static final String DIGEST = "b37049369adfe3d2b653af0ab301a062ca5cbe96ace6aa0d3f2559ee9b563fc2";
    @TempDir Path temporaryDirectory;

    @Test
    void healthyNativeRuntimeDoesNotRequireOrInvokeDocker() throws Exception {
        RunResult result = runPrerequisites("0.40.4", "Q4_K_M");
        assertThat(result.exitCode()).as(result.output()).isZero();
        assertThat(result.output()).contains("Ollama já está disponível").contains("Ollama 0.40.4 pronto");
        assertThat(result.dockerMarker()).doesNotExist();
    }

    @Test
    void refusesRuntimeWhoseQuantizationDiffersFromTheApprovedManifest() throws Exception {
        RunResult result = runPrerequisites("0.40.4", "Q8_0");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Quantização do modelo diverge");
        assertThat(result.dockerMarker()).doesNotExist();
    }

    @Test
    void refusesRuntimeVersionDifferentFromTheApprovedManifest() throws Exception {
        RunResult result = runPrerequisites("9.9.9", "Q4_K_M");

        assertThat(result.exitCode()).isNotZero();
        assertThat(result.output()).contains("Versão do Ollama diverge");
        assertThat(result.dockerMarker()).doesNotExist();
    }

    private RunResult runPrerequisites(String version, String quantization) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> respond(exchange, "{\"version\":\"" + version + "\"}"));
        server.createContext("/api/tags", exchange -> respond(exchange,
                "{\"models\":[{\"name\":\"gemma4:e2b-it-q4_K_M\",\"digest\":\"" + DIGEST + "\"}]}"));
        server.createContext("/api/show", exchange -> respond(exchange,
                "{\"capabilities\":[\"completion\"],\"details\":{\"quantization_level\":\"" + quantization + "\"}}"));
        server.start();
        try {
            Path marker = temporaryDirectory.resolve("docker-called");
            Path fakeBin = Files.createDirectories(temporaryDirectory.resolve("bin"));
            Path docker = fakeBin.resolve("docker");
            Files.writeString(docker, "#!/usr/bin/env bash\ntouch '" + marker + "'\nexit 99\n");
            docker.toFile().setExecutable(true);
            Path corpus = temporaryDirectory.resolve("corpus.db");
            Files.writeString(corpus, "non-empty");

            ProcessBuilder builder = new ProcessBuilder("bash", "scripts/ollama-prerequisites.sh")
                    .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                    .redirectErrorStream(true);
            builder.environment().put("PATH", fakeBin + ":" + System.getenv("PATH"));
            builder.environment().put("OLLAMA_BASE_URL", "http://127.0.0.1:" + server.getAddress().getPort());
            builder.environment().put("RAG_DATABASE", corpus.toString());
            Process process = builder.start();
            assertThat(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return new RunResult(process.exitValue(), output, marker);
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record RunResult(int exitCode, String output, Path dockerMarker) { }
}
