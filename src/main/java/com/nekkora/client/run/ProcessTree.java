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
import java.util.function.Function;

/**
 * O que se sabe dos PROCESSOS de uma execucao gerenciada - o processo lancado pelo aplicativo e os descendentes dele - gravado
 * num arquivo da propria execucao, neste computador. Serve a uma pergunta so: "ainda ha algo dessa execucao em andamento
 * aqui?". A resposta so e "nao" com PROVA.
 *
 * <p>O arquivo ({@link #FILE}), uma linha por fato:
 * <ul>
 * <li>{@code <pid> <inicio>}: um processo da execucao visto vivo (PID + instante de inicio, para nao confundir com outro
 * programa que reaproveite o PID);</li>
 * <li>{@link #GROUP}: o processo lancado entrou no grupo supervisionado pelo Windows ({@link WindowsProcessGroup});</li>
 * <li>{@link #MARK}: a marca da execucao foi gravada na sessao do Windows;</li>
 * <li>{@link #END}: o acompanhamento viu o processo principal terminar (ou o encerrou);</li>
 * <li>{@link #EMPTY}: o Windows informou que NENHUM processo do grupo esta vivo - a prova;</li>
 * <li>{@link #BROKEN}: a supervisao falhou ou ficou incompleta (o grupo nao pode mais provar nada).</li>
 * </ul>
 *
 * <p>{@link #verdict}:
 * <ul>
 * <li>{@link Verdict#GONE}: ha {@link #EMPTY}; ou a marca foi gravada e NAO existe mais - a sessao do Windows em que a
 * execucao rodou acabou (saida do usuario ou reinicio), e com ela todos os processos dela. Isso encerra a duvida sobre os
 * processos; nao diz nada sobre o resultado da automacao;</li>
 * <li>{@link Verdict#ALIVE}: algum processo anotado esta vivo (mesmo PID, mesmo inicio);</li>
 * <li>{@link Verdict#UNCERTAIN}: todo o resto. Sem o grupo vazio e sem a sessao encerrada NAO se afirma que acabou, mesmo
 * que nenhum processo anotado esteja vivo: um descendente criado por um processo que ja terminou nao aparece em nenhuma
 * lista fora do grupo.</li>
 * </ul>
 */
final class ProcessTree {

    static final String FILE = "arvore.txt";
    static final String GROUP = "GRUPO";
    static final String MARK = "MARCA";
    static final String END = "FIM";
    static final String EMPTY = "VAZIO";
    static final String BROKEN = "FALHA";
    private static final Set<String> FACTS = Set.of(GROUP, MARK, END, EMPTY, BROKEN);
    private static final int MAX_NODES = 4096;

    enum Verdict {
        GONE, ALIVE, UNCERTAIN
    }

    private final Path file;
    private final Set<String> noted = new HashSet<>();

    ProcessTree(Path file) {
        this.file = file;
    }

    /** Anota processos vivos da execucao. Falha aqui so reduz o que se sabe. */
    void note(List<Long> pids) {
        if (pids == null || noted.size() >= MAX_NODES) return;
        List<String> fresh = new ArrayList<>();
        for (Long pid : pids) {
            long start;
            try {
                start = ProcessHandle.of(pid).flatMap(h -> h.info().startInstant()).map(Instant::toEpochMilli).orElse(-1L);
            } catch (RuntimeException e) {
                start = -1;
            }
            String line = pid + " " + start;
            if (noted.size() + fresh.size() < MAX_NODES && !noted.contains(line) && !fresh.contains(line)) fresh.add(line);
        }
        if (!fresh.isEmpty() && append(String.join("\n", fresh) + "\n")) noted.addAll(fresh);
    }

    /** Grava um fato ({@link #GROUP}, {@link #MARK}, {@link #END}, {@link #EMPTY}, {@link #BROKEN}), uma vez. */
    boolean fact(String fact) {
        if (!FACTS.contains(fact)) throw new IllegalArgumentException("fato");
        if (noted.contains(fact)) return true;
        if (!append(fact + "\n")) return false;
        noted.add(fact);
        return true;
    }

    private boolean append(String text) {
        try {
            Files.write(file, text.getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /**
     * Ha prova de que nada desta execucao continua em andamento?
     *
     * @param sessionMark a marca da execucao ainda existe na sessao do Windows? ({@code null}: nao ha como saber)
     */
    static Verdict verdict(ProcessOwner owner, Path file, Function<String, Boolean> sessionMark, String name) {
        List<long[]> nodes = new ArrayList<>();
        if (owner != null && owner.startMillis() >= 0) nodes.add(new long[] {owner.pid(), owner.startMillis()});
        Set<String> facts = new HashSet<>();
        boolean readable = true;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
                readable = false;
            } else {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (FACTS.contains(line)) {
                        facts.add(line);
                        continue;
                    }
                    String[] p = line.split(" ");
                    if (p.length != 2) {
                        readable = false;
                        continue;
                    }
                    nodes.add(new long[] {Long.parseLong(p[0]), Long.parseLong(p[1])});
                }
            }
        } catch (IOException | RuntimeException e) {
            readable = false;
        }
        // a prova: o Windows disse que o grupo esta vazio (so vale se a supervisao nao falhou), ou a sessao dele acabou
        if (readable && facts.contains(EMPTY) && facts.contains(GROUP) && !facts.contains(BROKEN)) return Verdict.GONE;
        if (readable && facts.contains(MARK)) {
            Boolean still;
            try {
                still = sessionMark.apply(name);
            } catch (RuntimeException e) {
                still = null;
            }
            if (Boolean.FALSE.equals(still)) return Verdict.GONE;
        }
        for (long[] n : nodes) {
            try {
                Optional<ProcessHandle> h = ProcessHandle.of(n[0]).filter(ProcessHandle::isAlive);
                if (h.isEmpty() || n[1] < 0) continue;
                long start = h.get().info().startInstant().map(Instant::toEpochMilli).orElse(-1L);
                if (start == n[1]) return Verdict.ALIVE;
            } catch (RuntimeException e) {
                // sem resposta para este: nao prova nada
            }
        }
        return Verdict.UNCERTAIN;
    }
}
