package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.service.RuntimeComponentManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeComponentManagerTest {
    @TempDir Path temp;

    @Test
    void downloadsMissingPinnedGemmaAndPersistsVerifiedRuntimeManifest() throws Exception {
        AtomicInteger pulls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> respond(exchange, 200, "{\"version\":\"0.40.4\"}"));
        server.createContext("/api/tags", exchange -> {
            String body = pulls.get() == 0 ? "{\"models\":[]}" : """
                    {"models":[{"name":"gemma-test:latest","digest":"%s"}]}
                    """.formatted("a".repeat(64));
            respond(exchange, 200, body);
        });
        server.createContext("/api/pull", exchange -> {
            assertThat(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .contains("gemma-test:latest");
            pulls.incrementAndGet();
            respond(exchange, 200, "{\"status\":\"success\"}\n");
        });
        server.createContext("/api/show", exchange -> respond(exchange, 200,
                "{\"capabilities\":[\"completion\"],\"details\":{\"quantization_level\":\"Q4_K_M\"}}"));
        server.start();
        try {
            RuntimeComponentManager manager = new RuntimeComponentManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "gemma-test:latest",
                    "a".repeat(64), "Q4_K_M", temp, true, 5);

            RuntimeComponentManager.RuntimeState state = manager.ensureReady();

            assertThat(pulls).hasValue(1);
            assertThat(state.model()).isEqualTo("gemma-test:latest");
            assertThat(state.digest()).isEqualTo("a".repeat(64));
            assertThat(Files.readString(temp.resolve("runtime/manifest.json")))
                    .contains("\"verified\" : true", "gemma-test:latest", "0.40.4");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void refusesModelWhoseDigestDiffersFromThePinnedManifest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> respond(exchange, 200, "{\"version\":\"0.40.4\"}"));
        server.createContext("/api/tags", exchange -> respond(exchange, 200, """
                {"models":[{"name":"gemma-test:latest","digest":"%s"}]}
                """.formatted("b".repeat(64))));
        server.start();
        try {
            RuntimeComponentManager manager = new RuntimeComponentManager(
                    "http://127.0.0.1:" + server.getAddress().getPort(), "gemma-test:latest",
                    "a".repeat(64), "Q4_K_M", temp, true, 5);

            org.assertj.core.api.Assertions.assertThatThrownBy(manager::ensureReady)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("digest");
        } finally {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
