package com.redator.corretor.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Identidade local sem autenticação, isolada por requisição. */
@Component
public class LocalUserContext {
    public static final String HEADER = "X-Local-User";
    private static final Pattern VALID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{1,63}");
    private final ThreadLocal<String> current = ThreadLocal.withInitial(() -> "local-default");

    public void set(String value) {
        String normalized = value == null || value.isBlank() ? "local-default" : value.strip();
        if (!VALID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Identificador local inválido");
        }
        current.set(normalized);
    }

    public String current() { return current.get(); }
    public void clear() { current.remove(); }
}
