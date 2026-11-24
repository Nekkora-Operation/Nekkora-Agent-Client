package com.nekkora.client.env;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.nekkora.client.run.ProcessContainer;
import com.nekkora.client.run.PythonRuntime;

/**
 * Um processo auxiliar do ambiente gerenciado: o interpretador com caminho absoluto, argumentos em LISTA (nunca um shell, nunca
 * uma linha de comando montada), sempre com {@code -I} (modo isolado: sem a pasta atual, sem o site do usuario, sem as variaveis
 * {@code PYTHON*}), so com as variaveis de {@link PythonRuntime#ENV_KEEP} (nenhuma {@code PIP_*}, nenhum proxy do sistema), num
 * grupo de processos proprio do Windows ({@link ProcessContainer}). A saida (padrao e de erro, juntas) vai para um arquivo da
 * pasta de trabalho: a espera com prazo nao depende de alguem ler o fluxo.
 */
final class EnvCommand {

    /** Como o processo terminou. {@code output}: o fim do que ele escreveu (ate {@code keep} bytes), em UTF-8. */
    record Ran(int exit, boolean timedOut, boolean cancelled, String output, Path outputFile) {
        boolean ok() {
            return exit == 0 && !timedOut && !cancelled;
        }
    }

    private EnvCommand() {
    }

