package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
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
        properties = {"spring.datasource.url=jdbc:sqlite:./target/review-exchange-test.db", "app.llm.provider=fake",
                "app.local-api-token.enabled=false"})
class ReviewExchangeIntegrationTest {
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void cleanExchangeData() {
        jdbc.update("DELETE FROM human_evaluation");
        jdbc.update("DELETE FROM review_package");
        jdbc.update("DELETE FROM human_review_request");
        jdbc.update("DELETE FROM essay");
    }

    @Test
    void exchangesEncryptedCorrectionAndRejectsReplay() {
        long essayId = createEssay("student-exchange");
        HttpHeaders student = user("student-exchange");
        assertThat(exchange("/api/essays/" + essayId + "/human-review-requests", HttpMethod.POST,
                Map.of(), student).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> exported = exchange("/api/essays/" + essayId + "/review-package",
                HttpMethod.POST, Map.of("validityDays", 7), student);
        assertThat(exported.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String packageBase64 = String.valueOf(exported.getBody().get("packageBase64"));
        String secret = String.valueOf(exported.getBody().get("secret"));
        assertThat(packageBase64).doesNotContain("A desinformação");

        ResponseEntity<Map> opened = rest.postForEntity("/api/review-packages/open",
                Map.of("packageBase64", packageBase64, "secret", secret), Map.class);
        assertThat(opened.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(opened.getBody()).containsEntry("essayText",
                "A desinformação exige educação midiática e transparência das plataformas digitais.");

        Map<String, Object> correction = correction(packageBase64, secret);
        ResponseEntity<Map> responsePackage = rest.postForEntity("/api/review-packages/respond", correction, Map.class);
        assertThat(responsePackage.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> importBody = Map.of("packageBase64", responsePackage.getBody().get("packageBase64"),
                "secret", secret);
        ResponseEntity<Map> imported = exchange("/api/review-packages/import", HttpMethod.POST, importBody, student);
        assertThat(imported.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(imported.getBody()).containsEntry("total", 880);
        assertThat(jdbc.queryForObject("SELECT status FROM review_package", String.class)).isEqualTo("IMPORTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM human_evaluation WHERE status='PUBLISHED'", Integer.class)).isEqualTo(1);

        ResponseEntity<Map> replay = exchange("/api/review-packages/import", HttpMethod.POST, importBody, student);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM human_evaluation WHERE status='PUBLISHED'", Integer.class)).isEqualTo(1);
    }

    @Test
    void rejectsWrongSecretAndCrossProfileImportWithoutPublishing() {
        long essayId = createEssay("owner-profile");
        HttpHeaders owner = user("owner-profile");
        exchange("/api/essays/" + essayId + "/human-review-requests", HttpMethod.POST, Map.of(), owner);
        Map exported = exchange("/api/essays/" + essayId + "/review-package", HttpMethod.POST,
                Map.of(), owner).getBody();
        String packageBase64 = String.valueOf(exported.get("packageBase64"));
        String secret = String.valueOf(exported.get("secret"));
        Map response = rest.postForEntity("/api/review-packages/respond", correction(packageBase64, secret), Map.class).getBody();

        ResponseEntity<Map> wrongProfile = exchange("/api/review-packages/import", HttpMethod.POST,
                Map.of("packageBase64", response.get("packageBase64"), "secret", secret), user("someone-else"));
        assertThat(wrongProfile.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ResponseEntity<Map> wrongSecret = exchange("/api/review-packages/import", HttpMethod.POST,
                Map.of("packageBase64", response.get("packageBase64"),
                        "secret", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"), owner);
        assertThat(wrongSecret.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM human_evaluation WHERE status='PUBLISHED'", Integer.class)).isZero();
    }

    @Test
    void cancellingRevokesExportAndAllowsANewRequest() {
        long essayId = createEssay("cancel-owner");
        HttpHeaders owner = user("cancel-owner");
        exchange("/api/essays/" + essayId + "/human-review-requests", HttpMethod.POST, Map.of(), owner);
        Map first = exchange("/api/essays/" + essayId + "/review-package", HttpMethod.POST, Map.of(), owner).getBody();

        ResponseEntity<Map> cancelled = exchange("/api/essays/" + essayId + "/human-review-request/cancel",
                HttpMethod.POST, Map.of(), owner);
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");
        assertThat(jdbc.queryForObject("SELECT status FROM review_package", String.class)).isEqualTo("REVOKED");

        String oldBase64 = String.valueOf(first.get("packageBase64"));
        String oldSecret = String.valueOf(first.get("secret"));
        Map oldResponse = rest.postForEntity("/api/review-packages/respond",
                correction(oldBase64, oldSecret), Map.class).getBody();
        ResponseEntity<Map> revokedImport = exchange("/api/review-packages/import", HttpMethod.POST,
                Map.of("packageBase64", oldResponse.get("packageBase64"), "secret", oldSecret), owner);
        assertThat(revokedImport.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        ResponseEntity<Map> requestedAgain = exchange("/api/essays/" + essayId + "/human-review-requests",
                HttpMethod.POST, Map.of(), owner);
        assertThat(requestedAgain.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(((Number) requestedAgain.getBody().get("id")).longValue())
                .isNotEqualTo(((Number) cancelled.getBody().get("id")).longValue());

        ResponseEntity<Map> oldRequestStillDecryptsButCannotBeImported = rest.postForEntity("/api/review-packages/open",
                Map.of("packageBase64", oldBase64, "secret", oldSecret), Map.class);
        assertThat(oldRequestStillDecryptsButCannotBeImported.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    private Map<String, Object> correction(String packageBase64, String secret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("packageBase64", packageBase64); body.put("secret", secret);
        body.put("reviewer", "Professora Helena");
        body.put("scores", Map.of("C1", 160, "C2", 200, "C3", 160, "C4", 160, "C5", 200));
        body.put("comments", Map.of("C3", "Aprofunde a causalidade."));
        body.put("mainGuidance", "Integre melhor o repertório ao argumento.");
        return body;
    }

    private long createEssay(String profile) {
        Map<String, Object> body = Map.of("temaId", 1,
                "text", "A desinformação exige educação midiática e transparência das plataformas digitais.",
                "title", "Teste", "sourceType", "text");
        ResponseEntity<Map> response = exchange("/api/attempts", HttpMethod.POST, body, user(profile));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return ((Number) ((Map<?, ?>) response.getBody().get("essay")).get("id")).longValue();
    }

    private ResponseEntity<Map> exchange(String path, HttpMethod method, Object body, HttpHeaders headers) {
        return rest.exchange(path, method, new HttpEntity<>(body, headers), Map.class);
    }
    private static HttpHeaders user(String name) {
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Local-User", name); return headers;
    }
}
