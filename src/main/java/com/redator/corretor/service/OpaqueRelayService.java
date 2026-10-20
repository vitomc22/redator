package com.redator.corretor.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OpaqueRelayService {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    @Autowired public OpaqueRelayService(JdbcTemplate jdbc) { this(jdbc, Clock.systemUTC()); }
    OpaqueRelayService(JdbcTemplate jdbc, Clock clock) { this.jdbc = jdbc; this.clock = clock; }

    @Transactional
    public Map<String, Object> create(String blobBase64, Integer ttlHours) {
        byte[] blob = decode(blobBase64);
        int ttl = ttlHours == null ? 72 : ttlHours;
        if (ttl < 1 || ttl > 168) throw new IllegalArgumentException("TTL deve ficar entre 1 e 168 horas");
        String professor = token(), student = token(), id = UUID.randomUUID().toString(), now = now();
        String expires = OffsetDateTime.ofInstant(clock.instant().plus(ttl, ChronoUnit.HOURS), ZoneOffset.UTC).toString();
        jdbc.update("""
                INSERT INTO opaque_relay_channel(id, professor_token_hash, student_token_hash, request_blob,
                    expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)
                """, id, hash(professor), hash(student), blob, expires, now);
        return Map.of("channelId", id, "professorToken", professor, "studentToken", student, "expiresAt", expires);
    }

    @Transactional
    public Map<String, Object> readRequest(String token) {
        Map<String, Object> row = active("professor_token_hash", token);
        if (row.get("request_read_at") != null) throw gone();
        jdbc.update("UPDATE opaque_relay_channel SET request_read_at=? WHERE id=? AND request_read_at IS NULL", now(), row.get("id"));
        return blob(row.get("request_blob"));
    }

    @Transactional
    public Map<String, Object> writeResponse(String token, String blobBase64) {
        Map<String, Object> row = active("professor_token_hash", token);
        if (row.get("request_read_at") == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "O pedido ainda não foi aberto");
        if (row.get("response_blob") != null) throw gone();
        byte[] response = decode(blobBase64);
        jdbc.update("UPDATE opaque_relay_channel SET response_blob=?, response_created_at=? WHERE id=? AND response_blob IS NULL",
                response, now(), row.get("id"));
        return Map.of("status", "RESPONSE_AVAILABLE");
    }

    @Transactional
    public Map<String, Object> readResponse(String token) {
        Map<String, Object> row = active("student_token_hash", token);
        if (row.get("response_blob") == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Resposta ainda não disponível");
        if (row.get("response_read_at") != null) throw gone();
        jdbc.update("UPDATE opaque_relay_channel SET response_read_at=? WHERE id=? AND response_read_at IS NULL", now(), row.get("id"));
        return blob(row.get("response_blob"));
    }

    @Transactional
    public Map<String, Object> revoke(String token) {
        int changed = jdbc.update("UPDATE opaque_relay_channel SET status='REVOKED' WHERE student_token_hash=? AND status='ACTIVE'", hash(token));
        if (changed != 1) throw gone();
        return Map.of("status", "REVOKED");
    }

    private Map<String, Object> active(String column, String token) {
        var rows = jdbc.queryForList("SELECT * FROM opaque_relay_channel WHERE " + column + "=?", hash(token));
        if (rows.isEmpty()) throw gone();
        Map<String, Object> row = rows.getFirst();
        if (!"ACTIVE".equals(row.get("status")) || String.valueOf(row.get("expires_at")).compareTo(now()) <= 0) throw gone();
        return row;
    }
    private byte[] decode(String value) {
        try { byte[] bytes = Base64.getDecoder().decode(value == null ? "" : value); if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException(); return bytes; }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Blob inválido ou maior que 2 MiB"); }
    }
    private Map<String, Object> blob(Object value) { return Map.of("blobBase64", Base64.getEncoder().encodeToString((byte[]) value)); }
    private String token() { byte[] bytes = new byte[32]; random.nextBytes(bytes); return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private String now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(); }
    private static ResponseStatusException gone() { return new ResponseStatusException(HttpStatus.GONE, "Canal expirado, revogado ou já consumido"); }
}
