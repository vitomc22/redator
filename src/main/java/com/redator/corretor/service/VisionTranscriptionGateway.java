package com.redator.corretor.service;

public class VisionTranscriptionGateway {

    private final DocumentTranscriptionService.VisionTranscriptionProvider provider;

    public VisionTranscriptionGateway() {
        this(null);
    }

    public VisionTranscriptionGateway(DocumentTranscriptionService.VisionTranscriptionProvider provider) {
        this.provider = provider;
    }

    public String transcribe(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            return "Arquivo vazio. Revisão humana obrigatória antes da avaliação.";
        }
        if (provider != null) {
            return provider.transcribe(content, fileName);
        }
        return DocumentTranscriptionService.transcribeDocument(content, fileName);
    }
}
