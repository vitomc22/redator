package com.redator.corretor.service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UploadValidationService {

    static final long MAX_FILE_SIZE = 8L * 1024 * 1024;

    public void validate(byte[] content, String fileName) {
        if (content == null || content.length == 0) {
            throw badRequest("arquivo vazio");
        }
        if (content.length > MAX_FILE_SIZE) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "arquivo excede o limite de 8 MB");
        }

        String normalizedName = fileName == null ? "" : fileName.trim().toLowerCase(Locale.ROOT);
        if (normalizedName.endsWith(".pdf")) {
            if (!hasPrefix(content, new byte[] {'%', 'P', 'D', 'F'})) {
                throw badRequest("arquivo PDF invalido");
            }
            return;
        }
        if (normalizedName.endsWith(".png")) {
            validateImage(content, new byte[] {(byte) 0x89, 'P', 'N', 'G'} , "PNG");
            return;
        }
        if (normalizedName.endsWith(".jpg") || normalizedName.endsWith(".jpeg")) {
            if (!hasPrefix(content, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
                throw badRequest("arquivo JPG invalido");
            }
            validateDecodableImage(content, "JPG");
            return;
        }

        throw badRequest("formato de arquivo nao suportado; use PDF, PNG ou JPG");
    }

    private void validateImage(byte[] content, byte[] signature, String type) {
        if (!hasPrefix(content, signature)) {
            throw badRequest("arquivo " + type + " invalido");
        }
        validateDecodableImage(content, type);
    }

    private void validateDecodableImage(byte[] content, String type) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw badRequest("arquivo " + type + " nao pode ser decodificado");
            }
        } catch (IOException ex) {
            throw badRequest("arquivo " + type + " nao pode ser decodificado");
        }
    }

    private boolean hasPrefix(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if (content[i] != signature[i]) {
                return false;
            }
        }
        return true;
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
