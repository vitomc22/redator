package com.redator.corretor.service;

import com.redator.corretor.model.Tema;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class ThemeAlignmentService {

    public enum Alignment { PLENO, PARCIAL, FUGA }

    public record Assessment(Alignment alignment, Set<String> matchedTerms, String evidence) {
    }

    public Assessment assess(String essayText, Tema tema) {
        Set<String> themeTerms = Stream.of(tema.titulo(), tema.recorte(), tema.motivadores())
                .flatMap(value -> words(value).stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> essayTerms = words(essayText);
        Set<String> matched = essayTerms.stream().filter(themeTerms::contains).collect(Collectors.toCollection(LinkedHashSet::new));

        Alignment alignment;
        if (matched.isEmpty()) {
            alignment = Alignment.FUGA;
        } else if (matched.size() < 2) {
            alignment = Alignment.PARCIAL;
        } else {
            alignment = Alignment.PLENO;
        }
        return new Assessment(alignment, matched, "Alinhamento lexical com o tema: " + alignment.name().toLowerCase(Locale.ROOT)
                + "; termos coincidentes=" + matched);
    }

    private Set<String> words(String text) {
        if (text == null) {
            return Set.of();
        }
        String normalized = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
        return Arrays.stream(normalized.split("[^a-z0-9]+"))
                .filter(word -> word.length() >= 5)
                .filter(word -> !Set.of("como", "para", "sobre", "entre", "direito", "humano", "social", "sociedade").contains(word))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
