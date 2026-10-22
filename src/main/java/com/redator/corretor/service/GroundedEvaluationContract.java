package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redator.corretor.model.Tema;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Candidate contract: whole-text evidence coverage and explicit rubric distinctions. */
@Component
@Primary
@ConditionalOnProperty(name = "app.llm.contract", havingValue = "grounded-v2")
public final class GroundedEvaluationContract extends LocalEvaluationContract {
    public static final String VERSION = "grounded-balanced-evidence-map-json-v2";
    private static final String GUIDANCE = """
            Aplicação dos níveis: escolha o nível demonstrado pelo texto, sem completar lacunas com suposições.
            C1: não invente desvios; diferencie poucos desvios de domínio excepcional.
            C2: citar autor, lei ou obra não garante repertório produtivo. Para 200, explique como o repertório
            pertinente sustenta um argumento; uma referência ornamental ou genérica não demonstra esse nível.
            C3: diferencie enumeração de ideias de relações explicadas e desenvolvidas em defesa da tese.
            C4: avalie a articulação efetiva, não a mera presença de conectivos.
            C5: leia a conclusão. Distinga constatação, desejo genérico e intervenção concreta.
            Para 200, localize ação, agente responsável, meio de execução, efeito/finalidade e detalhamento adicional,
            articulados à discussão. Não invente agente, meio ou detalhamento que o estudante não escreveu.
            Justifique a distinção entre níveis próximos com evidência literal da própria redação.
            """;
    private final ObjectMapper stable = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override public String promptHash() { return hash(super.promptHash() + "\n" + VERSION + "\n" + GUIDANCE); }
    @Override public String json(Object value) {
        try { return stable.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Falha ao serializar contrato", error); }
    }
    @Override public List<Map<String, String>> messages(String text, Tema theme, RagService.Context rag) {
        var messages = new ArrayList<>(super.messages(text, theme, rag));
        var system = new LinkedHashMap<>(messages.getFirst());
        system.put("content", system.get("content") + "\n" + GUIDANCE);
        messages.set(0, system);
        return List.copyOf(messages);
    }

    @Override public List<String> evidenceCandidates(String text) {
        var unique = new LinkedHashSet<String>();
        for (String section : text.split("(?<=[.!?])\\s+|\\R+")) {
            for (int cursor = 0; cursor < section.length();) {
                while (cursor < section.length() && Character.isWhitespace(section.charAt(cursor))) cursor++;
                if (cursor >= section.length()) break;
                int end = section.offsetByCodePoints(cursor, Math.min(120, section.codePointCount(cursor, section.length())));
                if (end < section.length()) {
                    int boundary = section.lastIndexOf(' ', end);
                    if (boundary > cursor) end = boundary;
                }
                unique.add(section.substring(cursor, end));
                cursor = end;
            }
        }
        var all = new ArrayList<>(unique);
        if (all.size() <= 24) return List.copyOf(all);
        // Reserve both ends and spread the remaining slots across the development.
        var selected = new ArrayList<>(all.subList(0, 8));
        int middle = all.size() - 16;
        for (int i = 0; i < 8; i++) selected.add(all.get(8 + (int) Math.round(i * (middle - 1) / 7.0)));
        selected.addAll(all.subList(all.size() - 8, all.size()));
        return List.copyOf(selected);
    }
}
