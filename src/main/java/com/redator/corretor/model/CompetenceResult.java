package com.redator.corretor.model;

import java.util.List;

public record CompetenceResult(
        String competencia,
        int nota,
        int nivelSugerido,
        List<String> evidencias,
        List<String> problemas,
        boolean needsReview,
        List<String> motivos
) {
}