    /**
     * Roda {@code python -I -X utf8 <args...>} em {@code workDir}, ate {@code timeout} ou ate {@code cancel} dizer que basta (o grupo inteiro
     * e encerrado). Bloqueia. Falha ao lancar: {@link IOException}.
     */
    static Ran run(Path python, List<String> args, Path workDir, String name, Duration timeout, BooleanSupplier cancel, int keep) throws IOException {
        if (!python.isAbsolute() || args.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IOException("comando");
        }
        Files.createDirectories(workDir);
        Path out = workDir.resolve(name + ".saida");
        Files.deleteIfExists(out);
        List<String> cmd = new java.util.ArrayList<>();
        cmd.add(python.toString());
        // modo isolado SEMPRE, e como primeiro argumento
        cmd.add("-I");
        // modo UTF-8 (o -I ignora PYTHONIOENCODING): a saida do venv e do pip chega em UTF-8, inclusive as mensagens do Windows
        // ("ConnectionResetError: [WinError 10054] Foi forcado...") que, na pagina de codigo do console, viravam caracteres trocados
        // no resumo e no registro do preparo (achado na validacao de ponta a ponta)
        cmd.add("-X");
        cmd.add("utf8");
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile()).redirectErrorStream(true).redirectOutput(out.toFile())
                .redirectInput(ProcessBuilder.Redirect.PIPE);
        PythonRuntime.minimalEnvironment(pb.environment());
        // por garantia (a lista fechada ja nao as deixa passar): nada de configuracao do pip nem de proxy vindo do sistema
        pb.environment().keySet().removeIf(k -> {
            String u = k.toUpperCase(Locale.ROOT);
            return u.startsWith("PIP_") || u.endsWith("_PROXY") || u.equals("PYTHONPATH") || u.equals("PYTHONHOME");
        });
        ProcessContainer group = ProcessContainer.create();
        Process p = null;
        boolean timedOut = false;
        boolean cancelled = false;
        try {
            p = pb.start();
            group.add(p);
            mark(workDir, name, p.toHandle());
            p.getOutputStream().close();
            long limit = System.nanoTime() + timeout.toNanos();
            while (!p.waitFor(50, TimeUnit.MILLISECONDS)) {
                if (cancel.getAsBoolean()) {
                    cancelled = true;
                    break;
                }
                if (System.nanoTime() >= limit) {
                    timedOut = true;
                    break;
                }
            }
            if (timedOut || cancelled) {
                group.terminate();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            // os descendentes que ainda estiverem no grupo (o processo principal ja terminou) tambem saem
            if (group.alive() > 0) {
                group.terminate();
            }
            return new Ran(p.isAlive() ? -1 : p.exitValue(), timedOut, cancelled, tail(out, keep), out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            group.terminate();
            return new Ran(-1, false, true, tail(out, keep), out);
        } finally {
            if (p != null && p.isAlive()) {
                group.terminate();
            }
            group.close();
            if (p == null || !p.isAlive()) {
                try {
                    Files.deleteIfExists(workDir.resolve(name + MARK));
                } catch (IOException e) {
                    // a marca de um processo que ja terminou nao encerra nada (o inicio nao confere)
                }
            }
        }
    }

    /** A marca de um processo auxiliar em andamento: PID e instante de inicio, na pasta de trabalho dele. */
    static final String MARK = ".processo";

    private static void mark(Path workDir, String name, ProcessHandle h) {
        java.util.Optional<java.time.Instant> start = h.info().startInstant();
        if (start.isEmpty()) {
            return;
        }
        try {
            Files.writeString(workDir.resolve(name + MARK), h.pid() + " " + start.get().toEpochMilli(), StandardCharsets.US_ASCII);
        } catch (IOException e) {
            // sem a marca, uma queda do aplicativo deixa o processo seguir ate terminar (o que ele deixar sai na varredura)
        }
    }

    /**
     * Depois de uma queda do aplicativo, o {@code venv} ou o {@code pip} de um preparo (ou de uma resolucao) pode seguir rodando
     * sozinho: o grupo de processos so encerra o que o aplicativo manda encerrar. Encerra os processos marcados debaixo de
     * {@code dir} (e os descendentes deles) que AINDA SAO os mesmos: mesmo PID e mesmo instante de inicio, ao milissegundo (um PID
     * reaproveitado por outro programa nao confere e nao e tocado). So Java, sem funcao nova do sistema. Devolve quantos encerrou.
     */
    static int stopLeftovers(Path dir) {
        int[] n = {0};
        java.util.List<Path> marks = new java.util.ArrayList<>();
        find(dir, marks, 0);
        for (Path m : marks) {
            try {
                String[] v = Files.readString(m, StandardCharsets.US_ASCII).strip().split(" ");
                long pid = Long.parseLong(v[0]);
                long start = Long.parseLong(v[1]);
                ProcessHandle.of(pid).filter(h -> h.info().startInstant().map(i -> i.toEpochMilli() == start).orElse(false)).ifPresent(h -> {
                    h.descendants().forEach(ProcessHandle::destroyForcibly);
                    h.destroyForcibly();
                    try {
                        h.onExit().get(10, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        // segue: a remocao da pasta tenta de novo na varredura seguinte
                    }
                    n[0]++;
                });
                Files.deleteIfExists(m);
            } catch (IOException | RuntimeException e) {
                // marca ilegivel: nao encerra nada
            }
        }
        return n[0];
    }

    private static void find(Path dir, java.util.List<Path> out, int depth) {
        if (depth > 3 || !Files.isDirectory(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isSymbolicLink(p)) {
                    continue;
                }
                if (Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    // so as pastas de trabalho do aplicativo; o ambiente (venv) nao tem marcas
                    if (!p.getFileName().toString().equals("ambiente") && !p.getFileName().toString().equals("venv")) {
                        find(p, out, depth + 1);
                    }
                } else if (p.getFileName().toString().endsWith(MARK)) {
                    out.add(p);
                }
            }
        } catch (IOException | RuntimeException e) {
            // fica para a proxima
        }
    }

    /** O fim do arquivo de saida, ate {@code keep} bytes, em texto (bytes que nao sao UTF-8 viram substituicao). */
    static String tail(Path file, int keep) {
        try {
            long size = Files.size(file);
            try (InputStream in = Files.newInputStream(file)) {
                if (size > keep) {
                    in.skipNBytes(size - keep);
                }
                return new String(in.readNBytes(keep), StandardCharsets.UTF_8);
            }
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }
}
