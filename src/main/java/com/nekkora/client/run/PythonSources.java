package com.nekkora.client.run;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * De onde vem os CANDIDATOS a instalacao de Python deste computador. A descoberta e LIMITADA: so pergunta a fontes que o
 * proprio sistema mantem. Nao percorre discos, nao procura ambientes virtuais de projetos e nao le pastas do usuario.
 *
 * <p>No Windows, tres fontes, cada uma opcional e independente (uma que falha nao derruba as outras):
 * <ol>
 * <li><b>Inicializador do Python</b> ({@code py.exe -0p}): lista as instalacoes que ele conhece;</li>
 * <li><b>Registro de instalacoes</b> (PEP 514: {@code Software\Python} do usuario e da maquina, 64 e 32 bits): SO LEITURA,
 * por {@code reg.exe export} para um arquivo na pasta de trabalho do aplicativo (o arquivo sai em Unicode, entao caminhos
 * com acento chegam inteiros). O registro nunca e alterado;</li>
 * <li><b>PATH</b>: em cada pasta do PATH, o arquivo {@code python.exe}, se existir.</li>
 * </ol>
 * Fora do Windows, so o PATH ({@code python3} e {@code python}).
 *
 * <p>Os dois programas do sistema sao chamados pelo caminho absoluto, com argumentos separados e sem shell, com prazo.
 * Daqui saem so CAMINHOS: quem decide se um candidato e um Python de verdade e a sondagem ({@link PythonProbe}).
 *
 * <p><b>Microsoft Store.</b> A pasta {@code WindowsApps} guarda os "aliases de execucao": um {@code python.exe} que, sem
 * o Python da Store instalado, ABRE A LOJA para instalar. Nada dessa pasta e candidato, e nada dela e executado. Um
 * Python instalado pela Store, portanto, nao aparece na lista (limitacao declarada).
 */
public interface PythonSources {

    /** O que uma fonte devolveu. {@code failed}: a fonte existe e nao respondeu (a busca fica parcial). */
    record Found(String source, List<Path> paths, boolean failed) {}

    String LAUNCHER = "inicializador do Python (py)";
    String REGISTRY = "registro de instalações";
    String PATH = "PATH";

    /** Todas as fontes disponiveis, na ordem. Bloqueia (processos do sistema, com prazo): fora da linha de eventos. */
    List<Found> candidates(PythonProbe.Cancel cancel);

    /** Um candidato que fica na pasta dos aliases da Microsoft Store: nunca e sondado. */
    static boolean storeAlias(Path p) {
        for (Path part : p) {
            if (part.toString().equalsIgnoreCase("WindowsApps")) return true;
        }
        return false;
    }

