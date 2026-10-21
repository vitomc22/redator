package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.model.CompetenceResult;
import com.redator.corretor.model.CreateEssayRequest;
import com.redator.corretor.model.EvaluationProfile;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.EssayService;
import com.redator.corretor.service.EvaluationCache;
import com.redator.corretor.service.LlmGateway;
import com.redator.corretor.service.LocalEvaluationContract;
import com.redator.corretor.service.OllamaLlmProvider;
import com.redator.corretor.service.StableJsonEvaluationContract;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(properties = {"app.llm.provider=fake", "app.llm.stable-json=true",
        "app.rag.enabled=false", "app.rag.require-on-startup=false"})
class StableJsonContractIntegrationTest {
    private static final String DATABASE = "jdbc:sqlite:./target/stable-json-" + java.util.UUID.randomUUID() + ".db";
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) { registry.add("spring.datasource.url", () -> DATABASE); }

    @Autowired EssayService essays;
    @Autowired LocalEvaluationContract contract;
    @Autowired LlmGateway stable;
    @Autowired OllamaLlmProvider ollama;
    @Autowired EvaluationCache cache;
    @Autowired JdbcTemplate jdbc;

    @Test
    void applicationPersistsTheSelectedContractIdentityWithoutPublishingFakeScoresAsReal() throws Exception {
        assertThat(contract).isExactlyInstanceOf(StableJsonEvaluationContract.class);
        var essay = essays.createEssay(new CreateEssayRequest(1L,
                "A educação pública requer investimento contínuo. Portanto, o Ministério deve financiar escolas e capacitar professores."));
        var evaluation = essays.evaluateEssay(essay.id(), "normal");
        assertThat(evaluation.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(evaluation.reviewReasons()).contains("FAKE_PROVIDER");
        String auditJson = jdbc.queryForObject("SELECT audit_json FROM llm_call WHERE run_id=?", String.class, evaluation.runId());
        var audit = new ObjectMapper().readTree(auditJson);
        assertThat(audit.path("promptHash").asText()).isEqualTo(contract.promptHash())
                .isNotEqualTo(new LocalEvaluationContract().promptHash());
    }

    @Test
    void persistedCachesCannotAliasBetweenLegacyAndStableMapContracts() {
        var legacy = new LlmGateway("fake", ollama, new LocalEvaluationContract(), cache);
        var theme = new Tema(987L, "Cache separado", "Educação e cidadania", "");
        String text = "A cidadania depende de educação inclusiva e políticas públicas.";
        Map<String, CompetenceResult> fixture = new LinkedHashMap<>();
        for (String code : List.of("C1", "C2", "C3", "C4", "C5"))
            fixture.put(code, new CompetenceResult(code, 120, 120, List.of(text), List.of(), false, List.of("Fixture de cache")));
        var before = legacy.evaluate(text, theme, 1, EvaluationProfile.NORMAL, fixture);
        var after = stable.evaluate(text, theme, 1, EvaluationProfile.NORMAL, fixture);
        assertThat(before.cacheHit()).isFalse(); assertThat(after.cacheHit()).isFalse();
        assertThat(after.audit().get("cacheKey")).isNotEqualTo(before.audit().get("cacheKey"));
        assertThat(legacy.evaluate(text, theme, 1, EvaluationProfile.NORMAL, fixture).cacheHit()).isTrue();
        assertThat(stable.evaluate(text, theme, 1, EvaluationProfile.NORMAL, fixture).cacheHit()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM evaluation_cache WHERE cache_key IN (?,?)", Integer.class,
                before.audit().get("cacheKey"), after.audit().get("cacheKey"))).isEqualTo(2);
    }
}
