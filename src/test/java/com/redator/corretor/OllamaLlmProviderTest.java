package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.config.OllamaProperties;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.LocalEvaluationContract;
import com.redator.corretor.service.OllamaLlmProvider;
import com.redator.corretor.service.PublicHumanCorpus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OllamaLlmProviderTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void shouldSendStructuredRequestWithHumanRagAndAuditReferences(@TempDir Path temp) throws Exception {
        AtomicReference<String> chatRequest = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> respond(exchange, "{\"version\":\"0.12.0\"}"));
        server.createContext("/api/tags", exchange -> respond(exchange,
                "{\"models\":[{\"name\":\"gemma4:e2b-it-q4_K_M\",\"digest\":\"" + "a".repeat(64) + "\"}]}"));
        server.createContext("/api/show", exchange -> respond(exchange,
                "{\"capabilities\":[\"completion\",\"thinking\"]}"));
        server.createContext("/api/chat", exchange -> {
            chatRequest.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String content = "{\"competences\":["
                    + item("C1") + "," + item("C2") + "," + item("C3") + "," + item("C4") + "," + item("C5")
                    + "],\"globalFlags\":[],\"limitations\":[]}";
            String escaped = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(content);
            respond(exchange, "{\"model\":\"gemma4:e2b-it-q4_K_M\",\"message\":{\"role\":\"assistant\",\"content\":"
                    + escaped + "},\"done\":true,\"done_reason\":\"stop\",\"total_duration\":1000000000,"
                    + "\"load_duration\":1,\"prompt_eval_count\":100,\"prompt_eval_duration\":1,"
                    + "\"eval_count\":100,\"eval_duration\":1000000000}");
        });
        server.start();

        OllamaProperties properties = new OllamaProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setNumCtx(8192);
        Path ragDatabase = temp.resolve("rag.db");
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(ragDatabase)) {
            corpus.importEntries(java.util.List.of(new PublicHumanCorpus.ImportEntry(
                    "essay-br", "fixture", "human-1", "train", "educacao", "Direito à educação",
                    "A educação pública reduz desigualdades e fortalece a cidadania.", 2024, "MIT",
                    "https://github.com/lplnufpi/essay-br", "https://github.com/lplnufpi/essay-br",
                    new PublicHumanCorpus.HumanScore("human", 180, 160, 160, 180, 160, 840)
            )), "test-v1");
        }
        OllamaLlmProvider provider = new OllamaLlmProvider(properties, new LocalEvaluationContract(),
                new com.redator.corretor.service.RagService(ragDatabase, true, 3, 1200));
        String text = "A educação é um direito fundamental.";

        var generated = provider.generate(provider.prepare(text,
                new Tema(1L, "Educação", "Direito à educação", ""), 1L), text);

        assertThat(generated.result().competences()).hasSize(5);
        assertThat(generated.result().sum()).isEqualTo(800);
        assertThat(generated.audit()).containsEntry("model", "gemma4:e2b-it-q4_K_M");
        assertThat(generated.audit()).containsEntry("ragEnabled", true).containsEntry("ragExampleCount", 1);
        assertThat(generated.audit().get("ragCorpusFingerprint").toString()).matches("[a-f0-9]{64}");
        assertThat(chatRequest.get()).contains("\"stream\":false", "\"format\":", "\"think\":false", "\"num_ctx\":8192",
                "Exemplos humanos públicos", "A educação pública reduz desigualdades", "humanScoresC1ToC5");
    }

    private static String item(String id) {
        return "{\"id\":\"" + id + "\",\"score\":160,\"justification\":\"Nível compatível com o trecho.\","
                + "\"evidence\":[\"A educação é um direito fundamental.\"],"
                + "\"improvement\":\"Aprofunde o argumento.\",\"evidenceKind\":\"passage\"}";
    }

    private static void respond(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
