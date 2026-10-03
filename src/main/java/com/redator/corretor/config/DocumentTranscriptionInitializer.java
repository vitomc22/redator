package com.redator.corretor.config;

import com.redator.corretor.service.DocumentTranscriptionService;
import com.redator.corretor.service.RealVisionTranscriptionProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DocumentTranscriptionInitializer implements ApplicationRunner {

    @Override
    public void run(ApplicationArguments args) {
        DocumentTranscriptionService.setDefaultProvider(new RealVisionTranscriptionProvider());
    }
}
