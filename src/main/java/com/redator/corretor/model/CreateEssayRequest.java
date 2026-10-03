package com.redator.corretor.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateEssayRequest(
        @NotNull Long temaId,
        @NotBlank String text
) {
}
