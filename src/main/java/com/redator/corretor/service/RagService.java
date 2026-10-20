package com.redator.corretor.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Recuperação obrigatoriamente rastreável de exemplos humanos públicos. */
@Component
public class RagService {
    private final Path database;
    private final boolean enabled;
    private final int maxExamples;
    private final int excerptChars;

    @Autowired
    public RagService(@Value("${app.rag.database:dataset/public-corpus.db}") String database,
                      @Value("${app.rag.enabled:true}") boolean enabled,
                      @Value("${app.rag.max-examples:3}") int maxExamples,
                      @Value("${app.rag.excerpt-chars:1200}") int excerptChars) {
        this(Path.of(database), enabled, maxExamples, excerptChars);
    }

    public RagService(Path database, boolean enabled, int maxExamples, int excerptChars) {
        if (maxExamples < 1 || maxExamples > 5 || excerptChars < 300 || excerptChars > 2400) {
            throw new IllegalArgumentException("Configuração RAG inválida");
        }
        this.database = database.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.maxExamples = maxExamples;
        this.excerptChars = excerptChars;
    }

    public Context retrieve(String essay, String theme) {
        if (!enabled) return new Context(false, "disabled", List.of());
        if (!Files.isRegularFile(database)) {
            throw new IllegalStateException("Corpus RAG obrigatório não encontrado: " + database);
        }
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            String query = theme + " " + essay.substring(0, Math.min(700, essay.length()));
            List<Reference> references = new ArrayList<>();
            for (PublicHumanCorpus.Example example : corpus.retrieve(query, theme, maxExamples,
                    List.of(PublicHumanCorpus.hashText(essay)))) {
                String text = example.essayText();
                if (text.length() > excerptChars) text = text.substring(0, excerptChars) + " […]";
                references.add(new Reference(example.essayId(), example.essayHash(), example.source(), example.sourceId(),
                        example.licenseSpdx(), example.theme(), text, example.scores(), example.total(), example.relevance()));
            }
            String fingerprint = corpus.fingerprint();
            return new Context(true, fingerprint, List.copyOf(references));
        } catch (Exception error) {
            throw new IllegalStateException("Falha ao consultar corpus RAG", error);
        }
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("database", database.toString());
        status.put("available", Files.isRegularFile(database));
        status.put("maxExamples", maxExamples);
        if (enabled && Files.isRegularFile(database)) {
            try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
                status.putAll(corpus.statistics());
                status.put("fingerprint", corpus.fingerprint());
            } catch (Exception error) {
                status.put("available", false);
                status.put("error", "CORPUS_UNAVAILABLE");
            }
        }
        return status;
    }

    public record Context(boolean enabled, String corpusFingerprint, List<Reference> references) {
        public List<Map<String, Object>> promptExamples() {
            return references.stream().map(reference -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("referenceId", reference.id());
                item.put("theme", reference.theme());
                item.put("essayExcerpt", reference.essayExcerpt());
                item.put("humanScoresC1ToC5", reference.scores());
                item.put("humanTotal", reference.total());
                return item;
            }).toList();
        }
        public List<Map<String, Object>> auditExamples() {
            return references.stream().map(reference -> Map.<String, Object>of(
                    "id", reference.id(), "hash", reference.hash(), "source", reference.source(),
                    "sourceId", reference.sourceId(), "license", reference.license(),
                    "relevance", reference.relevance())).toList();
        }
    }

    public record Reference(long id, String hash, String source, String sourceId, String license,
                            String theme, String essayExcerpt, List<Integer> scores, int total,
                            double relevance) { }
}
