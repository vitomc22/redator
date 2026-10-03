package com.redator.corretor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ResponseSchemaValidator {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void validate(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("schema invalido: resposta vazia do modelo");
        }

        try {
            JsonNode node = objectMapper.readTree(json);
            if (!node.isObject()) {
                throw new IllegalArgumentException("schema invalido: resposta do modelo deve ser um objeto JSON");
            }

            if (!node.has("competencia") || !node.get("competencia").isTextual()) {
                throw new IllegalArgumentException("schema invalido: campo competencia deve ser texto");
            }

            if (!node.has("score") || !node.get("score").isInt() || node.get("score").intValue() < 0 || node.get("score").intValue() > 200) {
                throw new IllegalArgumentException("schema invalido: campo score deve ser inteiro entre 0 e 200");
            }

            if (!node.has("evidence") || !node.get("evidence").isArray()) {
                throw new IllegalArgumentException("schema invalido: campo evidence deve ser um array");
            }

            if (!node.has("problems") || !node.get("problems").isArray()) {
                throw new IllegalArgumentException("schema invalido: campo problems deve ser um array");
            }

            if (!node.has("needsReview") || !node.get("needsReview").isBoolean()) {
                throw new IllegalArgumentException("schema invalido: campo needsReview deve ser booleano");
            }

            for (JsonNode evidenceItem : node.get("evidence")) {
                if (!evidenceItem.isTextual()) {
                    throw new IllegalArgumentException("schema invalido: cada item de evidence deve ser texto");
                }
            }

            for (JsonNode problemItem : node.get("problems")) {
                if (!problemItem.isTextual()) {
                    throw new IllegalArgumentException("schema invalido: cada item de problems deve ser texto");
                }
            }
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("schema invalido: resposta do modelo nao e um JSON valido", ex);
        }
    }
}
