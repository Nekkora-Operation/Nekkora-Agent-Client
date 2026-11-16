package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001 - o registro minimo do que ficou sem confirmacao de recebimento e do que foi removido
 * pelo prazo de guarda: protegido no disco (protecao REAL do Windows), sem conteudo, com o token so enquanto ha aviso a dar.
 */
@EnabledOnOs(OS.WINDOWS)
class LossLedgerTest {

    private static final String A = "11111111-1111-4111-8111-111111111111";
    private static final String B = "22222222-2222-4222-8222-222222222222";
    private static final String TOKEN = "t".repeat(43);
    private static final long T0 = 1_800_000_000L;
    private static final long DAY = 86_400L;

    @TempDir
    Path tmp;

    private LossLedger open() {
        return new LossLedger(tmp.resolve("guarda"), new WindowsKeyring());
    }

    @Test
    void guardadoNaoEPerdidoEAPerdaSoExisteDepoisDaRemocaoPeloPrazo() throws Exception {
        LossLedger l = open();
        assertTrue(l.all(T0).isEmpty());
        l.logsHeld(A, TOKEN, 3, T0, 1008, LossLedger.Block.CONFLICT);
        l.resultsHeld(A, TOKEN, 3, T0, LossLedger.Block.CONFLICT);
        LossLedger.Entry held = l.load(A).orElseThrow();
        assertEquals(1008, held.logLines());
        assertFalse(held.logsLost() || held.resultsLost() || held.owes());
        // no disco: nada em claro
        String raw = new String(Files.readAllBytes(tmp.resolve("guarda").resolve(A + ".guarda")), StandardCharsets.ISO_8859_1);
        assertFalse(raw.contains(TOKEN) || raw.contains(A) || raw.contains("1008"), raw);
        // a fila sai pelo prazo; a pasta de saida ainda esta guardada
        l.logsLost(A, T0 + 7 * DAY);
        LossLedger.Entry one = open().load(A).orElseThrow();
        assertTrue(one.logsLost() && !one.resultsLost() && one.owes());
        assertEquals(TOKEN, one.token(), "enquanto ha aviso a dar, o token fica");
        // repetir a remocao nao muda a data; perda de tentativa sem registro nao cria nada
        l.logsLost(A, T0 + 9 * DAY);
        assertEquals(T0 + 7 * DAY, l.load(A).orElseThrow().logsLostAt());
        l.logsLost(B, T0);
        l.resultsLost(B, 5, T0);
        assertTrue(l.load(B).isEmpty());
        // a Platform aceita o aviso dos registros; ainda ha pasta guardada: o token fica
        l.noticed(A, true, LossLedger.Notice.SENT);
        assertEquals(TOKEN, l.load(A).orElseThrow().token());
        assertFalse(l.load(A).orElseThrow().owes());
        // a pasta de saida sai pelo prazo, com 4 arquivos; o aviso dela se mostra impossivel: o token sai
        l.resultsLost(A, 4, T0 + 8 * DAY);
        assertTrue(l.load(A).orElseThrow().owes());
        l.noticed(A, false, LossLedger.Notice.IMPOSSIBLE);
        LossLedger.Entry done = l.load(A).orElseThrow();
        assertEquals("", done.token());
        assertEquals(4, done.resultFiles());
        assertEquals(LossLedger.Notice.SENT, done.logsNotice());
        assertEquals(LossLedger.Notice.IMPOSSIBLE, done.resultsNotice());
        // o registro da perda fica pelo tempo previsto e depois sai
        assertEquals(List.of(done), l.all(T0 + 8 * DAY + LossLedger.KEEP_DAYS * DAY));
        assertTrue(l.all(T0 + 8 * DAY + LossLedger.KEEP_DAYS * DAY + 1).isEmpty());
        assertFalse(Files.exists(tmp.resolve("guarda").resolve(A + ".guarda")));
    }

