package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.service.EvaluationCache;
import com.redator.corretor.service.LlmGateway;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PersistentEvaluationCacheTest {
    @Test
    void survivesServiceRecreationAndEvictsOldestEntry(@TempDir Path temp) {
        JdbcTemplate jdbc = jdbc(temp);
        createTable(jdbc);
        EvaluationCache first = new EvaluationCache(jdbc, new ObjectMapper(), 2, 30);
        first.put("one", output(40));
        first.put("two", output(80));

        EvaluationCache recreated = new EvaluationCache(jdbc, new ObjectMapper(), 2, 30);
        assertThat(recreated.get("one")).get().extracting(LlmGateway.EvaluationOutput::cacheHit)
                .isEqualTo(true);
        recreated.put("three", output(120));

        assertThat(recreated.size()).isEqualTo(2);
        assertThat(recreated.get("two")).isEmpty();
        assertThat(recreated.get("one")).isPresent();
        assertThat(recreated.get("three")).isPresent();
    }

    private static LlmGateway.EvaluationOutput output(int score) {
        Map<String, CompetenceResult> values = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5"))
            values.put(code, new CompetenceResult(code, score, score, List.of("trecho"),
                    List.of("melhoria"), false, List.of("motivo")));
        return new LlmGateway.EvaluationOutput(values, false, List.of(), List.of(),
                Map.of("provider", "fixture"), false);
    }

    private static JdbcTemplate jdbc(Path temp) {
        DataSource source = new DriverManagerDataSource("jdbc:sqlite:" + temp.resolve("cache.db"));
        return new JdbcTemplate(source);
    }

    private static void createTable(JdbcTemplate jdbc) {
        jdbc.execute("""
                CREATE TABLE evaluation_cache(cache_key TEXT PRIMARY KEY, output_json TEXT NOT NULL,
                    created_at TEXT NOT NULL, last_accessed_at TEXT NOT NULL, expires_at TEXT NOT NULL)
                """);
    }
}
