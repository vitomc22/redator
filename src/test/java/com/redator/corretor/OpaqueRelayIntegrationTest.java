package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.datasource.url=jdbc:sqlite:./target/opaque-relay-test.db",
                "app.llm.provider=fake", "app.local-api-token.enabled=false"})
class OpaqueRelayIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach void clean() { jdbc.update("DELETE FROM opaque_relay_channel"); }

    @Test
    void transfersOpaqueRequestAndResponseOnceAndSupportsRevocation() {
        String encryptedRequest = Base64.getEncoder().encodeToString("ciphertext-request".getBytes());
        Map created = post("/api/relay/channels", Map.of("blobBase64", encryptedRequest, "ttlHours", 24), Map.class).getBody();
        String professorToken = String.valueOf(created.get("professorToken"));
        String studentToken = String.valueOf(created.get("studentToken"));
        assertThat(jdbc.queryForObject("SELECT request_blob FROM opaque_relay_channel", byte[].class))
                .isEqualTo("ciphertext-request".getBytes());
        assertThat(jdbc.queryForMap("SELECT * FROM opaque_relay_channel").toString())
                .doesNotContain(professorToken, studentToken);

        ResponseEntity<Map> opened = rest.getForEntity("/api/relay/professor/" + professorToken, Map.class);
        assertThat(opened.getBody()).containsEntry("blobBase64", encryptedRequest);
        assertThat(rest.getForEntity("/api/relay/professor/" + professorToken, Map.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);

        String encryptedResponse = Base64.getEncoder().encodeToString("ciphertext-response".getBytes());
        post("/api/relay/professor/" + professorToken + "/response",
                Map.of("blobBase64", encryptedResponse), Map.class);
        assertThat(rest.getForEntity("/api/relay/student/" + studentToken + "/response", Map.class).getBody())
                .containsEntry("blobBase64", encryptedResponse);
        assertThat(rest.getForEntity("/api/relay/student/" + studentToken + "/response", Map.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);

        Map another = post("/api/relay/channels", Map.of("blobBase64", encryptedRequest), Map.class).getBody();
        post("/api/relay/student/" + another.get("studentToken") + "/revoke", Map.of(), Map.class);
        assertThat(rest.getForEntity("/api/relay/professor/" + another.get("professorToken"), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.GONE);
    }

    private <T> ResponseEntity<T> post(String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Local-User", "relay-student");
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), type);
    }
}
