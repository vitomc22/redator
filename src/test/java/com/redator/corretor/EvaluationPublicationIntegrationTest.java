package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.service.EvaluationPublicationService;
import com.redator.corretor.service.LlmGateway;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:./target/publication-transaction-test.db",
        "app.llm.provider=fake"
})
class EvaluationPublicationIntegrationTest {
    @Autowired EvaluationPublicationService publication;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void removeFailureTrigger() {
        jdbc.execute("DROP TRIGGER IF EXISTS fail_second_competence");
    }

    @Test
    void rollsBackAuditRunAndCompetencesWhenAnyCompetenceCannotBePublished() {
        long essayId = insertEssay();
        long runId = insertRunningRun(essayId);
        jdbc.execute("""
                CREATE TRIGGER fail_second_competence BEFORE INSERT ON competence_result
                WHEN NEW.competencia='C2' BEGIN SELECT RAISE(ABORT, 'forced rollback'); END
                """);
        Map<String, CompetenceResult> scores = new LinkedHashMap<>();
        scores.put("C1", score("C1"));
        scores.put("C2", score("C2"));
        LlmGateway.EvaluationOutput output = new LlmGateway.EvaluationOutput(scores, false, List.of(), List.of(),
                Map.of("provider", "fake", "model", "transaction-test", "wallMs", 1), false);

        assertThatThrownBy(() -> publication.publish(runId, "revision", OffsetDateTime.now().toString(), output))
                .isInstanceOf(DataAccessException.class);

        assertThat(jdbc.queryForObject("SELECT status FROM run WHERE id=?", String.class, runId))
                .isEqualTo("RUNNING");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM llm_call WHERE run_id=?", Integer.class, runId))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM competence_result WHERE run_id=?", Integer.class, runId))
                .isZero();
    }

    private long insertEssay() {
        jdbc.update("""
                INSERT INTO essay (tema_id,text,status,created_at,student_id,source_type,attempt_number,frozen)
                VALUES (1,'texto','READY',?,'transaction-test','text',1,0)
                """, OffsetDateTime.now().toString());
        return jdbc.queryForObject("SELECT MAX(id) FROM essay", Long.class);
    }

    private long insertRunningRun(long essayId) {
        jdbc.update("INSERT INTO run (essay_id,profile,total,status,created_at) VALUES (?,'normal',0,'RUNNING',?)",
                essayId, OffsetDateTime.now().toString());
        return jdbc.queryForObject("SELECT MAX(id) FROM run", Long.class);
    }

    private static CompetenceResult score(String competence) {
        return new CompetenceResult(competence, 160, 160, List.of("evidência"), List.of(), false, List.of("motivo"));
    }
}
