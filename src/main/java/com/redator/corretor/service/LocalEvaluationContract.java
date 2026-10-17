package com.redator.corretor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.model.Tema;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Executable validation of the versioned schema, plus literal evidence checks. */
@Component
public class LocalEvaluationContract {
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final String system = resource("prompts/enem-local-v1-system.txt");
    private final String user = resource("prompts/enem-local-v1-user.txt");
    private final String rubric = resource("rubrics/enem-2026-v1.json");
    private final String schema = resource("schemas/enem-local-v1.json");
    public String promptHash() { return hash(system + "\n" + user); }
    public String rubricHash() { return hash(rubric); }
    public String schemaHash() { return hash(schema); }
    public JsonNode schema() { return parse(schema); }
    public JsonNode schemaForText(String text) {
        JsonNode dynamic = schema().deepCopy();
        var values = mapper.createArrayNode();
        evidenceCandidates(text).forEach(values::add);
        ((com.fasterxml.jackson.databind.node.ObjectNode) dynamic.path("properties").path("competences")
                .path("items").path("properties").path("evidence").path("items"))
                .set("enum", values);
        return dynamic;
    }
    public List<Map<String, String>> messages(String text, Tema theme) {
        return messages(text, theme, new RagService.Context(false, "disabled", List.of()));
    }
    public List<Map<String, String>> messages(String text, Tema theme, RagService.Context rag) {
        String calibration = rag.references().isEmpty()
                ? "Nenhum exemplo humano foi recuperado. Avalie somente pela rubrica."
                : "Exemplos humanos públicos servem apenas para calibrar a aplicação da rubrica. "
                  + "Não copie notas, frases ou justificativas; avalie integralmente a redação atual. Exemplos: "
                  + json(rag.promptExamples());
        return List.of(Map.of("role", "system", "content", system + "\nRubrica: " + parse(rubric).get("levels")),
                Map.of("role", "user", "content", user + "\n" + calibration + "\n" + json(Map.of(
                        "tema", theme.titulo(), "proposta", theme.recorte(), "motivadores", theme.motivadores(),
                        "redacao", text, "evidenceCandidates", evidenceCandidates(text)))));
    }

