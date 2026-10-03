package com.redator.corretor.model;

import java.util.Locale;

public enum EvaluationProfile {
    CHEAP,
    NORMAL,
    PREMIUM;

    public static EvaluationProfile from(String profile) {
        if (profile == null || profile.isBlank()) {
            return CHEAP;
        }

        String normalized = profile.trim().toUpperCase(Locale.ROOT);
        for (EvaluationProfile value : values()) {
            if (value.name().equals(normalized)) {
                return value;
            }
        }

        throw new IllegalArgumentException("perfil de avaliacao invalido: " + profile + ". Opcoes: cheap, normal, premium");
    }
}
