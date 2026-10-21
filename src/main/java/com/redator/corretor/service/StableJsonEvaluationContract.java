package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Experimental deterministic map ordering, with a distinct prompt/cache identity. */
@Component
@Primary
@ConditionalOnProperty(name = "app.llm.stable-json", havingValue = "true")
public final class StableJsonEvaluationContract extends LocalEvaluationContract {
    public static final String SERIALIZATION_VERSION = "stable-map-json-v1";
    private final ObjectMapper stableMapper = new ObjectMapper()
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Override
    public String json(Object value) {
        try { return stableMapper.writeValueAsString(value); }
        catch (Exception error) { throw new IllegalStateException("Falha ao serializar contrato", error); }
    }

    @Override
    public String promptHash() {
        return hash(super.promptHash() + "\n" + SERIALIZATION_VERSION);
    }
}
