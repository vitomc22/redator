package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/** Experimental deterministic map ordering, with a distinct prompt/cache identity. */
@Component
@Primary
@ConditionalOnExpression("'${app.llm.stable-json:false}' == 'true' && '${app.llm.contract:legacy-v1}' != 'grounded-v2'")
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
