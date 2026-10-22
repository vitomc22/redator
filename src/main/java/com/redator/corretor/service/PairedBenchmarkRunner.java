package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.model.CreateEssayRequest;
import com.redator.corretor.model.EssayResponse;
import com.redator.corretor.model.EvaluationResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Runs one auditable benchmark arm. Use separate application processes for baseline and RAG. */
@Service
public class PairedBenchmarkRunner {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final EssayService essays;
    private final JdbcTemplate jdbc;
    private final LlmGateway llm;
    private final RagService rag;

    public PairedBenchmarkRunner(EssayService essays, JdbcTemplate jdbc, LlmGateway llm, RagService rag) {
        this.essays = essays; this.jdbc = jdbc; this.llm = llm; this.rag = rag;
    }

    public Map<String, Object> run(Path benchmarkFile, Path output, String requestedArm) throws Exception {
        Map<String, Object> benchmark = JSON.readValue(Files.readString(benchmarkFile), new TypeReference<>() { });
        List<Map<String, Object>> samples = objects(benchmark.get("samples"), "samples");
        String fingerprint = required(benchmark, "selectionFingerprint");
        verifyFrozenSelection(samples, fingerprint, benchmark.get("sampleCount"));
        boolean ragEnabled = Boolean.TRUE.equals(rag.status().get("enabled"));
        String arm = ragEnabled ? "rag" : "baseline";
        if (!arm.equals(requestedArm))
            throw new IllegalArgumentException("braço " + requestedArm + " exige app.rag.enabled=" + "rag".equals(requestedArm));

        Map<String, Object> document = new LinkedHashMap<>();
        document.put("schemaVersion", 1); document.put("arm", arm);
        document.put("selectionFingerprint", fingerprint);
        document.put("runtime", llm.runtime()); document.put("rag", rag.status());
        document.put("configFingerprint", LocalEvaluationContract.hash(JSON.writeValueAsString(
                Map.of("runtime", llm.runtime(), "rag", rag.status()))));
        List<Map<String, Object>> results = new ArrayList<>();
        document.put("results", results);

        for (Map<String, Object> sample : samples) {
            String hash = required(sample, "essayHash");
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("essayHash", hash);
            try {
                long themeId = insertTheme(String.valueOf(sample.get("theme")), hash);
                EssayResponse essay = essays.createEssay(new CreateEssayRequest(themeId, required(sample, "text")));
                EvaluationResult evaluation = essays.evaluateEssay(essay.id(), "normal");
                item.put("status", evaluation.status()); item.put("runId", evaluation.runId());
                item.put("provider", evaluation.provider()); item.put("model", evaluation.model());
                item.put("scores", scoreMap(evaluation.competences()));
                item.put("needsReview", evaluation.needsReview()); item.put("reviewReasons", evaluation.reviewReasons());
                item.put("llmCalls", essays.getRunDetails(evaluation.runId()).get("llmCalls"));
            } catch (EvaluationFailure failure) {
                item.put("status", "FAILED"); item.put("failureCode", failure.code()); item.put("runId", failure.runId());
            } catch (RuntimeException failure) {
                item.put("status", "FAILED"); item.put("failureCode", "INTERNAL_FAILURE");
                item.put("failureType", failure.getClass().getSimpleName());
            }
            results.add(item);
            document.put("completedCount", results.size());
            writeAtomically(output, document);
        }
        document.put("complete", true);
        writeAtomically(output, document);
        return document;
    }

    private long insertTheme(String rawTheme, String hash) {
        String theme = rawTheme == null || rawTheme.isBlank() ? "Tema não informado" : rawTheme.strip();
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement("INSERT INTO tema (titulo, recorte, motivadores) VALUES (?, ?, ?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, theme);
            statement.setString(2, theme);
            statement.setString(3, "Tema proveniente do benchmark humano congelado " + hash);
            return statement;
        }, key);
        if (key.getKey() == null) throw new IllegalStateException("tema do benchmark não foi criado");
        return key.getKey().longValue();
    }

    private static Map<String, Integer> scoreMap(Map<String, CompetenceResult> competences) {
        Map<String, Integer> values = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5")) {
            CompetenceResult result = competences.get(code);
            if (result == null) throw new IllegalStateException("resultado sem " + code);
            values.put(code, result.nota());
        }
        return values;
    }

    private static void verifyFrozenSelection(List<Map<String, Object>> samples, String fingerprint, Object count) throws Exception {
        String actual = DatasetManifestValidator.sha256(JSON.writeValueAsBytes(samples));
        if (!actual.equals(fingerprint)) throw new IllegalArgumentException("impressão da seleção congelada não confere");
        if (!(count instanceof Number number) || number.intValue() != samples.size())
            throw new IllegalArgumentException("sampleCount não confere com a seleção congelada");
        for (Map<String, Object> sample : samples) {
            if (!required(sample, "essayHash").equals(PublicHumanCorpus.hashText(required(sample, "text"))))
                throw new IllegalArgumentException("essayHash não corresponde ao texto congelado");
        }
    }

    private static void writeAtomically(Path output, Map<String, Object> document) throws Exception {
        Path absolute = output.toAbsolutePath().normalize();
        if (absolute.getParent() != null) Files.createDirectories(absolute.getParent());
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".tmp");
        Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n",
                StandardCharsets.UTF_8);
        try { Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value, String label) {
        if (!(value instanceof List<?> list) || list.isEmpty()) throw new IllegalArgumentException(label + " inválido");
        List<Map<String, Object>> values = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) throw new IllegalArgumentException(label + " contém item inválido");
            values.add((Map<String, Object>) map);
        }
        return values;
    }

    private static String required(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException(key + " é obrigatório");
        return String.valueOf(value);
    }
}
