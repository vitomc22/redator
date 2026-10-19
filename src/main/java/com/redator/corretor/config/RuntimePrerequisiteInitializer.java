package com.redator.corretor.config;

import com.redator.corretor.service.RuntimeComponentManager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Makes Ollama/Gemma a startup prerequisite in the normal local runtime. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RuntimePrerequisiteInitializer implements ApplicationRunner {
    private final String provider;
    private final boolean verifyOnStartup;
    private final RuntimeComponentManager manager;

    public RuntimePrerequisiteInitializer(@Value("${app.llm.provider:ollama}") String provider,
                                          @Value("${app.runtime.verify-on-startup:true}") boolean verifyOnStartup,
                                          RuntimeComponentManager manager) {
        this.provider = provider;
        this.verifyOnStartup = verifyOnStartup;
        this.manager = manager;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (verifyOnStartup && "ollama".equalsIgnoreCase(provider)) manager.ensureReady();
    }
}
