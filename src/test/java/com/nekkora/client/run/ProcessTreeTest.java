package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.function.Function;

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
    private static final Function<String, Boolean> MARCA_EXISTE = n -> true;
    private static final Function<String, Boolean> MARCA_SUMIU = n -> false;
    private static final Function<String, Boolean> SEM_RESPOSTA = n -> null;

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

    private ProcessTree.Verdict v(String text, Function<String, Boolean> mark) throws Exception {
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

    @Test
    void aSessaoDoWindowsEncerradaAcabaComADuvidaSobreOsProcessos() throws Exception {
        // o aplicativo estava fechado quando o processo principal terminou: sem "vazio"; a marca da sessao sumiu = sessao encerrada
        assertEquals(ProcessTree.Verdict.GONE, v("GRUPO\nMARCA\n" + (DEAD - 4) + " 2000\n", MARCA_SUMIU));
        assertEquals(ProcessTree.Verdict.GONE, v("FALHA\nMARCA\n", MARCA_SUMIU));
        // a marca nunca foi gravada: a falta dela nao prova nada; o Windows nao respondeu: tambem nao
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\n" + (DEAD - 4) + " 2000\n", MARCA_SUMIU));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n", SEM_RESPOSTA));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n", MARCA_EXISTE));
    }

    @Test
    void semProvaNadaEAfirmadoMesmoSemNenhumProcessoAnotadoVivo() throws Exception {
        // fim visto e nenhum anotado vivo, mas sem o grupo vazio: um descendente de processo ja terminado nao aparece em lista nenhuma
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n" + (DEAD - 4) + " 2000\nFIM\n", MARCA_EXISTE));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("", MARCA_EXISTE));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), tmp.resolve("nao-existe.txt"), MARCA_SUMIU, "x"));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(null, file("FIM\n"), MARCA_EXISTE, "x"));
        // arquivo cortado ou alterado: nem "vazio" nem a marca valem
        assertEquals(ProcessTree.Verdict.UNCERTAIN, v("GRUPO\nMARCA\n123\nFIM\nVAZIO\n", MARCA_SUMIU));
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
