package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Sessao do usuario em que o aplicativo roda: a execucao local so comeca numa sessao INTERATIVA (nunca na Session 0 dos
 * servicos), com area de trabalho e com a tela desbloqueada. No Windows a informacao vem do proprio sistema, sem codigo
 * nativo: {@code %SystemRoot%\System32\tasklist.exe} com argumentos fixos le a sessao do processo do aplicativo e se o
 * {@code LogonUI.exe} (tela de bloqueio) esta na mesma sessao.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.SessionProbe} (Nekkora-Agent 9b1e7e1). Unica diferenca: "ha area de
 * trabalho?" vinha de {@code java.awt.GraphicsEnvironment.isHeadless()}; esta camada nao conhece AWT, entao quem monta o
 * aplicativo entrega a resposta em {@code headless} (na pratica, {@code GraphicsEnvironment::isHeadless}).
 */
public interface SessionProbe {

    /** Estado observado; {@code problem} vazio = pode executar. */
    record Session(boolean interactive, int id, boolean locked, ErrorCode problem) {
        public boolean usable() {
            return problem == null;
        }
    }

    Session probe();

    /**
     * Para o sistema em que o aplicativo roda (Windows: tasklist; outros: execucao local nao suportada nesta versao).
     *
     * @param headless verdadeiro quando NAO ha area de trabalho; implementado fora desta camada
     */
    static SessionProbe system(boolean windows, Map<String, String> env, BooleanSupplier headless) {
        if (!windows) return () -> new Session(false, -1, false, ErrorCode.EXEC_UNSUPPORTED_OS);
        return new Windows(Path.of(env.getOrDefault("SystemRoot", env.getOrDefault("SYSTEMROOT", "C:\\Windows")), "System32", "tasklist.exe"),
                ProcessHandle.current().pid(), headless);
    }

    /** Windows real. */
    final class Windows implements SessionProbe {
        private final Path tasklist;
        private final long pid;
        private final BooleanSupplier headless;

        Windows(Path tasklist, long pid, BooleanSupplier headless) {
            this.tasklist = tasklist;
            this.pid = pid;
            this.headless = headless;
        }

        @Override
        public Session probe() {
            if (headless.getAsBoolean() || !Files.isRegularFile(tasklist)) return new Session(false, -1, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            List<String[]> own = rows(List.of("/FI", "PID eq " + pid, "/FO", "CSV", "/NH"));
            if (own.size() != 1 || own.get(0).length < 4 || !own.get(0)[3].matches("[0-9]{1,5}")) {
                return new Session(false, -1, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            }
            int session = Integer.parseInt(own.get(0)[3]);
            // Session 0 e a dos servicos: sem area de trabalho do usuario, nunca executa ali
            if (session == 0) return new Session(false, 0, false, ErrorCode.EXEC_SESSION_UNAVAILABLE);
            boolean locked = !rows(List.of("/FI", "IMAGENAME eq LogonUI.exe", "/FI", "SESSION eq " + session, "/FO", "CSV", "/NH")).isEmpty();
            return new Session(true, session, locked, locked ? ErrorCode.EXEC_SESSION_LOCKED : null);
        }

        /** Linhas CSV do tasklist (a mensagem "nenhuma tarefa" nao e CSV e fica de fora). */
        private List<String[]> rows(List<String> args) {
            List<String> cmd = new ArrayList<>();
            cmd.add(tasklist.toString());
            cmd.addAll(args);
            List<String[]> out = new ArrayList<>();
            Process p = null;
            try {
                p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                p.getOutputStream().close();
                byte[] data;
                try (InputStream in = p.getInputStream()) {
                    data = in.readNBytes(256 * 1024);
                }
                if (!p.waitFor(10, TimeUnit.SECONDS)) return List.of();
                for (String line : new String(data, StandardCharsets.ISO_8859_1).split("\\R")) {
                    String l = line.strip();
                    if (!l.startsWith("\"")) continue;
                    out.add(l.substring(1, l.length() - 1).split("\",\""));
                }
            } catch (IOException | RuntimeException e) {
                return List.of();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return List.of();
            } finally {
                if (p != null && p.isAlive()) p.destroyForcibly();
            }
            return out;
        }
    }
}
