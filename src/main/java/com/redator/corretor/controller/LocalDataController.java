package com.redator.corretor.controller;

import com.redator.corretor.service.LocalBackupService;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/local-data")
public class LocalDataController {
    private final LocalBackupService backups;

    public LocalDataController(LocalBackupService backups) { this.backups = backups; }

    @GetMapping(value = "/backup", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<FileSystemResource> backup() {
        Path snapshot = backups.createBackup();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(snapshot.getFileName().toString()).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentLength(snapshot.toFile().length())
                .body(new FileSystemResource(snapshot));
    }

    @PostMapping(value = "/restore", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> restore(@RequestPart("file") MultipartFile file) {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Backup vazio");
        try {
            Path staged = backups.stageRestore(file.getInputStream(), file.getSize());
            return ResponseEntity.accepted().body(Map.of(
                    "status", "STAGED",
                    "restartRequired", true,
                    "file", staged.getFileName().toString()));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.getMessage());
        } catch (IOException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Não foi possível ler o backup");
        }
    }
}
