package com.redator.corretor.cli;

import com.redator.corretor.CorretorEnemApplication;
import com.redator.corretor.service.PairedBenchmarkRunner;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Starts an isolated local Spring context and runs one real Gemma benchmark arm. */
public final class PairedBenchmarkCli {
    private PairedBenchmarkCli() { }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = options(args);
        String arm = required(options, "arm");
        if (!arm.equals("baseline") && !arm.equals("rag"))
            throw new IllegalArgumentException("--arm deve ser baseline ou rag");
        String dataDirectory = required(options, "data-directory");
        Path dataPath = Path.of(dataDirectory).toAbsolutePath().normalize();
        Files.createDirectories(dataPath);
        if (!Files.isDirectory(dataPath) || !Files.isWritable(dataPath))
            throw new IllegalArgumentException("--data-directory precisa ser um diretório gravável");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put("spring.main.banner-mode", "off"); defaults.put("spring.main.web-application-type", "none");
        defaults.put("app.data-directory", dataPath.toString());
        defaults.put("app.rag.enabled", String.valueOf(arm.equals("rag")));
        defaults.put("app.rag.require-on-startup", String.valueOf(arm.equals("rag")));
        defaults.put("app.llm.provider", options.getOrDefault("provider", "ollama"));
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(CorretorEnemApplication.class)
                .web(WebApplicationType.NONE).properties(defaults).run(
                        "--app.data-directory=" + dataPath,
                        "--app.rag.enabled=" + arm.equals("rag"),
                        "--app.rag.require-on-startup=" + arm.equals("rag"),
                        "--app.llm.provider=" + options.getOrDefault("provider", "ollama"))) {
            context.getBean(PairedBenchmarkRunner.class).run(Path.of(required(options, "benchmark")),
                    Path.of(required(options, "output")), arm);
        }
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < args.length; index += 2) {
            if (!args[index].startsWith("--") || index + 1 >= args.length)
                throw new IllegalArgumentException(usage());
            values.put(args[index].substring(2), args[index + 1]);
        }
        return values;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(usage());
        return value;
    }

    private static String usage() {
        return "Uso: --benchmark <json> --output <json> --arm baseline|rag --data-directory <diretório> [--provider ollama|fake]";
    }
}
