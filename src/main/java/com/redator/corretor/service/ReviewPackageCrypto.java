package com.redator.corretor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

/** Versioned opaque package shared by file or an untrusted relay. */
@Service
public class ReviewPackageCrypto {
    private static final int VERSION = 1;
    private static final Set<String> FIELDS = Set.of("version", "type", "requestId", "revisionHash", "expiresAt", "nonce", "ciphertext");
    private final ObjectMapper mapper;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public ReviewPackageCrypto(ObjectMapper mapper) { this(mapper, Clock.systemUTC()); }

    public ReviewPackageCrypto(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    public Sealed seal(String type, String requestId, String revisionHash, Instant expiresAt,
                       Map<String, Object> payload) {
        require(type, "tipo"); require(requestId, "requestId"); require(revisionHash, "hash da revisão");
        if (expiresAt == null) throw new IllegalArgumentException("Expiração obrigatória");
        byte[] key = randomBytes(32);
        byte[] nonce = randomBytes(12);
        String nonceText = encode(nonce);
        Map<String, Object> metadata = metadata(type, requestId, revisionHash, expiresAt, nonceText);
        try {
            byte[] plaintext = mapper.writeValueAsBytes(payload);
            byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, key, nonce, aad(metadata), plaintext);
            ObjectNode envelope = mapper.valueToTree(metadata);
            envelope.put("ciphertext", encode(ciphertext));
            return new Sealed(mapper.writeValueAsBytes(envelope), encode(key));
        } catch (Exception exception) {
            throw new IllegalStateException("Não foi possível cifrar o pacote de revisão", exception);
        }
    }

    public Opened open(byte[] envelopeBytes, String secret, String expectedType, String expectedRevisionHash) {
        try {
            JsonNode parsed = mapper.readTree(envelopeBytes);
            if (!(parsed instanceof ObjectNode envelope)) throw invalid("Envelope inválido");
            Iterator<String> names = envelope.fieldNames();
            while (names.hasNext()) if (!FIELDS.contains(names.next())) throw invalid("Campo inesperado no envelope");
            if (envelope.size() != FIELDS.size() || envelope.path("version").asInt(-1) != VERSION) throw invalid("Versão ou campos inválidos");
            String type = text(envelope, "type"), requestId = text(envelope, "requestId");
            String revisionHash = text(envelope, "revisionHash"), expiresText = text(envelope, "expiresAt");
            String nonceText = text(envelope, "nonce"), ciphertextText = text(envelope, "ciphertext");
            if (!MessageDigest.isEqual(expectedType.getBytes(StandardCharsets.UTF_8), type.getBytes(StandardCharsets.UTF_8)))
                throw invalid("Tipo de pacote divergente");
            if (!MessageDigest.isEqual(expectedRevisionHash.getBytes(StandardCharsets.UTF_8), revisionHash.getBytes(StandardCharsets.UTF_8)))
                throw invalid("Hash da revisão divergente");
            Instant expiresAt = Instant.parse(expiresText);
            if (!expiresAt.isAfter(clock.instant())) throw invalid("Pacote expirado");
            Map<String, Object> metadata = metadata(type, requestId, revisionHash, expiresAt, nonceText);
            byte[] plaintext = crypt(Cipher.DECRYPT_MODE, decode(secret), decode(nonceText), aad(metadata), decode(ciphertextText));
            Map<String, Object> payload = mapper.readValue(plaintext, new TypeReference<>() { });
            return new Opened(requestId, revisionHash, expiresAt, payload);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("Pacote adulterado, segredo incorreto ou conteúdo inválido");
        }
    }

    private Map<String, Object> metadata(String type, String requestId, String revisionHash,
                                         Instant expiresAt, String nonce) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION); result.put("type", type); result.put("requestId", requestId);
        result.put("revisionHash", revisionHash); result.put("expiresAt", expiresAt.toString()); result.put("nonce", nonce);
        return result;
    }

    private byte[] aad(Map<String, Object> metadata) throws Exception { return mapper.writeValueAsBytes(metadata); }

    private static byte[] crypt(int mode, byte[] key, byte[] nonce, byte[] aad, byte[] value)
            throws GeneralSecurityException {
        if (key.length != 32 || nonce.length != 12) throw invalid("Segredo ou nonce inválido");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad);
        return cipher.doFinal(value);
    }

    private byte[] randomBytes(int size) { byte[] value = new byte[size]; random.nextBytes(value); return value; }
    private static String encode(byte[] value) { return Base64.getUrlEncoder().withoutPadding().encodeToString(value); }
    private static byte[] decode(String value) { return Base64.getUrlDecoder().decode(value); }
    private static String text(JsonNode node, String field) { String value = node.path(field).asText(); require(value, field); return value; }
    private static void require(String value, String field) { if (value == null || value.isBlank()) throw invalid(field + " obrigatório"); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }

    public record Sealed(byte[] envelope, String secret) { }
    public record Opened(String requestId, String revisionHash, Instant expiresAt, Map<String, Object> payload) { }
}
