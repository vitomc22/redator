package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.config.OllamaProperties;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.LocalEvaluationContract;
import com.redator.corretor.service.OllamaLlmProvider;
import com.redator.corretor.service.PublicHumanCorpus;
import com.redator.corretor.service.EvaluationFailure;
import com.redator.corretor.service.RagService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class OllamaLlmProviderTest {
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    @Test
    void reservesEveryOutputTokenAndTemplateTokenBeforeContactingOllama(@TempDir Path temp) {
        var contract = new LocalEvaluationContract();
        var theme = new Tema(1L, "Educação", "Direito à educação", "");
        String text = "A educação pública é um direito. 📝";
        var serializedInput = contract.json(java.util.Map.of(
                "messages", contract.messages(text, theme), "format", contract.schemaForText(text)));
        int estimatedInputTokens = (int) Math.ceil(serializedInput.getBytes(StandardCharsets.UTF_8).length / 3.0);
        var properties = new OllamaProperties();
        properties.setBaseUrl("http://127.0.0.1:1");
        properties.setNumPredict(600);
        properties.setNumCtx(estimatedInputTokens + 600 + 255);
        var provider = new OllamaLlmProvider(properties, contract,
                new RagService(temp.resolve("unused.db"), false, 3, 1200));

        assertThatThrownBy(() -> provider.prepare(text, theme, 1))
                .isInstanceOfSatisfying(EvaluationFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("CONTEXT_LIMIT"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"lexical-v1", "calibration-v2"})
    void fitsReferencesWithoutTruncatingStudentEssay(String policy, @TempDir Path temp) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/version", exchange -> respond(exchange, "{\"version\":\"0.40.4\"}"));
        server.createContext("/api/tags", exchange -> respond(exchange,
                "{\"models\":[{\"name\":\"gemma4:e2b-it-q4_K_M\",\"digest\":\"" + "a".repeat(64) + "\"}]}"));
        server.createContext("/api/show", exchange -> respond(exchange, "{\"capabilities\":[\"completion\"]}"));
        server.start();
        Path database = temp.resolve("budget-corpus.db");
        try (var corpus = new PublicHumanCorpus(database)) {
            for (int i = 0; i < 3; i++) {
                corpus.importEntries(java.util.List.of(new PublicHumanCorpus.ImportEntry(
                        "fixture", "budget", "example-" + i, "train", "educacao", "Educação",
                        "A educação exige atenção. " + "Argumentação e cidadania. ".repeat(80)
                                + "Portanto, o Ministério deve ampliar o ensino. Caso " + i,
                        2024, "MIT", "https://example.test/license", "https://example.test/corpus",
                        new PublicHumanCorpus.HumanScore("human", 160, 160, 160, 160, 160, 800))), "budget-v1");
            }
        }
        var rag = new RagService(database, true, 3, 1200, policy);
        var contract = new LocalEvaluationContract();
        String text = "A educação pública reduz desigualdades e deve ser garantida a todos os cidadãos. ".repeat(12);
        var theme = new Tema(1L, "Educação", "Direito à educação", "");
        var fullContext = rag.retrieve(text, theme.titulo());
        assertThat(fullContext.references()).hasSize(3);
        var twoReferences = new RagService.Context(true, fullContext.corpusFingerprint(),
                fullContext.references().subList(0, 2), fullContext.policy());
        int inputBytes = contract.json(java.util.Map.of("messages", contract.messages(text, theme, twoReferences),
                "format", contract.schemaForText(text))).getBytes(StandardCharsets.UTF_8).length;
        var properties = new OllamaProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setNumCtx((int) Math.ceil(inputBytes / 3.0) + properties.getNumPredict() + 256);

        var prepared = new OllamaLlmProvider(properties, contract, rag).prepare(text, theme, 1);

        assertThat(prepared.audit()).containsEntry("ragExampleCount", 2)
                .containsEntry("ragOmittedExamplesForContext", 1);
        assertThat(((Number) prepared.audit().get("estimatedInputTokens")).longValue()
                + properties.getNumPredict() + 256).isLessThanOrEqualTo(properties.getNumCtx());
        var messages = (java.util.List<java.util.Map<String, String>>) prepared.payload().get("messages");
        assertThat(messages.getLast().get("content")).contains(text);
    }

    @ParameterizedTest
    @ValueSource(strings = {"lexical-v1", "calibration-v2"})
    void shouldSendStructuredRequestWithHumanRagAndAuditReferences(String policy, @TempDir Path temp) throws Exception {
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
                new com.redator.corretor.service.RagService(ragDatabase, true, 3, 1200, policy));
        String text = "A educação é um direito fundamental.";

        var generated = provider.generate(provider.prepare(text,
                new Tema(1L, "Educação", "Direito à educação", ""), 1L), text);

        assertThat(generated.result().competences()).hasSize(5);
        assertThat(generated.result().sum()).isEqualTo(800);
        assertThat(generated.audit()).containsEntry("model", "gemma4:e2b-it-q4_K_M");
        assertThat(generated.audit()).containsEntry("ragEnabled", true).containsEntry("ragExampleCount", 1);
        assertThat(generated.audit()).containsEntry("ragPolicy", policy);
        var capturedMessages = new com.fasterxml.jackson.databind.ObjectMapper().readTree(chatRequest.get()).path("messages");
        assertThat(generated.audit()).containsEntry("requestMessagesHash",
                LocalEvaluationContract.hash(new LocalEvaluationContract().json(capturedMessages)));
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
