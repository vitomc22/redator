package com.redator.corretor;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalLauncherContractTest {
    @TempDir Path temporaryDirectory;

    @Test
    void checkPreparesPrivateLocalLayoutWithoutStartingTheApplication() throws Exception {
        ProcessBuilder builder = launcher("check", "--fake");
        Process process = builder.start();
        process.getOutputStream().close();
        assertThat(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output)
                .contains("status=ready")
                .contains("provider=fake")
                .contains("address=127.0.0.1")
                .contains("data=" + temporaryDirectory);
        assertThat(Files.isDirectory(temporaryDirectory.resolve("runtime"))).isTrue();
        assertThat(Files.isDirectory(temporaryDirectory.resolve("logs"))).isTrue();
        assertThat(Files.exists(temporaryDirectory.resolve("runtime/redator.pid"))).isFalse();
    }

    @Test
    void stopOnlyRemovesAStalePidFileAndNeverSignalsAnUnrelatedProcess() throws Exception {
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("runtime"));
        Files.writeString(runtime.resolve("redator.pid"), Long.toString(ProcessHandle.current().pid()));

        Process process = launcher("stop", "--fake").start();
        process.getOutputStream().close();
        assertThat(process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("status=stopped").contains("reason=stale-pid");
        assertThat(ProcessHandle.current().isAlive()).isTrue();
        assertThat(runtime.resolve("redator.pid")).doesNotExist();
    }

    private ProcessBuilder launcher(String... arguments) {
        String[] command = new String[arguments.length + 2];
        command[0] = "bash";
        command[1] = "scripts/redator-launch.sh";
        System.arraycopy(arguments, 0, command, 2, arguments.length);
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(Path.of(".").toAbsolutePath().normalize().toFile())
                .redirectErrorStream(true);
        builder.environment().put("REDATOR_DATA_DIR", temporaryDirectory.toString());
        return builder;
    }
}
