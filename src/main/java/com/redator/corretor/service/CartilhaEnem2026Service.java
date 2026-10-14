package com.redator.corretor.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class CartilhaEnem2026Service {

    private static final String RESOURCE = "cartilha_enem_2026.pdf";
    private static final Map<String, String> REQUIRED_RULES = Map.of(
            "competencia_v_direitos_humanos", "Propostas que desrespeitem os direitos humanos receberão nota 0",
            "competencia_i", "Demonstrar domínio da modalidade escrita formal",
            "competencia_v", "Elaborar proposta de intervenção para o problema abordado"
    );
    private static final Pattern HUMAN_RIGHTS_VIOLATION = Pattern.compile(
            "(?:pena\\s+de\\s+morte|tortura|mutila(?:c|ç)ão|execu(?:c|ç)ão|justi(?:c|ç)a\\s+com\\s+as\\s+pr(?:o|ó)prias\\s+mãos|incitar\\s+(?:a\\s+)?viol(?:e|ê)ncia)",
            Pattern.CASE_INSENSITIVE
    );

    private final String sourceHash;
    private final String extractedText;

    public CartilhaEnem2026Service() {
        try {
            ClassPathResource resource = new ClassPathResource(RESOURCE);
            byte[] content;
            try (InputStream input = resource.getInputStream()) {
                content = input.readAllBytes();
            }
            this.sourceHash = sha256(content);
            try (PDDocument document = Loader.loadPDF(content)) {
                this.extractedText = new PDFTextStripper().getText(document);
            }
            validateRequiredRules(extractedText);
        } catch (IOException ex) {
            throw new IllegalStateException("Nao foi possivel carregar a cartilha ENEM 2026", ex);
        }
    }

    public String sourceVersion() {
        return "enem-2026-cartilha-sha256-" + sourceHash;
    }

    public String evidenceSource() {
        return "Fonte normativa: Cartilha do Participante ENEM 2026 (" + sourceVersion() + ")";
    }

    public boolean detectsHumanRightsViolation(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return HUMAN_RIGHTS_VIOLATION.matcher(normalized).find();
    }

    private void validateRequiredRules(String text) {
        String normalized = normalize(text);
        for (Map.Entry<String, String> rule : REQUIRED_RULES.entrySet()) {
            if (!normalized.contains(normalize(rule.getValue()))) {
                throw new IllegalStateException("Cartilha sem regra obrigatoria: " + rule.getKey());
            }
        }
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 indisponivel", ex);
        }
    }
}
