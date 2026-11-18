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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - a fila local dos registros de uma tentativa: protegida no disco (protecao REAL do
 * Windows), sem segredos, com limite que nao para em silencio, e que sobrevive ao fechamento do aplicativo.
 */
@EnabledOnOs(OS.WINDOWS)
class LogQueueTest {

    private static final String CREDENTIAL = "nka1." + "c".repeat(60);
    private static final String TOKEN = "t".repeat(43);

    @TempDir
    Path tmp;

    private LogQueue open() {
        return new LogQueue(tmp.resolve("fila").resolve("11111111-1111-4111-8111-111111111111.fila"), new WindowsKeyring(), List.of(CREDENTIAL, TOKEN));
    }

    private Path file() {
        return tmp.resolve("fila").resolve("11111111-1111-4111-8111-111111111111.fila");
    }

    @Test
    void oQueVaiParaODiscoEProtegidoESemSegredosEVoltaQuandoOAplicativoReabre() throws Exception {
        LogQueue q = open();
        assertFalse(q.existed());
        q.add(false, 1_000, false, "linha de negocio com dado reconhecivel 12345");
        q.add(true, 1_001, false, "falhou: Authorization: Bearer abcdefghijklmnop e depois");
        q.add(false, 1_002, true, "credencial " + CREDENTIAL + " e token " + TOKEN);
        q.add(false, 1_003, false, "codigo nkp1.org.enr." + "B".repeat(43) + " fim");
        q.note(1_004, "aviso do aplicativo");
        q.flush();
        // no disco: nada legivel - nem o texto do robo, nem segredo
        String raw = new String(LogQueue.raw(file()), StandardCharsets.ISO_8859_1);
        for (String plain : List.of("dado reconhecivel", "12345", "abcdefghijklmnop", CREDENTIAL, TOKEN, "nkp1.", "aviso do aplicativo")) {
            assertFalse(raw.contains(plain), "em claro no disco: " + plain);
        }
        // o que sai para a Platform: os segredos ja foram removidos antes de entrar na fila
        List<LogQueue.Line> batch = q.batch();
        assertEquals(List.of(1, 2, 3, 4, 5), batch.stream().map(LogQueue.Line::sequence).toList());
        assertEquals(List.of("STDOUT", "STDERR", "STDOUT", "STDOUT", "EXECUTOR"), batch.stream().map(LogQueue.Line::channel).toList());
        assertEquals("linha de negocio com dado reconhecivel 12345", batch.get(0).message());
        assertEquals("falhou: Authorization: [removido] e depois", batch.get(1).message());
        assertEquals("credencial [removido] e token [removido]", batch.get(2).message());
        assertTrue(batch.get(2).truncated());
        assertEquals("codigo [removido] fim", batch.get(3).message());
        // a Platform remove de novo ao receber: repetir a remocao nao pode mudar o que ja saiu limpo daqui
        for (LogQueue.Line l : batch) {
            assertEquals(l.message(), q.redact(l.message()), "remocao repetida");
        }
        assertEquals("cabecalho Authorization: [removido] e o resto", q.redact(q.redact("cabecalho Authorization: Bearer abcdefghijklmnop e o resto")));
        // os mesmos vetores do arquivo da execucao local: as duas remocoes tem de dar o mesmo resultado
        for (com.nekkora.client.RedactionVectors.Vector v : com.nekkora.client.RedactionVectors.ALL) {
            assertEquals(v.out(), q.redact(v.in()), v.in());
        }
        // o aplicativo fecha e abre: a fila volta do disco, na mesma ordem, e continua a sequencia
        LogQueue again = open();
        assertTrue(again.existed());
        assertEquals(5, again.lines());
        assertEquals(batch, again.batch());
        again.add(false, 2_000, false, "depois de reabrir");
        assertEquals(6, again.lines());
        // confirmado pela Platform: sai da memoria; o lote seguinte comeca depois
        again.acked(5);
        assertEquals(List.of(6), again.batch().stream().map(LogQueue.Line::sequence).toList());
        assertFalse(again.drained());
        again.acked(6);
        assertTrue(again.drained());
        again.delete();
        assertFalse(Files.exists(file()));
    }

    @Test
    void oLimiteNaoParaEmSilencioOAvisoOcupaAUltimaVagaEOExcedenteEContado() throws Exception {
        LogQueue q = open();
        for (int i = 1; i <= LogQueue.MAX_LINES + 100; i++) {
            q.add(i % 2 == 0, i, false, "linha " + i);
        }
        assertEquals(LogQueue.MAX_LINES, q.lines());
        assertEquals(101, q.dropped(), "o que nao coube e contado");
        // os lotes respeitam 200 linhas; a ultima linha da fila e o aviso do aplicativo
        int seen = 0;
        LogQueue.Line last = null;
        for (List<LogQueue.Line> b = q.batch(); !b.isEmpty(); b = q.batch()) {
            assertTrue(b.size() <= LogQueue.BATCH_LINES);
            seen += b.size();
            last = b.get(b.size() - 1);
            q.acked(last.sequence());
        }
        assertEquals(LogQueue.MAX_LINES, seen);
        assertEquals("EXECUTOR", last.channel());
        assertEquals(LogQueue.LIMIT_NOTICE, last.message());
        assertEquals(LogQueue.MAX_LINES, last.sequence());
        // reaberta: a contagem do descartado nao se perde
        q.flush();
        LogQueue again = open();
        assertEquals(LogQueue.MAX_LINES, again.lines());
        assertEquals(101, again.dropped());
        again.add(false, 9, false, "mais uma");
        assertEquals(102, again.dropped());
        assertEquals(LogQueue.MAX_LINES, again.lines());
    }

    @Test
    void oLimiteDeTamanhoTambemValeEOLoteRespeitaOTamanho() {
        LogQueue q = open();
        String big = "x".repeat(8 * 1024);
        int accepted = 0;
        while (q.dropped() == 0) {
            q.add(false, accepted, false, big);
            accepted++;
        }
        // 20 MiB de linhas de 8 KiB, menos a folga do aviso: 2.559 linhas e o aviso
        assertEquals(2560, q.lines());
        assertEquals(1, q.dropped());
        List<LogQueue.Line> b = q.batch();
        assertEquals(32, b.size(), "256 KiB por lote");
        assertTrue(b.stream().mapToLong(l -> l.message().length()).sum() <= LogQueue.BATCH_BYTES);
    }

    @Test
    void quandoAPlatformNaoAceitaMaisOQueFaltavaContaComoDescartado() {
        LogQueue q = open();
        for (int i = 1; i <= 10; i++) {
            q.add(false, i, false, "linha " + i);
        }
        q.acked(4);
        q.refusedFrom(5);
        assertEquals(4, q.lines());
        assertEquals(6, q.dropped());
        assertTrue(q.drained());
        q.add(false, 11, false, "depois");
        assertEquals(7, q.dropped());
        assertEquals(4, q.lines());
    }
}
