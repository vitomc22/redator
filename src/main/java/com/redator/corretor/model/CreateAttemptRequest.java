package com.redator.corretor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateAttemptRequest(
        @NotNull Long temaId,
        @NotBlank String text,
        String title,
        Long parentEssayId,
        String sourceType
) {
}
