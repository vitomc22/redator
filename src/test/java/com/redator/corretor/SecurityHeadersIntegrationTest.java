package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.llm.provider=fake", "app.local-api-token.enabled=false"})
class SecurityHeadersIntegrationTest {
    @Autowired TestRestTemplate rest;

    @Test
    void deniesCrossOriginEmbeddingAndUnsafeContentInference() {
        HttpHeaders request = new HttpHeaders(); request.setOrigin("https://evil.example");
        ResponseEntity<String> response = rest.exchange("/", HttpMethod.GET, new HttpEntity<>(request), String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
        assertThat(response.getHeaders().getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(response.getHeaders().getFirst("Content-Security-Policy"))
                .contains("default-src 'self'", "frame-ancestors 'none'", "connect-src 'self'");
    }
}
