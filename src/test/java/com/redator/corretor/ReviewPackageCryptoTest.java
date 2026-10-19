package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.redator.corretor.service.ReviewPackageCrypto;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReviewPackageCryptoTest {
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-19T12:00:00Z"), ZoneOffset.UTC);
    private final ReviewPackageCrypto crypto = new ReviewPackageCrypto(new ObjectMapper(), NOW);

    @Test
    void roundTripsVersionedRequestWithoutPuttingSecretInEnvelope() {
        var sealed = crypto.seal("REQUEST", "pedido-123", "abc123",
                Instant.parse("2026-10-20T12:00:00Z"), Map.of("essayText", "Texto privado", "theme", "Tema"));

        String envelopeText = new String(sealed.envelope(), StandardCharsets.UTF_8);
        assertThat(envelopeText).doesNotContain(sealed.secret(), "Texto privado");
        var opened = crypto.open(sealed.envelope(), sealed.secret(), "REQUEST", "abc123");
        assertThat(opened.requestId()).isEqualTo("pedido-123");
        assertThat(opened.payload()).containsEntry("essayText", "Texto privado");
    }

    @Test
    void rejectsTamperingWrongRevisionExpiryAndUnexpectedEnvelopeFields() throws Exception {
        var sealed = crypto.seal("RESPONSE", "pedido-456", "revision-hash",
                Instant.parse("2026-10-20T12:00:00Z"), Map.of("scores", Map.of("C1", 160)));
        byte[] tampered = sealed.envelope().clone();
        tampered[tampered.length - 3] ^= 1;
        assertThatThrownBy(() -> crypto.open(tampered, sealed.secret(), "RESPONSE", "revision-hash"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> crypto.open(sealed.envelope(), sealed.secret(), "RESPONSE", "different"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("revisão");

        var expired = crypto.seal("REQUEST", "pedido-expirado", "hash",
                Instant.parse("2026-10-19T11:59:59Z"), Map.of("essayText", "x"));
        assertThatThrownBy(() -> crypto.open(expired.envelope(), expired.secret(), "REQUEST", "hash"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("expirado");

        ObjectNode unknown = (ObjectNode) new ObjectMapper().readTree(sealed.envelope());
        unknown.put("execute", "never");
        assertThatThrownBy(() -> crypto.open(new ObjectMapper().writeValueAsBytes(unknown), sealed.secret(), "RESPONSE", "revision-hash"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("inesperado");
    }
}
