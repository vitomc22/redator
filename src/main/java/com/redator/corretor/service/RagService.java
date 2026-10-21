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
    public static final String LEGACY_POLICY = "lexical-v1";
    public static final String CALIBRATION_POLICY = "calibration-v2";
    private final Path database;
    private final boolean enabled;
    private final int maxExamples;
    private final int excerptChars;
    private final String policy;

    @Autowired
    public RagService(@Value("${app.rag.database:dataset/public-corpus.db}") String database,
                      @Value("${app.rag.enabled:true}") boolean enabled,
                      @Value("${app.rag.max-examples:3}") int maxExamples,
                      @Value("${app.rag.excerpt-chars:1200}") int excerptChars,
                      @Value("${app.rag.policy:lexical-v1}") String policy) {
        this(Path.of(database), enabled, maxExamples, excerptChars, policy);
    }

    public RagService(Path database, boolean enabled, int maxExamples, int excerptChars) {
        this(database, enabled, maxExamples, excerptChars, LEGACY_POLICY);
    }

    public RagService(Path database, boolean enabled, int maxExamples, int excerptChars, String policy) {
        if (maxExamples < 1 || maxExamples > 5 || excerptChars < 300 || excerptChars > 2400) {
            throw new IllegalArgumentException("Configuração RAG inválida");
        }
        if (!List.of(LEGACY_POLICY, CALIBRATION_POLICY).contains(policy)) {
            throw new IllegalArgumentException("Política RAG desconhecida: " + policy);
        }
        this.database = database.toAbsolutePath().normalize();
        this.enabled = enabled;
        this.maxExamples = maxExamples;
        this.excerptChars = excerptChars;
        this.policy = policy;
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
                boolean complete;
                if (CALIBRATION_POLICY.equals(policy)) {
                    complete = text.codePointCount(0, text.length()) <= excerptChars;
                    text = calibrationExcerpt(text, excerptChars);
                } else {
                    complete = text.length() <= excerptChars;
                    if (!complete) text = text.substring(0, excerptChars) + " […]";
                }
                references.add(new Reference(example.essayId(), example.essayHash(), example.source(), example.sourceId(),
                        example.licenseSpdx(), example.theme(), text, example.scores(), example.total(), example.relevance(), complete));
            }
            String fingerprint = corpus.fingerprint();
            return new Context(true, fingerprint, List.copyOf(references), policy);
        } catch (Exception error) {
            throw new IllegalStateException("Falha ao consultar corpus RAG", error);
        }
    }

    private static String calibrationExcerpt(String text, int limit) {
        if (text.codePointCount(0, text.length()) <= limit) return text;
        String omission = " […] ";
        int remaining = limit - omission.codePointCount(0, omission.length());
        int beginning = remaining * 2 / 3;
        int ending = remaining - beginning;
        return text.substring(0, text.offsetByCodePoints(0, beginning)) + omission
                + text.substring(text.offsetByCodePoints(text.length(), -ending));
    }

    public Map<String, Object> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("database", database.toString());
        status.put("available", Files.isRegularFile(database));
        status.put("maxExamples", maxExamples);
        status.put("policy", policy);
        status.put("excerptChars", excerptChars);
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

    public record Context(boolean enabled, String corpusFingerprint, List<Reference> references, String policy) {
        public Context(boolean enabled, String corpusFingerprint, List<Reference> references) {
            this(enabled, corpusFingerprint, references, enabled ? LEGACY_POLICY : "disabled");
        }

        public List<Map<String, Object>> promptExamples() {
            return references.stream().map(reference -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("referenceId", reference.id());
                item.put("theme", reference.theme());
                item.put("essayExcerpt", reference.essayExcerpt());
                item.put("humanScoresC1ToC5", reference.scores());
                item.put("humanTotal", reference.total());
                if (CALIBRATION_POLICY.equals(policy)) {
                    item.put("excerptCoverage", reference.complete() ? "complete" : "beginning-and-end");
                    item.put("humanScoreScope", "whole-essay-mean");
                }
                return item;
            }).toList();
        }
        public List<Map<String, Object>> auditExamples() {
            return references.stream().map(reference -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", reference.id()); item.put("hash", reference.hash());
                item.put("source", reference.source()); item.put("sourceId", reference.sourceId());
                item.put("license", reference.license()); item.put("relevance", reference.relevance());
                if (CALIBRATION_POLICY.equals(policy)) {
                    item.put("excerptHash", LocalEvaluationContract.hash(reference.essayExcerpt()));
                    item.put("excerptCoverage", reference.complete() ? "complete" : "beginning-and-end");
                }
                return item;
            }).toList();
        }
    }

    public record Reference(long id, String hash, String source, String sourceId, String license,
                            String theme, String essayExcerpt, List<Integer> scores, int total,
                            double relevance, boolean complete) { }
}
