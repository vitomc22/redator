package com.redator.corretor.config;

import com.redator.corretor.service.LocalUserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class LocalUserFilter extends OncePerRequestFilter {
    private final LocalUserContext users;

    public LocalUserFilter(LocalUserContext users) { this.users = users; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            users.set(request.getHeader(LocalUserContext.HEADER));
            chain.doFilter(request, response);
        } catch (IllegalArgumentException error) {
            response.sendError(HttpStatus.BAD_REQUEST.value(), error.getMessage());
        } finally {
            users.clear();
        }
    }
}