    /** As fontes deste computador. {@code workDir}: pasta do aplicativo onde a leitura do registro e gravada e apagada. */
    static PythonSources system(Path workDir) {
        return new Os(System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"), System.getenv(), workDir, Os::exec);
    }

    /** Como um programa do sistema e chamado (os testes trocam por respostas prontas). {@code null}: nao respondeu. */
    @FunctionalInterface
    interface Command {
        Result run(List<String> command, Path workDir, PythonProbe.Cancel cancel);
    }

    record Result(int exit, byte[] output) {}

    final class Os implements PythonSources {
        static final Duration COMMAND_TIMEOUT = Duration.ofSeconds(10);
        static final List<String> REGISTRY_KEYS = List.of("HKCU\\Software\\Python", "HKLM\\Software\\Python", "HKLM\\Software\\WOW6432Node\\Python");
        private static final Pattern LAUNCHER_LINE = Pattern.compile("([A-Za-z]:\\\\[^\\r\\n*?\"<>|]*\\.exe)\\s*$");
        private static final Pattern REGISTRY_LINE = Pattern.compile("^\"ExecutablePath\"=\"(.*)\"\\s*$");

        private final boolean windows;
        private final Map<String, String> env;
        private final Path workDir;
        private final Command command;

        Os(boolean windows, Map<String, String> env, Path workDir, Command command) {
            this.windows = windows;
            this.env = env;
            this.workDir = workDir;
            this.command = command;
        }

        @Override
        public List<Found> candidates(PythonProbe.Cancel cancel) {
            List<Found> out = new ArrayList<>();
            if (windows) {
                Found launcher = launcher(cancel);
                if (launcher != null) out.add(launcher);
                Found registry = registry(cancel);
                if (registry != null) out.add(registry);
            }
            out.add(path());
            return out;
        }

        private String env(String name) {
            for (Map.Entry<String, String> e : env.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }

        private static Path absolute(String text) {
            try {
                if (text == null || text.isBlank()) return null;
                Path p = Path.of(text.trim());
                return p.isAbsolute() ? p.normalize() : null;
            } catch (InvalidPathException e) {
                return null;
            }
        }

        private List<Path> pathFolders() {
            List<Path> dirs = new ArrayList<>();
            String path = env("PATH");
            if (path == null) return dirs;
            for (String part : path.split(java.io.File.pathSeparator)) {
                String clean = part.trim();
                if (clean.length() > 1 && clean.startsWith("\"") && clean.endsWith("\"")) clean = clean.substring(1, clean.length() - 1);
                Path dir = absolute(clean); // entradas relativas dependem da pasta atual: ignoradas
                if (dir != null) dirs.add(dir);
            }
            return dirs;
        }

        /** O inicializador: {@code %SystemRoot%\py.exe} ou o primeiro {@code py.exe} do PATH. Sem ele, a fonte nao existe. */
        private Found launcher(PythonProbe.Cancel cancel) {
            List<Path> places = new ArrayList<>();
            Path root = absolute(env("SystemRoot"));
            if (root != null) places.add(root);
            places.addAll(pathFolders());
            Path py = null;
            for (Path dir : places) {
                Path candidate = dir.resolve("py.exe");
                if (!storeAlias(candidate) && Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                    py = candidate;
                    break;
                }
            }
            if (py == null) return null;
            Result r = command.run(List.of(py.toString(), "-0p"), workDir, cancel);
            if (r == null || r.exit() != 0) return new Found(LAUNCHER, List.of(), true);
            Set<Path> paths = new LinkedHashSet<>();
            for (String text : decodings(r.output())) {
                for (String line : text.split("\\R")) {
                    Matcher m = LAUNCHER_LINE.matcher(line);
                    if (m.find()) {
                        Path p = absolute(m.group(1));
                        if (p != null) paths.add(p);
                    }
                }
            }
            return new Found(LAUNCHER, List.copyOf(paths), false);
        }

        /**
         * O texto do inicializador: UTF-8 quando os bytes sao UTF-8 validos; senao, a codificacao do sistema. Um caminho
         * que saia errado daqui nao existe em disco e e descartado por quem consome.
         */
        private static List<String> decodings(byte[] bytes) {
            try {
                return List.of(StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString());
            } catch (java.nio.charset.CharacterCodingException e) {
                String nativeName = System.getProperty("native.encoding");
                Charset c = nativeName != null && Charset.isSupported(nativeName) ? Charset.forName(nativeName) : StandardCharsets.ISO_8859_1;
                return List.of(new String(bytes, c));
            }
        }

        /** O registro, so para leitura. Chave ausente nao e falha (exportar uma chave que nao existe termina com erro). */
        private Found registry(PythonProbe.Cancel cancel) {
            Path root = absolute(env("SystemRoot"));
            Path reg = root == null ? null : root.resolve("System32").resolve("reg.exe");
            if (reg == null || !Files.isRegularFile(reg, LinkOption.NOFOLLOW_LINKS)) return null;
            Set<Path> paths = new LinkedHashSet<>();
            boolean failed = false;
            for (String key : REGISTRY_KEYS) {
                if (cancel.cancelled()) break;
                Path file = workDir.resolve("instalacoes-" + UUID.randomUUID() + ".reg");
                try {
                    Files.createDirectories(workDir);
                    Result r = command.run(List.of(reg.toString(), "export", key, file.toString(), "/y"), workDir, cancel);
                    if (r == null) {
                        failed = true;
                        continue;
                    }
                    if (r.exit() != 0 || !Files.isRegularFile(file)) continue; // a chave nao existe nesta maquina
                    byte[] bytes = Files.size(file) > 8L * 1024 * 1024 ? new byte[0] : Files.readAllBytes(file);
                    for (String line : new String(bytes, StandardCharsets.UTF_16).split("\\R")) {
                        Matcher m = REGISTRY_LINE.matcher(line.trim());
                        if (m.matches()) {
                            Path p = absolute(m.group(1).replace("\\\\", "\\").replace("\\\"", "\""));
                            if (p != null) paths.add(p);
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    failed = true;
                } finally {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        // fica na pasta de trabalho do aplicativo
                    }
                }
            }
            return new Found(REGISTRY, List.copyOf(paths), failed && paths.isEmpty());
        }

        private Found path() {
            Set<Path> paths = new LinkedHashSet<>();
            for (Path dir : pathFolders()) {
                for (String name : windows ? List.of("python.exe") : List.of("python3", "python")) {
                    Path p = dir.resolve(name);
                    try {
                        if (!storeAlias(p) && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) paths.add(p);
                    } catch (RuntimeException e) {
                        // entrada ilegivel do PATH: ignorada
                    }
                }
            }
            return new Found(PATH, List.copyOf(paths), false);
        }

        /** Chama um programa do sistema: caminho absoluto, argumentos separados, sem shell, com prazo. */
        static Result exec(List<String> cmd, Path workDir, PythonProbe.Cancel cancel) {
            Process p = null;
            Path output = null;
            try {
                Files.createDirectories(workDir);
                output = workDir.resolve("fonte-" + UUID.randomUUID() + ".saida");
                ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(output.toFile())
                        .redirectInput(ProcessBuilder.Redirect.PIPE);
                PythonRuntime.minimalEnvironment(pb.environment());
                pb.environment().remove("PYTHONUTF8");
                pb.environment().remove("PYTHONIOENCODING");
                p = pb.start();
                p.getOutputStream().close();
                long limit = System.nanoTime() + COMMAND_TIMEOUT.toNanos();
                while (!p.waitFor(40, TimeUnit.MILLISECONDS)) {
                    if (cancel.cancelled() || System.nanoTime() >= limit) return null;
                }
                byte[] bytes = Files.size(output) > 1024 * 1024 ? new byte[0] : Files.readAllBytes(output);
                return new Result(p.exitValue(), bytes);
            } catch (IOException | RuntimeException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            } finally {
                if (p != null && p.isAlive()) {
                    p.descendants().forEach(ProcessHandle::destroyForcibly);
                    p.destroyForcibly();
                }
                if (output != null) {
                    try {
                        Files.deleteIfExists(output);
                    } catch (IOException e) {
                        // fica na pasta de trabalho do aplicativo
                    }
                }
            }
        }
    }
}
