package com.redator.corretor.config;

import com.redator.corretor.service.RagService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Impede que o modo Ollama suba anunciando RAG quando o corpus não está utilizável. */
@Component
public class RagPrerequisiteInitializer implements ApplicationRunner {
    private final String provider;
    private final RagService rag;
    private final boolean requiredOnStartup;

    public RagPrerequisiteInitializer(@Value("${app.llm.provider:ollama}") String provider, RagService rag,
                                      @Value("${app.rag.require-on-startup:true}") boolean requiredOnStartup) {
        this.provider = provider;
        this.rag = rag;
        this.requiredOnStartup = requiredOnStartup;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!"ollama".equalsIgnoreCase(provider) || !requiredOnStartup) return;
        Map<String, Object> status = rag.status();
        long eligible = status.get("ragEligible") instanceof Number number ? number.longValue() : 0;
        if (!Boolean.TRUE.equals(status.get("enabled")) || !Boolean.TRUE.equals(status.get("available")) || eligible < 1) {
            throw new IllegalStateException("RAG obrigatório indisponível ou sem exemplos humanos elegíveis: " + status);
        }
    }
}
