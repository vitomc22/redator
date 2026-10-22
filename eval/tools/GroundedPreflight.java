import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.config.OllamaProperties;
import com.redator.corretor.model.Tema;
import com.redator.corretor.service.GroundedEvaluationContract;
import com.redator.corretor.service.OllamaLlmProvider;
import com.redator.corretor.service.RagService;
import java.nio.file.*;
import java.util.*;

/** Source-file tool: metadata and prompt preparation only, never /api/chat. */
class GroundedPreflight {
    public static void main(String[] args) throws Exception {
        var json = new ObjectMapper();
        var frozen = json.readValue(Files.readString(Path.of(args[0])), Map.class);
        var output = new LinkedHashMap<String, Object>();
        output.put("selectionFingerprint", frozen.get("selectionFingerprint"));
        output.put("inferencePerformed", false);
        var arms = new LinkedHashMap<String, Object>(); output.put("arms", arms);
        for (boolean enabled : List.of(false, true)) {
            var provider = new OllamaLlmProvider(new OllamaProperties(), new GroundedEvaluationContract(),
                    new RagService(Path.of("dataset/public-corpus.db"), enabled, 1, 1200, "calibration-v2"));
            var results = new ArrayList<Object>();
            for (Object raw : (List<?>) frozen.get("samples")) {
                var sample = (Map<?, ?>) raw;
                String theme = sample.get("theme").toString(), hash = sample.get("essayHash").toString();
                var prepared = provider.prepare(sample.get("text").toString(),
                        new Tema(1L, theme, theme, "Tema proveniente do benchmark humano congelado " + hash), 1L);
                results.add(Map.of("essayHash", hash, "audit", prepared.audit()));
            }
            arms.put(enabled ? "rag" : "baseline", results);
            System.out.println((enabled ? "rag" : "baseline") + ": " + results.size() + " prepared");
        }
        Path outputFile = Path.of(args[1]);
        if (Files.exists(outputFile)) throw new IllegalStateException("Output already exists");
        Files.writeString(outputFile, json.writerWithDefaultPrettyPrinter().writeValueAsString(output) + "\n", StandardOpenOption.CREATE_NEW);
    }
}
