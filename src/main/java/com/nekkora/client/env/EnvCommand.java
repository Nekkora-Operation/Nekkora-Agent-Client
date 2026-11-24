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
     * Roda {@code python -I <args...>} em {@code workDir}, ate {@code timeout} ou ate {@code cancel} dizer que basta (o grupo inteiro
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
