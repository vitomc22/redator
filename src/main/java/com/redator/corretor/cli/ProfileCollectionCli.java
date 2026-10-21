package com.redator.corretor.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redator.corretor.service.ProfileCollectionExporter;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Offline preparation only: no Spring context, runtime, network or live collection activation. */
public final class ProfileCollectionCli {
    private ProfileCollectionCli() { }

    public static void main(String[] args) throws Exception {
        var options = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i += 2) {
            if (i + 1 >= args.length || !Set.of("--source", "--output").contains(args[i])
                    || args[i + 1].isBlank() || options.putIfAbsent(args[i], args[i + 1]) != null)
                throw usage();
        }
        if (options.size() != 2) throw usage();
        var report = new ProfileCollectionExporter().export(Path.of(options.get("--source")), Path.of(options.get("--output")));
        System.out.println(new ObjectMapper().writeValueAsString(Map.of("status", "PREPARED_NOT_ACTIVATED",
                "manifest", report.manifest().toString(), "profileCount", report.profiles().size())));
    }

    private static IllegalArgumentException usage() {
        return new IllegalArgumentException("Uso: --source <coleção SQLite legada> --output <diretório novo>");
    }
}
