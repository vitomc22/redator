package com.redator.corretor.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.CorretorEnemApplication;
import com.redator.corretor.service.EssayService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

public class BenchmarkDatasetCli {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            return;
        }

        String datasetPath = args[0];
        Long temaId = Long.parseLong(args[1]);
        String profile = args.length > 2 ? args[2] : "cheap";

        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CorretorEnemApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {
            EssayService essayService = context.getBean(EssayService.class);
            List<String> texts = loadTexts(Path.of(datasetPath));
            Map<String, Object> summary = essayService.runBenchmark(temaId, texts, profile);
            System.out.println(new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(summary));
        }
    }

    private static void printUsage() {
        System.out.println("Uso: BenchmarkDatasetCli <caminho-do-dataset.json> <temaId> [perfil]");
        System.out.println("Exemplo: BenchmarkDatasetCli sample-data/benchmark-sample.json 1 cheap");
    }

    private static List<String> loadTexts(Path datasetPath) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Object root = mapper.readValue(Files.readString(datasetPath), Object.class);

        if (root instanceof List<?> list) {
            return parseTexts(list);
        }

        if (root instanceof Map<?, ?> map) {
            Object candidate = map.get("texts");
            if (candidate instanceof List<?> list) {
                return parseTexts(list);
            }
        }

        throw new IllegalArgumentException("Dataset deve ser uma lista de textos ou um objeto com a chave 'texts'.");
    }

    private static List<String> parseTexts(List<?> values) {
        List<String> texts = new ArrayList<>();
        for (Object value : values) {
            if (value == null) {
                continue;
            }
            if (value instanceof String text) {
                texts.add(text);
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                String text = map.get("text") != null ? String.valueOf(map.get("text")) : map.get("texto") != null ? String.valueOf(map.get("texto")) : null;
                if (text != null && !text.isBlank()) {
                    texts.add(text);
                }
            }
        }
        return texts;
    }
}
