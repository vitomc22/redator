package com.redator.corretor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class EvaluationCache {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final int maxEntries;
    private final int ttlDays;
    private final Clock clock;

    @Autowired
    public EvaluationCache(JdbcTemplate jdbc, ObjectMapper mapper,
            @Value("${app.llm.cache.max-entries:250}") int maxEntries,
            @Value("${app.llm.cache.ttl-days:30}") int ttlDays) {
        this(jdbc, mapper, maxEntries, ttlDays, Clock.systemUTC());
    }

    EvaluationCache(JdbcTemplate jdbc, ObjectMapper mapper, int maxEntries, int ttlDays, Clock clock) {
        if (maxEntries < 1 || ttlDays < 1) throw new IllegalArgumentException("Limites do cache devem ser positivos");
        this.jdbc = jdbc; this.mapper = mapper; this.maxEntries = maxEntries; this.ttlDays = ttlDays; this.clock = clock;
    }

    @Transactional
    public Optional<LlmGateway.EvaluationOutput> get(String key) {
        String now = now();
        jdbc.update("DELETE FROM evaluation_cache WHERE expires_at<=?", now);
        var rows = jdbc.queryForList("SELECT output_json FROM evaluation_cache WHERE cache_key=?", key);
        if (rows.isEmpty()) return Optional.empty();
        try {
            var output = mapper.readValue(String.valueOf(rows.getFirst().get("output_json")),
                    LlmGateway.EvaluationOutput.class).withCacheHit(true);
            jdbc.update("UPDATE evaluation_cache SET last_accessed_at=? WHERE cache_key=?", now, key);
            return Optional.of(output);
        } catch (Exception ex) {
            jdbc.update("DELETE FROM evaluation_cache WHERE cache_key=?", key);
            return Optional.empty();
        }
    }

    @Transactional
    public void put(String key, LlmGateway.EvaluationOutput output) {
        String now = now();
        String expires = OffsetDateTime.ofInstant(clock.instant().plus(ttlDays, ChronoUnit.DAYS), ZoneOffset.UTC).toString();
        try {
            jdbc.update("""
                    INSERT INTO evaluation_cache(cache_key, output_json, created_at, last_accessed_at, expires_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT(cache_key) DO UPDATE SET output_json=excluded.output_json,
                        last_accessed_at=excluded.last_accessed_at, expires_at=excluded.expires_at
                    """, key, mapper.writeValueAsString(output.withCacheHit(false)), now, now, expires);
        } catch (Exception ex) {
            throw new IllegalStateException("Não foi possível persistir o cache de avaliação", ex);
        }
        jdbc.update("DELETE FROM evaluation_cache WHERE expires_at<=?", now);
        jdbc.update("""
                DELETE FROM evaluation_cache WHERE cache_key IN (
                    SELECT cache_key FROM evaluation_cache ORDER BY last_accessed_at DESC, rowid DESC LIMIT -1 OFFSET ?
                )
                """, maxEntries);
    }

    public int size() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM evaluation_cache WHERE expires_at>?", Integer.class, now());
    }

    private String now() { return OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).toString(); }
}
