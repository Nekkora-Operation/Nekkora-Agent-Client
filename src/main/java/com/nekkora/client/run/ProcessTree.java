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
 * <li>{@link Verdict#GONE}: ha {@link #EMPTY}; ou esta COMPROVADO que a sessao do Windows em que a execucao rodou acabou
 * (saida do usuario ou reinicio), e com ela todos os processos dela - a marca gravada e conferida no lancamento nao existe
 * mais, a tabela de marcas esta funcionando agora, e nenhum processo existente e daquela sessao desde antes do lancamento
 * ({@link Evidence}). Isso encerra a duvida sobre os processos; nao diz nada sobre o resultado da automacao;</li>
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
    /** Seguido do numero da sessao do Windows em que a execucao foi lancada. */
    static final String SESSION = "SESSAO";
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

    /** A sessao do Windows em que a execucao foi lancada (uma vez). */
    boolean session(int id) {
        String line = SESSION + " " + id;
        if (noted.contains(line)) return true;
        if (!append(line + "\n")) return false;
        noted.add(line);
        return true;
    }

    /** As evidencias sobre a SESSAO do Windows que hospedava a execucao. Todas tem de concordar para ela ser dada por encerrada. */
    interface Evidence {
        /** A marca da execucao ainda existe? {@code null}: sem resposta. */
        Boolean marked(String name);

        /** A tabela de marcas esta funcionando agora (grava, le e retira uma sonda)? */
        boolean working();

        /** A sessao {@code session}, que existia em {@code sinceMillis}, acabou - pelos processos que existem agora? {@code null}: nao da para saber. */
        Boolean sessionEnded(int session, long sinceMillis);
    }

    /** As evidencias do Windows de verdade. */
    static final Evidence WINDOWS = new Evidence() {
        @Override
        public Boolean marked(String name) {
            return WindowsProcessGroup.marked(name);
        }

        @Override
        public boolean working() {
            return WindowsProcessGroup.working();
        }

        @Override
        public Boolean sessionEnded(int session, long sinceMillis) {
            return WindowsProcessGroup.sessionEnded(session, sinceMillis);
        }
    };

    /** Ha prova de que nada desta execucao continua em andamento? */
    static Verdict verdict(ProcessOwner owner, Path file, Evidence evidence, String name) {
        List<long[]> nodes = new ArrayList<>();
        if (owner != null && owner.startMillis() >= 0) nodes.add(new long[] {owner.pid(), owner.startMillis()});
        Set<String> facts = new HashSet<>();
        int session = -1;
        boolean readable = true;
        try {
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024 * 1024) {
                readable = false;
            } else {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    if (line.startsWith(SESSION + " ")) {
                        session = Integer.parseInt(line.substring(SESSION.length() + 1));
                        continue;
                    }
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
        // a sessao do Windows que hospedava a execucao ACABOU: so com TODAS as evidencias de acordo. A falta da marca, sozinha, nao
        // basta - pode ser falha de leitura, remocao por outro programa ou gravacao que nao se completou
        if (readable && facts.contains(MARK) && session >= 0 && owner != null && owner.startMillis() > 0) {
            try {
                if (Boolean.FALSE.equals(evidence.marked(name)) && evidence.working()
                        && Boolean.TRUE.equals(evidence.sessionEnded(session, owner.startMillis()))) {
                    return Verdict.GONE;
                }
            } catch (RuntimeException e) {
                // sem resposta: nao prova nada
            }
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
