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

    @Test
    void launchesTheCurrentArtifactFromAnyDirectoryAndResolvesItsBundledCorpus() throws Exception {
        Path bundle = fixtureBundle();
        Files.createDirectories(bundle.resolve("target"));
        Files.writeString(bundle.resolve("target/corretor-enem-0.1.0-SNAPSHOT.jar"), "current");
        Files.writeString(bundle.resolve("target/corretor-enem-two-raters-v2.jar"), "historical");
        var builder = new ProcessBuilder("bash", bundle.resolve("scripts/redator-launch.sh").toString(), "run", "--fake")
                .directory(temporaryDirectory.toFile()).redirectErrorStream(true);
        builder.environment().put("REDATOR_DATA_DIR", temporaryDirectory.resolve("data").toString());
        builder.environment().put("REDATOR_JAVA_BIN", fakeJava(bundle.resolve("test-java")).toString());
        builder.environment().remove("RAG_DATABASE");
        var process = builder.start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("artifact=current", "cwd=" + bundle, "rag=" + bundle.resolve("dataset/public-corpus.db"));
    }

    @Test
    void packagedAppUsesItsBundledJavaAndExecutableJarWithoutMavenOrSystemJava() throws Exception {
        Path bundle = fixtureBundle();
        Files.createDirectories(bundle.resolve("app"));
        Files.writeString(bundle.resolve("app/redator.jar"), "packaged");
        fakeJava(bundle.resolve("runtime/bin/java"));
        var builder = new ProcessBuilder("bash", bundle.resolve("scripts/redator-launch.sh").toString(), "run", "--fake")
                .directory(temporaryDirectory.toFile()).redirectErrorStream(true);
        builder.environment().put("REDATOR_DATA_DIR", temporaryDirectory.resolve("data").toString());
        builder.environment().remove("REDATOR_JAVA_BIN");
        var process = builder.start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("artifact=packaged");
    }

    @Test
    void statusAndStopRecognizeTheExactPackagedJarInAPathWithSpaces() throws Exception {
        Path bundle = fixtureBundle(), java = bundle.resolve("runtime/bin/java");
        Path jar = bundle.resolve("app/redator.jar"), data = temporaryDirectory.resolve("packaged-data");
        Files.createDirectories(java.getParent()); Files.createDirectories(jar.getParent());
        Files.writeString(jar, "packaged");
        Files.writeString(java, "#!/usr/bin/env bash\nread -r waiting\n");
        var app = new ProcessBuilder("bash", java.toString(), "-jar", jar.toString()).start();
        Path runtime = Files.createDirectories(data.resolve("runtime"));
        Files.writeString(runtime.resolve("redator.pid"), Long.toString(app.pid()));
        Files.writeString(runtime.resolve("redator.port"), "18089");
        Files.writeString(runtime.resolve("redator.jar-path"), jar.toString());
        try {
            for (String command : new String[]{"status", "stop"}) {
                var builder = new ProcessBuilder("bash", bundle.resolve("scripts/redator-launch.sh").toString(), command)
                        .redirectErrorStream(true);
                builder.environment().put("REDATOR_DATA_DIR", data.toString());
                var process = builder.start();
                assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(process.exitValue()).as(output).isZero();
                assertThat(output).contains("status=" + (command.equals("status") ? "running" : "stopped"));
            }
            assertThat(app.waitFor(5, TimeUnit.SECONDS)).isTrue();
        } finally { app.destroyForcibly(); }
    }

    @Test
    void firstBuildKeepsMavenOutputOutOfTheArtifactPathAndResolvesRelativeData() throws Exception {
        Path bundle = fixtureBundle(), bin = Files.createDirectories(bundle.resolve("test-bin"));
        Path maven = bin.resolve("mvn");
        Files.writeString(maven, """
                #!/usr/bin/env bash
                mkdir -p target
                printf built > target/corretor-enem-0.1.0-SNAPSHOT.jar
                echo 'Log de compilação do Maven'
                """);
        assertThat(maven.toFile().setExecutable(true)).isTrue();
        var builder = new ProcessBuilder("bash", bundle.resolve("scripts/redator-launch.sh").toString(), "run", "--fake")
                .directory(temporaryDirectory.toFile()).redirectErrorStream(true);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("REDATOR_DATA_DIR", "relative-data");
        builder.environment().put("REDATOR_JAVA_BIN", fakeJava(bundle.resolve("test-java")).toString());
        builder.environment().remove("REDATOR_APP_JAR");
        var process = builder.start();
        assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("artifact=built", "data=" + temporaryDirectory.resolve("relative-data"));
    }

    private Path fixtureBundle() throws Exception {
        Path bundle = temporaryDirectory.resolve("bundle com espaços");
        Files.createDirectories(bundle.resolve("scripts"));
        Files.copy(Path.of("scripts/redator-launch.sh"), bundle.resolve("scripts/redator-launch.sh"));
        return bundle;
    }

    private Path fakeJava(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, """
                #!/usr/bin/env bash
                if [[ "${1:-}" == -version ]]; then
                  echo 'openjdk version "21.0.1"' >&2
                else
                  printf 'artifact=%s\\ncwd=%s\\nrag=%s\\ndata=%s\\n' "$(cat "$2")" "$PWD" "${RAG_DATABASE:-unset}" "${REDATOR_DATA_DIR:-unset}"
                fi
                """);
        assertThat(file.toFile().setExecutable(true)).isTrue();
        return file;
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
