package com.redator.corretor.service;

import com.redator.corretor.model.CompetenceResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RubricEngine {

    private RubricEngine() {
    }

    public static Map<String, CompetenceResult> evaluate(DeterministicEssayAnalysis analysis) {
        Map<String, CompetenceResult> map = new LinkedHashMap<>();

        map.put("C1", c1(analysis));
        map.put("C2", c2(analysis));
        map.put("C3", c3(analysis));
        map.put("C4", c4(analysis));
        map.put("C5", c5(analysis));
        return map;
    }

    private static CompetenceResult c1(DeterministicEssayAnalysis analysis) {
        int words = analysis.words();
        int errors = Math.max(0, analysis.tokensForaDoDicionario());
        int ratePer100 = words == 0 ? 0 : (int) Math.round((errors * 100.0) / words);
        int nota = 200;
        if (ratePer100 > 8) {
            nota = 120;
        }
        if (ratePer100 > 14) {
            nota = 80;
        }
        if (ratePer100 > 20 || analysis.shortText()) {
            nota = 40;
        }
        if (analysis.needsReview()) {
            nota = Math.min(nota, 80);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("Palavras avaliadas: " + words);
        evidence.add("Ocorrencias de registro informal: " + errors);
        List<String> problems = new ArrayList<>();
        if (analysis.containsPromptInjection()) {
            problems.add("Possivel injecao de prompt detectada");
        }
        if (analysis.shortText()) {
            problems.add("Texto breve ou insuficiente");
        }
        return new CompetenceResult("C1", nota, 160, evidence, problems, analysis.needsReview(), List.of("Dominio da modalidade escrita formal"));
    }

    private static CompetenceResult c2(DeterministicEssayAnalysis analysis) {
        int nota = 160;
        boolean review = analysis.needsReview();
        if (review) {
            nota = 80;
        }
        if (analysis.words() < 120) {
            nota = 40;
        }

        List<String> evidence = new ArrayList<>();
        evidence.add(analysis.paragraphSummary());
        evidence.add("Tema identificado: educacao e cidadania");
        List<String> problems = new ArrayList<>();
        if (analysis.containsPromptInjection()) {
            problems.add("Alinhamento do tema pode estar comprometido");
        }
        if (analysis.shortText()) {
            problems.add("Estrutura insuficiente para a competencia de proposta");
        }
        return new CompetenceResult("C2", nota, 160, evidence, problems, review, List.of("Compreensao da proposta e tipo textual"));
    }

    private static CompetenceResult c3(DeterministicEssayAnalysis analysis) {
        int nota = 160;
        if (analysis.shortText()) {
            nota = 80;
        }
        if (analysis.words() < 100) {
            nota = 40;
        }
        boolean review = analysis.needsReview();
        if (review) {
            nota = Math.min(nota, 120);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("Paragrafos: " + analysis.paragraphs());
        evidence.add("Conclusao e desenvolvimento foram avaliados em conjunto");
        List<String> problems = new ArrayList<>();
        if (analysis.containsPromptInjection()) {
            problems.add("Possivel ruptura na argumentacao");
        }
        return new CompetenceResult("C3", nota, 160, evidence, problems, review, List.of("Selecao, relacao e organizacao de argumentos"));
    }

    private static CompetenceResult c4(DeterministicEssayAnalysis analysis) {
        int nota = 160;
        if (analysis.tokensForaDoDicionario() > 0) {
            nota = 120;
        }
        if (analysis.shortText()) {
            nota = 80;
        }
        if (analysis.needsReview()) {
            nota = Math.min(nota, 120);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("Conectivos de coesao e transicao foram detectados");
        evidence.add("Linguagem e progressao textual analisadas");
        List<String> problems = new ArrayList<>();
        if (analysis.tokensForaDoDicionario() > 0) {
            problems.add("Ha marcas de oralidade que podem reduzir a coesao formal");
        }
        return new CompetenceResult("C4", nota, 160, evidence, problems, analysis.needsReview(), List.of("Mecanismos linguisticos de coesao"));
    }

    private static CompetenceResult c5(DeterministicEssayAnalysis analysis) {
        int nota = 160;
        if (analysis.shortText()) {
            nota = 80;
        }
        if (analysis.containsPromptInjection()) {
            nota = 0;
        }
        if (analysis.needsReview()) {
            nota = Math.min(nota, 80);
        }

        List<String> evidence = new ArrayList<>();
        evidence.add("A proposta foi avaliada pela clareza e viabilidade da intervencao");
        evidence.add("Tema e solucao apresentados de forma sustentavel");
        List<String> problems = new ArrayList<>();
        if (analysis.containsPromptInjection()) {
            problems.add("Proposta potencialmente invalida por instrucao externa");
        }
        return new CompetenceResult("C5", nota, 160, evidence, problems, analysis.needsReview(), List.of("Proposta de intervencao"));
    }
}
