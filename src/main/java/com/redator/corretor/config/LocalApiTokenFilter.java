package com.redator.corretor.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/** CSRF-style process token protecting the loopback API from unrelated web pages. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocalApiTokenFilter extends OncePerRequestFilter {
    static final String COOKIE = "REDATOR_LOCAL_SESSION";
    static final String HEADER = "X-Redator-Local-Token";
    private final boolean enabled;
    private final String token;

    @Autowired
    public LocalApiTokenFilter(@Value("${app.local-api-token.enabled:true}") boolean enabled) {
        this(enabled, generateToken());
    }

    public LocalApiTokenFilter(boolean enabled, String token) {
        this.enabled = enabled;
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!enabled) {
            chain.doFilter(request, response);
            return;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            response.addHeader("Set-Cookie", COOKIE + "=" + token
                    + "; Path=/; HttpOnly; SameSite=Strict");
            chain.doFilter(request, response);
            return;
        }
        if (!valid(request)) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"status\":403,\"error\":\"Sessão local inválida\",\"code\":\"LOCAL_SESSION_REQUIRED\"}");
            return;
        }
        response.setHeader("Cache-Control", "no-store");
        chain.doFilter(request, response);
    }

    private boolean valid(HttpServletRequest request) {
        String candidate = request.getHeader(HEADER);
        if (candidate == null && request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (COOKIE.equals(cookie.getName())) candidate = cookie.getValue();
            }
        }
        return candidate != null && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8));
    }

    private static String generateToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
