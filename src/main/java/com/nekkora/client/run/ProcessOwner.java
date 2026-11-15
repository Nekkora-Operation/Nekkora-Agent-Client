package com.nekkora.client.run;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Posse de um processo lancado pelo aplicativo (portado do Nekkora Agent 9b1e7e1, sem mudanca): PID + INSTANTE DE INICIO + executavel. Um PID reutilizado pelo Windows (outro
 * inicio) nao e posse; processo externo de mesmo nome nunca e tocado. So se encerra o processo possuido e os seus
 * descendentes que comecaram depois dele. O executavel fica como RESUMO ({@link #digest}) do caminho: o registro em disco
 * nao guarda o caminho (que tem o nome do usuario do Windows).
 */
public record ProcessOwner(long pid, long startMillis, String executableDigest) {

    /** Posse do processo recem-lancado. */
    public static ProcessOwner of(Process p, String executable) {
        ProcessHandle h = p.toHandle();
        long start = h.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
        return new ProcessOwner(h.pid(), start, digest(executable));
    }

    /** Resumo do caminho do executavel (sem diferenca de maiusculas nem de barras). */
    public static String digest(String executable) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(executable.replace('/', '\\').toLowerCase(java.util.Locale.ROOT).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** O processo com este PID ainda e o MESMO que o Agent lancou (mesmo inicio e mesmo executavel)? */
    public Optional<ProcessHandle> handle() {
        if (startMillis < 0) return Optional.empty();
        return ProcessHandle.of(pid).filter(ProcessHandle::isAlive).filter(h -> h.info().startInstant().map(Instant::toEpochMilli).orElse(-2L) == startMillis)
                .filter(h -> h.info().command().map(c -> digest(c).equals(executableDigest)).orElse(false));
    }

    public boolean alive() {
        return handle().isPresent();
    }

    /**
     * Encerra os descendentes possuidos (iniciados depois do processo) e o processo. Espera ate {@code wait}. Devolve
     * quantos processos foram encerrados.
     */
    public int terminate(Duration wait) {
        Optional<ProcessHandle> root = handle();
        if (root.isEmpty()) return 0;
        List<ProcessHandle> owned = new ArrayList<>();
        root.get().descendants().forEach(d -> {
            long s = d.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            if (s >= startMillis) owned.add(d); // PID reaproveitado por outro programa comecou antes: nao e nosso
        });
        int n = 0;
        // de baixo para cima: os netos primeiro, para nada ser reparentado no meio
        for (int i = owned.size() - 1; i >= 0; i--) if (owned.get(i).destroyForcibly()) n++;
        if (root.get().destroyForcibly()) n++;
        long deadline = System.nanoTime() + wait.toNanos();
        for (ProcessHandle h : owned) waitGone(h, deadline);
        waitGone(root.get(), deadline);
        return n;
    }

    private static void waitGone(ProcessHandle h, long deadline) {
        while (h.isAlive() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    static boolean sameExecutable(String a, String b) {
        return a != null && b != null && a.replace('/', '\\').equalsIgnoreCase(b.replace('/', '\\'));
    }
}
