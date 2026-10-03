package com.redator.corretor.model;

public record EssayResponse(Long id, Long temaId, String text, String status, String createdAt) {
}
