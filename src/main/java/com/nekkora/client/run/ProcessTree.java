package com.nekkora.client.run;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A ARVORE de processos de uma execucao: o processo lancado pelo aplicativo e os descendentes dele. Serve a uma pergunta so -
 * "ainda ha algo dessa execucao em andamento neste computador?" - e a resposta so e "nao" com prova.
 *
 * <p>Por que um registro proprio: depois que o processo principal termina, o sistema nao diz mais quem eram os filhos dele (um
 * filho que continua vivo fica sem pai conhecido). Entao, ENQUANTO a execucao e acompanhada, cada descendente visto e anotado
 * (PID + instante de inicio) num arquivo da propria execucao; quando o acompanhamento VE o fim do processo principal, isso
 * tambem e anotado ({@link #END}).
 *
 * <p>{@link #verdict}: {@link Verdict#GONE} so quando ha posse valida do processo principal, o fim dele foi VISTO pelo
 * acompanhamento, e nem ele nem nenhum descendente anotado esta vivo (mesmo PID e mesmo inicio). Algum vivo:
 * {@link Verdict#ALIVE}. Qualquer outra coisa - sem posse, sem o arquivo, fim nao visto (o aplicativo estava fechado quando o
 * processo terminou), arquivo ilegivel, processo vivo cujo inicio o sistema nao informa - e {@link Verdict#UNCERTAIN}: nao se
 * afirma nada.
 *
 * <p>LIMITE conhecido: um descendente criado depois da ultima anotacao por um processo que ja terminou (no intervalo de uma
 * volta do acompanhamento, ou enquanto o aplicativo esteve fechado com o processo principal vivo) nao e visto.
 */
final class ProcessTree {

    static final String FILE = "arvore.txt";
    static final String END = "FIM";
    private static final int MAX_NODES = 4096;

    enum Verdict {
        GONE, ALIVE, UNCERTAIN
    }

    private final Path file;
    private final Set<String> noted = new HashSet<>();

    ProcessTree(Path file) {
        this.file = file;
    }

    /** Anota os descendentes vivos do processo principal (que ainda esta vivo). Falha aqui so reduz o que se sabe. */
    void observe(ProcessHandle root, long rootStartMillis) {
        if (root == null || noted.size() >= MAX_NODES) return;
        List<String> fresh = new ArrayList<>();
        try {
            root.descendants().forEach(d -> {
                long start = d.info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
                // PID reaproveitado por outro programa comecou antes do processo principal: nao e desta arvore
                if (start >= 0 && start < rootStartMillis) return;
                String line = d.pid() + " " + start;
                if (noted.size() + fresh.size() < MAX_NODES && !noted.contains(line) && !fresh.contains(line)) fresh.add(line);
            });
        } catch (RuntimeException e) {
            return;
        }
        if (fresh.isEmpty()) return;
        if (append(String.join("\n", fresh) + "\n")) noted.addAll(fresh);
    }

    /** O acompanhamento VIU o processo principal terminar (ou o encerrou). */
    void ended() {
        append(END + "\n");
    }

    private boolean append(String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Ha prova de que nada da arvore desta execucao continua em andamento? */
    static Verdict verdict(ProcessOwner owner, Path file) {
        if (owner == null || owner.startMillis() < 0) return Verdict.UNCERTAIN;
        List<long[]> nodes = new ArrayList<>();
        nodes.add(new long[] {owner.pid(), owner.startMillis()});
        boolean endSeen = false;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) return Verdict.UNCERTAIN;
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.equals(END)) {
                    endSeen = true;
                    continue;
                }
                String[] p = line.split(" ");
                if (p.length != 2) return Verdict.UNCERTAIN;
                nodes.add(new long[] {Long.parseLong(p[0]), Long.parseLong(p[1])});
            }
        } catch (IOException | RuntimeException e) {
            return Verdict.UNCERTAIN;
        }
        boolean uncertain = !endSeen;
        for (long[] n : nodes) {
            Optional<ProcessHandle> h;
            try {
                h = ProcessHandle.of(n[0]).filter(ProcessHandle::isAlive);
            } catch (RuntimeException e) {
                uncertain = true;
                continue;
            }
            if (h.isEmpty()) continue;
            long start = h.get().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
            if (start < 0 || n[1] < 0) {
                // ha um processo vivo com esse PID e nao da para saber se e o mesmo
                uncertain = true;
            } else if (start == n[1]) {
                return Verdict.ALIVE;
            }
        }
        return uncertain ? Verdict.UNCERTAIN : Verdict.GONE;
    }
}
