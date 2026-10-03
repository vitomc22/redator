package com.redator.corretor.service;

public class VisionTranscriptionGateway {

    private final DocumentTranscriptionService.VisionTranscriptionProvider provider;

    public VisionTranscriptionGateway() {
        this((content, fileName) -> DocumentTranscriptionService.transcribeDocument(content, fileName));
    }

    public VisionTranscriptionGateway(DocumentTranscriptionService.VisionTranscriptionProvider provider) {
        this.provider = provider == null
                ? ((content, fileName) -> DocumentTranscriptionService.transcribeDocument(content, fileName))
                : provider;
    }

    public String transcribe(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            return "Arquivo vazio. Revisão humana obrigatória antes da avaliação.";
        }
        return provider.transcribe(content, fileName);
    }
}
