package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O que conta como PROVA de que nada de uma execucao gerenciada continua em andamento (decisoes de 16/11): o Windows dizer que
 * o grupo dela esta vazio, ou a sessao do Windows em que ela rodou ter acabado. Sem isso nada e afirmado, mesmo que nenhum
 * processo anotado esteja vivo. Aqui, sem lancar nada: o "processo vivo" e o do proprio teste.
 */
class ProcessTreeTest {
    @TempDir
    Path tmp;

    private static final long DEAD = 4_000_000_003L;

    /** Evidencias simuladas: a marca existe?; a tabela de marcas funciona?; a sessao acabou (pelos processos)? */
    private static ProcessTree.Evidence ev(Boolean marked, boolean working, Boolean ended) {
        return new ProcessTree.Evidence() {
            @Override
            public Boolean marked(String name) {
                return marked;
            }

            @Override
            public boolean working() {
                return working;
            }

            @Override
            public Boolean sessionEnded(int session, long sinceMillis) {
                return ended;
            }
        };
    }

    private static final ProcessTree.Evidence MARCA_EXISTE = ev(true, true, false);
    /** A marca sumiu, a tabela funciona e nenhum processo e daquela sessao: a sessao acabou. */
    private static final ProcessTree.Evidence SESSAO_ENCERRADA = ev(false, true, true);

    private static final String N = "\n";

    private static long ownStart() {
        return ProcessHandle.current().info().startInstant().map(Instant::toEpochMilli).orElseThrow();
    }

    private Path file(String text) throws Exception {
        Path f = tmp.resolve(ProcessTree.FILE);
        Files.writeString(f, text, StandardCharsets.UTF_8);
        return f;
    }

    private static ProcessOwner dead() {
        return new ProcessOwner(DEAD, 1_000L, ProcessOwner.digest("C:\\x\\python.exe"));
    }

    private ProcessTree.Verdict v(String text, ProcessTree.Evidence mark) throws Exception {
        return ProcessTree.verdict(dead(), file(text), mark, "NekkoraClient-teste");
    }

    @Test
    void aProvaEOGrupoVazioDitoPeloWindows() throws Exception {
        assertEquals(ProcessTree.Verdict.GONE, v("GRUPO\nMARCA\n" + (DEAD - 4) + " 2000\nFIM\nVAZIO\n", MARCA_EXISTE));
        // o grupo vazio decide mesmo com um PID anotado hoje ocupado por OUTRO programa (reaproveitado): a contagem e do Windows
        long me = ProcessHandle.current().pid();
        assertEquals(ProcessTree.Verdict.GONE, v("GRUPO\n" + me + " " + (ownStart() - 60_000) + "\nFIM\nVAZIO\n", MARCA_EXISTE));
        // "vazio" de um grupo que falhou ou nunca recebeu o processo principal nao prova nada
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nFALHA\nFIM\nVAZIO\n", MARCA_EXISTE));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("FIM\nVAZIO\n", MARCA_EXISTE));
    }

    /**
     * Decisao de 16/11: o fim da sessao do Windows que hospedava a execucao encerra a duvida sobre os processos - desde que
     * COMPROVADO. A falta da marca, sozinha, nao libera: pode ser falha de leitura, remocao ou gravacao incompleta.
     */
    @Test
    void oFimDaSessaoSoContaComTodasAsEvidenciasDeAcordo() throws Exception {
        String lancada = "GRUPO" + N + "SESSAO 1" + N + "MARCA" + N + (DEAD - 4) + " 2000" + N;
        assertEquals(ProcessTree.Verdict.GONE, v(lancada, SESSAO_ENCERRADA));
        assertEquals(ProcessTree.Verdict.GONE, v("FALHA" + N + "SESSAO 1" + N + "MARCA" + N, SESSAO_ENCERRADA));
        // a marca sumiu, mas ha processo daquela sessao desde antes do lancamento: a sessao e a MESMA - a marca foi removida por
        // outro programa. Nada e liberado
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v(lancada, ev(false, true, false)));
        // a marca "sumiu" e a tabela de marcas nao funciona agora: e falha de leitura
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v(lancada, ev(false, false, true)));
        // o Windows nao respondeu sobre a marca, ou nao da para saber da sessao pelos processos
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v(lancada, ev(null, true, true)));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v(lancada, ev(false, true, null)));
        // inicializacao incompleta: a marca nunca foi gravada e conferida, ou a sessao do lancamento nao foi anotada
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO" + N + "SESSAO 1" + N + (DEAD - 4) + " 2000" + N, SESSAO_ENCERRADA));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO" + N + "MARCA" + N + (DEAD - 4) + " 2000" + N, SESSAO_ENCERRADA));
        // a marca existe: a sessao continua
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v(lancada, MARCA_EXISTE));
    }

    @Test
    void semProvaNadaEAfirmadoMesmoSemNenhumProcessoAnotadoVivo() throws Exception {
        // fim visto e nenhum anotado vivo, mas sem o grupo vazio: um descendente de processo ja terminado nao aparece em lista nenhuma
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n" + (DEAD - 4) + " 2000\nFIM\n", MARCA_EXISTE));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("", MARCA_EXISTE));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), tmp.resolve("nao-existe.txt"), SESSAO_ENCERRADA, "x"));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(null, file("FIM\n"), MARCA_EXISTE, "x"));
        // arquivo cortado ou alterado: nem "vazio" nem a marca valem
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nSESSAO 1\nMARCA\n123\nFIM\nVAZIO\n", SESSAO_ENCERRADA));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nabc 1\nVAZIO\n", MARCA_EXISTE));
    }

    @Test
    void umProcessoAnotadoVivoDizQueHaAlgoEmAndamento() throws Exception {
        long me = ProcessHandle.current().pid();
        assertEquals(ProcessTree.Verdict.ALIVE, v("GRUPO\nMARCA\n" + me + " " + ownStart() + "\nFIM\n", MARCA_EXISTE));
        ProcessOwner alive = new ProcessOwner(me, ownStart(), ProcessOwner.digest("C:\\x\\python.exe"));
        assertEquals(ProcessTree.Verdict.ALIVE, ProcessTree.verdict(alive, file("GRUPO\nFIM\n"), MARCA_EXISTE, "x"));
        // mesmo PID, outro inicio: e outro programa
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n" + me + " " + (ownStart() - 60_000) + "\nFIM\n", MARCA_EXISTE));
    }
}
