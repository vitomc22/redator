package com.redator.corretor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.KamelDatasetImporter;
import com.redator.corretor.service.ExtendedEssayBrImporter;
import com.redator.corretor.service.PublicHumanCorpus;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class PublicCorpusCli {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PublicCorpusCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        Map<String, String> options = options(args);
        Path database = Path.of(options.getOrDefault("db", "dataset/public-corpus.db"));
        try (PublicHumanCorpus corpus = new PublicHumanCorpus(database)) {
            switch (args[0]) {
                case "import-kamel" -> importKamel(corpus, options);
                case "import-extended" -> importExtended(corpus);
                case "stats" -> print(corpus.statistics());
                case "search" -> search(corpus, options);
                default -> throw new IllegalArgumentException("comando desconhecido: " + args[0]);
            }
        }
    }

    private static void importExtended(PublicHumanCorpus corpus) throws Exception {
        var result = new ExtendedEssayBrImporter().importAll(corpus);
        print(Map.of("source", ExtendedEssayBrImporter.SOURCE, "revision", result.revision(),
                "skippedRows", result.skippedRows(), "duplicateRows", result.duplicateRows(),
                "imported", result.imported(), "statistics", corpus.statistics()));
    }

    private static void importKamel(PublicHumanCorpus corpus, Map<String, String> options) throws Exception {
        String config = options.getOrDefault("config", "JBCS2025");
        List<String> splits = Arrays.stream(options.getOrDefault("splits", "train,validation,test").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).toList();
        int pageSize = Integer.parseInt(options.getOrDefault("page-size", "100"));
        KamelDatasetImporter importer = new KamelDatasetImporter();
        List<Map<String, Object>> imported = new ArrayList<>();
        for (String split : splits) {
            var result = importer.importSplit(corpus, config, split, pageSize);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("config", config);
            item.put("split", split);
            item.put("revision", result.revision());
            item.put("downloaded", result.downloaded());
            item.put("imported", result.imported());
            imported.add(item);
        }
        print(Map.of("imports", imported, "statistics", corpus.statistics()));
    }

    private static void search(PublicHumanCorpus corpus, Map<String, String> options) throws Exception {
        String query = options.getOrDefault("query", "");
        String theme = options.get("theme");
        int limit = Integer.parseInt(options.getOrDefault("limit", "3"));
        List<String> excluded = Arrays.stream(options.getOrDefault("exclude", "").split(","))
                .map(String::strip).filter(value -> !value.isBlank()).toList();
        print(Map.of("examples", corpus.retrieve(query, theme, limit, excluded)));
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> result = new LinkedHashMap<>();
        for (int index = 1; index < args.length; index++) {
            String option = args[index];
            if (!option.startsWith("--") || index + 1 >= args.length) {
                throw new IllegalArgumentException("opcao exige valor: " + option);
            }
            result.put(option.substring(2), args[++index]);
        }
        return result;
    }

    private static void print(Object value) throws Exception {
        System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(value));
    }

    private static void usage() {
        System.out.println("Uso:");
        System.out.println("  PublicCorpusCli import-kamel [--db dataset/public-corpus.db] [--config JBCS2025] [--splits train,validation,test]");
        System.out.println("  PublicCorpusCli import-extended [--db dataset/public-corpus.db]");
        System.out.println("  PublicCorpusCli stats [--db dataset/public-corpus.db]");
        System.out.println("  PublicCorpusCli search [--db dataset/public-corpus.db] --query <texto> [--theme <tema>] [--limit 3]");
    }
}