    @Test
    void oQueFoiConfirmadoNaoDeixaRegistroEOVinculoEncerradoTiraOsTokensEDizQueNaoPodeMaisSerEnviado() throws Exception {
        LossLedger l = open();
        l.logsHeld(A, TOKEN, 1, T0, 10, LossLedger.Block.NONE);
        l.logsSettled(A);
        assertTrue(l.all(T0).isEmpty(), "nada guardado e nada perdido: sem registro");
        assertFalse(Files.exists(tmp.resolve("guarda").resolve(A + ".guarda")), "o token sai junto com o registro");
        // duas tentativas: uma so guardada (ainda sendo enviada), outra ja com perda sem aviso
        l.resultsHeld(A, TOKEN, 1, T0, LossLedger.Block.NONE);
        l.logsHeld(B, TOKEN, 2, T0 + 1, 7, LossLedger.Block.NONE);
        l.logsLost(B, T0 + 2);
        assertEquals(List.of(A, B), l.all(T0 + 3).stream().map(LossLedger.Entry::attemptId).toList());
        assertTrue(l.load(A).orElseThrow().deliverable());
        l.unlink(T0 + 3);
        // o vinculo acabou: o que estava guardado CONTINUA registrado, sem token, dito como impedido por autorizacao - nao e mais
        // enviado, e nao some em silencio. A perda fica, sem token e com o aviso impossivel
        List<LossLedger.Entry> after = l.all(T0 + 3);
        assertEquals(List.of(A, B), after.stream().map(LossLedger.Entry::attemptId).toList());
        LossLedger.Entry kept = after.get(0);
        assertEquals("", kept.token());
        assertTrue(kept.resultsHeld());
        assertEquals(LossLedger.Block.AUTHORIZATION, kept.resultsBlock());
        assertFalse(kept.deliverable(), "sem token e com impedimento: nada e tentado de novo");
        assertEquals("", after.get(1).token());
        assertEquals(LossLedger.Notice.IMPOSSIBLE, after.get(1).logsNotice());
        assertEquals(7, after.get(1).logLines());
        // quando o prazo remover o que ficou guardado sem token, a perda nasce com o aviso impossivel (nao ha como avisar)
        l.resultsLost(A, 3, T0 + 7 * DAY);
        LossLedger.Entry gone = l.load(A).orElseThrow();
        assertTrue(gone.resultsLost());
        assertEquals(3, gone.resultFiles());
        assertEquals(LossLedger.Notice.IMPOSSIBLE, gone.resultsNotice());
        assertFalse(gone.owes());
        // arquivo ilegivel (outro usuario, cortado) nao e um registro e nao derruba a leitura
        Files.write(tmp.resolve("guarda").resolve(A + ".guarda"), new byte[] {1, 2, 3});
        assertEquals(1, l.all(T0 + 7 * DAY).size());
    }

    @Test
    void aEntregaContinuadaTemTresFinsConfirmacaoImpedimentoComMotivoOuPrazo() throws Exception {
        LossLedger l = open();
        String run = "20261116-101500-abcdef";
        // a tentativa terminou: registros e pasta de saida entram na entrega, sem impedimento; o prazo conta daqui
        l.enqueue(A, TOKEN, 4, run, T0);
        LossLedger.Entry e = l.load(A).orElseThrow();
        assertTrue(e.deliverable() && e.logsHeld() && e.resultsHeld());
        assertEquals(run, e.run());
        assertEquals(LossLedger.Block.NONE, e.logsBlock());
        assertEquals(T0, e.notedAt());
        // repetir (queda entre o registro e a limpeza do diario) nao muda a data nem o que ja foi dito
        l.logsHeld(A, TOKEN, 4, T0 + 50, 12, LossLedger.Block.CONFLICT);
        l.enqueue(A, TOKEN, 4, run, T0 + 100);
        e = l.load(A).orElseThrow();
        assertEquals(T0, e.notedAt());
        assertEquals(LossLedger.Block.CONFLICT, e.logsBlock());
        assertEquals(12, e.logLines());
        assertTrue(e.deliverable(), "os resultados continuam sem impedimento");
        // fim 1 - confirmacao: os resultados saem; ficam os registros, impedidos
        l.resultsSettled(A);
        e = l.load(A).orElseThrow();
        assertFalse(e.resultsHeld());
        assertFalse(e.deliverable());
        assertEquals(TOKEN, e.token(), "o token fica enquanto pode haver perda a avisar");
        // fim 2 - impedimento com motivo: fica dito, nao ha novo envio
        l.enqueue(B, TOKEN, 1, null, T0);
        assertFalse(l.load(B).orElseThrow().resultsHeld(), "tentativa sem execucao nao tem pasta de saida");
        l.blocked(B, LossLedger.Block.AUTHORIZATION);
        assertEquals(LossLedger.Block.AUTHORIZATION, l.load(B).orElseThrow().logsBlock());
        assertFalse(l.load(B).orElseThrow().deliverable());
        // fim 3 - prazo: a perda fica registrada, com aviso a dar
        l.logsLost(B, T0 + 7 * DAY);
        assertTrue(l.load(B).orElseThrow().owes());
        // confirmado tudo: nao sobra registro
        l.logsSettled(A);
        assertTrue(l.load(A).isEmpty());
        // um registro do formato anterior (antes da entrega continuada) continua legivel: guardado, sem novo envio
        String old = String.join("\t", "v1", A, TOKEN, "2", String.valueOf(T0), "5", "0", "PENDING", "1", "0", "PENDING");
        Files.write(tmp.resolve("guarda").resolve(A + ".guarda"), new WindowsKeyring().protect(old.getBytes(StandardCharsets.UTF_8)));
        LossLedger.Entry v1 = open().load(A).orElseThrow();
        assertTrue(v1.logsHeld() && v1.resultsHeld());
        assertEquals(LossLedger.Block.CONFLICT, v1.logsBlock());
        assertFalse(v1.deliverable());
    }
}