    /** Exact UTF-16 substrings offered to constrained decoding; never normalized or synthesized. */
    public List<String> evidenceCandidates(String text) {
        List<String> candidates = new ArrayList<>();
        for (String section : text.split("(?<=[.!?])\\s+|\\R+")) {
            int cursor = 0;
            while (cursor < section.length() && candidates.size() < 24) {
                while (cursor < section.length() && Character.isWhitespace(section.charAt(cursor))) cursor++;
                if (cursor >= section.length()) break;
                int end = Math.min(section.length(), cursor + 120);
                if (end < section.length()) {
                    int boundary = section.lastIndexOf(' ', end);
                    if (boundary > cursor) end = boundary;
                }
                String literal = section.substring(cursor, end);
                if (!literal.isBlank() && text.contains(literal) && !candidates.contains(literal)) candidates.add(literal);
                cursor = end;
            }
            if (candidates.size() >= 24) break;
        }
        if (candidates.isEmpty() && !text.isEmpty()) candidates.add(text.substring(0, Math.min(120, text.length())));
        return List.copyOf(candidates);
    }
    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalStateException("Falha ao serializar contrato", ex); }
    }
    public JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch (Exception ex) { throw invalid("Resposta não é JSON completo válido."); }
    }
    public Validated validate(String content, String text) {
        JsonNode root = parse(content);
        fields(root, Set.of("competences", "globalFlags", "limitations"));
        JsonNode items = root.get("competences");
        require(items.isArray() && items.size() == 5, "São necessárias cinco competências.");
        Set<String> flags = new LinkedHashSet<>();
        array(root.get("globalFlags"), 5);
        Set<String> allowedFlags = Set.of("THEME_ESCAPE", "INCOMPATIBLE_TEXT_TYPE", "CRITICAL_INSUFFICIENCY", "HUMAN_RIGHTS_CONCERN", "ANNULMENT_SUSPECTED");
        root.get("globalFlags").forEach(f -> {
            require(f.isTextual() && allowedFlags.contains(f.asText()) && flags.add(f.asText()), "Flag inválida ou repetida.");
        });
        List<String> limitations = new ArrayList<>();
        array(root.get("limitations"), 5);
        root.get("limitations").forEach(l -> limitations.add(string(l, 180)));
        limitations.add("Estimativa conservadora de contexto; sem tokenizador compatível no Java.");
        limitations.add("Linhas manuscritas e fatos externos não verificados; cópia depende de motivadores completos.");
        Map<String, CompetenceResult> results = new TreeMap<>();
        Map<String, List<Map<String, Object>>> locations = new TreeMap<>();
        for (JsonNode item : items) {
            fields(item, Set.of("id", "score", "justification", "evidence", "improvement", "evidenceKind"));
            String id = string(item.get("id"), 2);
            require(Set.of("C1","C2","C3","C4","C5").contains(id) && !results.containsKey(id), "Identificador de competência inválido ou repetido.");
            JsonNode score = item.get("score");
            require(score.isInt() && Set.of(0,40,80,120,160,200).contains(score.intValue()), "Nota fora da escala.");
            String justification = string(item.get("justification"), 180);
            String improvement = string(item.get("improvement"), 140);
            String kind = string(item.get("evidenceKind"), 7);
            require(Set.of("passage", "absence").contains(kind), "Tipo de evidência inválido.");
            array(item.get("evidence"), 2);
            List<String> evidence = new ArrayList<>();
            List<Map<String, Object>> offsets = new ArrayList<>();
            for (JsonNode quote : item.get("evidence")) {
                String literal = string(quote, 120);
                int start = text.indexOf(literal);
                if (start < 0) throw new EvaluationFailure(HttpStatus.BAD_GATEWAY, "INVALID_EVIDENCE", "Evidência não pertence ao texto; avaliação requer revisão.");
                evidence.add(literal);
                offsets.add(Map.of("text", literal, "start", start, "end", start + literal.length(),
                        "ambiguous", text.indexOf(literal, start + 1) >= 0, "offsetUnit", "UTF-16"));
            }
            require(kind.equals("absence") ? evidence.isEmpty() : !evidence.isEmpty(), "Evidência ausente ou incompatível com evidenceKind.");
            // Absence claims cannot be verified mechanically; a human must confirm them.
            if (kind.equals("absence")) flags.add("ABSENCE_REQUIRES_REVIEW");
            results.put(id, new CompetenceResult(id, score.intValue(), score.intValue(), evidence,
                    List.of(improvement), false, List.of(justification)));
            locations.put(id, offsets);
        }
        if (results.get("C2").nota() == 0) flags.add("ANNULMENT_SUSPECTED");
        if (results.get("C2").nota() == 40 && (results.get("C3").nota() > 40 || results.get("C5").nota() > 40)) {
            flags.add("RUBRIC_CONSISTENCY_REVIEW");
        }
        boolean review = !flags.isEmpty();
        results.replaceAll((id, c) -> new CompetenceResult(id, c.nota(), c.nivelSugerido(), c.evidencias(), c.problemas(), review, c.motivos()));
        return new Validated(results, review, List.copyOf(flags), limitations, locations);
    }
    private void fields(JsonNode node, Set<String> expected) {
        require(node != null && node.isObject(), "Objeto obrigatório.");
        Set<String> actual = new HashSet<>(); node.fieldNames().forEachRemaining(actual::add);
        require(actual.equals(expected), "Campos obrigatórios ausentes ou propriedades adicionais.");
    }
    private void array(JsonNode n, int max) { require(n != null && n.isArray() && n.size() <= max, "Lista inválida."); }
    private String string(JsonNode n, int max) {
        require(n != null && n.isTextual() && !n.asText().isBlank() && n.asText().codePointCount(0, n.asText().length()) <= max, "Texto vazio ou excede limite.");
        return n.asText();
    }
    private void require(boolean ok, String reason) { if (!ok) throw invalid(reason); }
    private EvaluationFailure invalid(String reason) { return new EvaluationFailure(HttpStatus.BAD_GATEWAY, "INVALID_RESPONSE", reason); }
    private static String resource(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) { return new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        catch (Exception ex) { throw new IllegalStateException("Recurso obrigatório indisponível: " + path, ex); }
    }
    public static String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public record Validated(Map<String, CompetenceResult> competences, boolean needsReview, List<String> reviewReasons,
                            List<String> limitations, Map<String, List<Map<String, Object>>> evidenceLocations) {
        public int sum() { return competences.values().stream().mapToInt(CompetenceResult::nota).sum(); }
    }
}
