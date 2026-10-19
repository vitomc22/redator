package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import com.redator.corretor.config.LocalApiTokenFilter;
import jakarta.servlet.http.Cookie;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class LocalApiTokenFilterTest {
    @Test
    void issuesStrictHttpOnlySessionCookieAndRequiresItForApiCalls() throws Exception {
        LocalApiTokenFilter filter = new LocalApiTokenFilter(true, "fixed-test-token");
        MockHttpServletRequest pageRequest = new MockHttpServletRequest("GET", "/");
        MockHttpServletResponse pageResponse = new MockHttpServletResponse();

        filter.doFilter(pageRequest, pageResponse, (request, response) -> { });

        String setCookie = pageResponse.getHeader("Set-Cookie");
        assertThat(setCookie).contains("REDATOR_LOCAL_SESSION=fixed-test-token", "HttpOnly", "SameSite=Strict", "Path=/");

        MockHttpServletRequest rejected = new MockHttpServletRequest("POST", "/api/essays");
        MockHttpServletResponse rejectedResponse = new MockHttpServletResponse();
        AtomicBoolean rejectedChain = new AtomicBoolean();
        filter.doFilter(rejected, rejectedResponse, (request, response) -> rejectedChain.set(true));
        assertThat(rejectedResponse.getStatus()).isEqualTo(403);
        assertThat(rejectedChain).isFalse();

        MockHttpServletRequest accepted = new MockHttpServletRequest("POST", "/api/essays");
        accepted.setCookies(new Cookie("REDATOR_LOCAL_SESSION", "fixed-test-token"));
        MockHttpServletResponse acceptedResponse = new MockHttpServletResponse();
        AtomicBoolean acceptedChain = new AtomicBoolean();
        filter.doFilter(accepted, acceptedResponse, (request, response) -> acceptedChain.set(true));
        assertThat(acceptedChain).isTrue();
    }

    @Test
    void disabledModeDoesNotIssueOrRequireSessionToken() throws Exception {
        LocalApiTokenFilter filter = new LocalApiTokenFilter(false, "fixed-test-token");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/runtime");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean chain = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> chain.set(true));

        assertThat(chain).isTrue();
        assertThat(response.getHeader("Set-Cookie")).isNull();
    }
}
