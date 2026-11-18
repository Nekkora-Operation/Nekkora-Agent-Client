package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A conferencia da arvore de processos de uma execucao (decisao de 16/11: confirmar que o processo terminou considera os filhos;
 * com processo vivo ou identificacao incerta, nada e afirmado). Aqui, sem lancar nada: o "processo vivo" e o do proprio teste.
 */
class ProcessTreeTest {
    @TempDir
    Path tmp;

    /** Um PID que nao existe agora (o maior possivel no Windows e multiplo de 4; este nunca e usado). */
    private static final long DEAD = 4_000_000_003L;

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

    @Test
    void soEAusenteComOFimVistoENadaVivo() throws Exception {
        assertEquals(ProcessTree.Verdict.GONE, ProcessTree.verdict(dead(), file("FIM\n")));
        assertEquals(ProcessTree.Verdict.GONE, ProcessTree.verdict(dead(), file((DEAD - 4) + " 2000\n" + (DEAD - 8) + " 3000\nFIM\n")));
        // um PID anotado que hoje e de OUTRO processo (outro inicio): reaproveitado, nao e desta arvore
        long me = ProcessHandle.current().pid();
        assertEquals(ProcessTree.Verdict.GONE, ProcessTree.verdict(dead(), file(me + " " + (ownStart() - 60_000) + "\nFIM\n")));
    }

    @Test
    void umDescendenteAnotadoVivoMantemAArvoreEmAndamentoMesmoComOPrincipalTerminado() throws Exception {
        long me = ProcessHandle.current().pid();
        assertEquals(ProcessTree.Verdict.ALIVE, ProcessTree.verdict(dead(), file(me + " " + ownStart() + "\nFIM\n")));
        // o proprio processo principal vivo
        ProcessOwner alive = new ProcessOwner(me, ownStart(), ProcessOwner.digest("C:\\x\\python.exe"));
        assertEquals(ProcessTree.Verdict.ALIVE, ProcessTree.verdict(alive, file("FIM\n")));
    }

    @Test
    void semProvaNadaEAfirmado() throws Exception {
        // o fim nao foi visto pelo acompanhamento (o aplicativo estava fechado quando o processo terminou)
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), file("")));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), file((DEAD - 4) + " 2000\n")));
        // sem o arquivo (execucao de uma versao anterior do aplicativo), sem posse, posse sem instante de inicio
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), tmp.resolve("nao-existe.txt")));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(null, file("FIM\n")));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(new ProcessOwner(DEAD, -1, ProcessOwner.digest("C:\\x\\python.exe")), file("FIM\n")));
        // arquivo cortado ou alterado
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), file("123\nFIM\n")));
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), file("abc 1\nFIM\n")));
        // um PID anotado esta vivo e o inicio dele nao foi anotado: nao da para saber se e o mesmo
        assertEquals(ProcessTree.Verdict.UNCERTAIN, ProcessTree.verdict(dead(), file(ProcessHandle.current().pid() + " -1\nFIM\n")));
        // vivo vence incerto: se ha prova de processo em andamento, e isso que se diz
        long me = ProcessHandle.current().pid();
        assertEquals(ProcessTree.Verdict.ALIVE, ProcessTree.verdict(dead(), file(me + " " + ownStart() + "\n")));
    }
}
