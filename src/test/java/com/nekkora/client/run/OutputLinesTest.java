package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - a saida do processo lida linha a linha (funcionalidade nova). */
class OutputLinesTest {

    private record Seen(boolean error, boolean truncated, String text) {
    }

    private static List<Seen> pump(byte[] data, boolean error, AtomicLong bytes) {
        List<Seen> seen = new ArrayList<>();
        OutputLines.pump(new ByteArrayInputStream(data), error, (e, at, cut, text) -> seen.add(new Seen(e, cut, text)), bytes, Clock.systemUTC());
        return seen;
    }

    @Test
    void linhasPorQuebraDeLinhaComAcentoSemRetornoDeCarroEAUltimaSemQuebra() {
        AtomicLong bytes = new AtomicLong();
        byte[] data = "primeira\r\nsegunda com acentuação\n\nprogresso 10%\rprogresso 100%\nsem quebra no fim".getBytes(StandardCharsets.UTF_8);
        List<Seen> seen = pump(data, false, bytes);
        assertEquals(List.of("primeira", "segunda com acentuação", "", "progresso 10%progresso 100%", "sem quebra no fim"), seen.stream().map(Seen::text).toList());
        assertTrue(seen.stream().noneMatch(Seen::truncated));
        assertTrue(seen.stream().noneMatch(Seen::error));
        assertEquals(data.length, bytes.get(), "todos os bytes foram lidos");
        assertTrue(pump("x\n".getBytes(StandardCharsets.UTF_8), true, new AtomicLong()).get(0).error(), "o canal e o do fluxo, nao do texto");
    }

    @Test
    void linhaMaiorQueOLimiteECortadaMarcadaEORestoDelaELidoSemTravar() throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write("a".repeat(OutputLines.MAX_LINE_BYTES + 5000).getBytes(StandardCharsets.UTF_8));
        data.write('\n');
        data.write("depois\n".getBytes(StandardCharsets.UTF_8));
        // acentos: 2 bytes cada; o corte nao parte um caractere
        data.write("é".repeat(OutputLines.MAX_LINE_BYTES).getBytes(StandardCharsets.UTF_8));
        data.write('\n');
        AtomicLong bytes = new AtomicLong();
        List<Seen> seen = pump(data.toByteArray(), false, bytes);
        assertEquals(3, seen.size());
        assertTrue(seen.get(0).truncated());
        assertEquals(OutputLines.MAX_LINE_BYTES, seen.get(0).text().length());
        assertEquals(new Seen(false, false, "depois"), seen.get(1), "a linha seguinte chega inteira");
        assertTrue(seen.get(2).truncated());
        assertEquals(OutputLines.MAX_LINE_BYTES, seen.get(2).text().getBytes(StandardCharsets.UTF_8).length);
        assertFalse(seen.get(2).text().contains("\uFFFD"), "nenhum caractere partido");
        assertEquals(data.size(), bytes.get(), "o excedente foi lido (o processo nao trava)");
    }

    @Test
    void bytesInvalidosECaracteresDeControleViramSubstituicaoETabulacaoFica() {
        byte[] data = {'o', 'k', (byte) 0xff, '\t', 0x07, 0x1b, '[', '3', '1', 'm', 0x00, 0x7f, '\n'};
        List<Seen> seen = pump(data, false, new AtomicLong());
        assertEquals(1, seen.size());
        // 0xff nao e UTF-8: a linha e lida na pagina de codigo do sistema; os caracteres de controle viram substituicao
        String ff = new String(new byte[] {(byte) 0xff}, OutputLines.NATIVE);
        assertEquals("ok" + ff + "\t\uFFFD\uFFFD[31m\uFFFD\uFFFD", seen.get(0).text());
        // o Python escreve, por padrao, na pagina de codigo do Windows: os acentos chegam certos sem o script fazer nada
        if (OutputLines.NATIVE.newEncoder().canEncode("ação")) {
            assertEquals("acentuação", pump("acentuação\n".getBytes(OutputLines.NATIVE), false, new AtomicLong()).get(0).text());
        }
        // linha UTF-8 cortada no meio de um caractere: o pedaco partido sai, o resto continua UTF-8
        byte[] cut = ("a" + "é".repeat(OutputLines.MAX_LINE_BYTES)).getBytes(StandardCharsets.UTF_8);
        Seen partial = pump(cut, false, new AtomicLong()).get(0);
        assertTrue(partial.truncated());
        assertEquals("a" + "é".repeat((OutputLines.MAX_LINE_BYTES - 1) / 2), partial.text());
        // muitos bytes invalidos: cada um vira 3 bytes em UTF-8; a linha continua dentro do limite, e marcada como cortada
        byte[] bad = new byte[OutputLines.MAX_LINE_BYTES];
        java.util.Arrays.fill(bad, (byte) 0xff);
        Seen big = pump(bad, false, new AtomicLong()).get(0);
        assertTrue(big.truncated());
        assertTrue(big.text().getBytes(StandardCharsets.UTF_8).length <= OutputLines.MAX_LINE_BYTES);
    }

    @Test
    void semQuemRecebaASaidaELidaEDesprezadaEQuemRecebeFalhandoNaoParaALeitura() {
        AtomicLong bytes = new AtomicLong();
        OutputLines.pump(new ByteArrayInputStream("um\ndois\n".getBytes(StandardCharsets.UTF_8)), false, null, bytes, Clock.systemUTC());
        assertEquals(8, bytes.get());
        List<String> after = new ArrayList<>();
        AtomicLong read = new AtomicLong();
        OutputLines.pump(new ByteArrayInputStream("um\ndois\ntres\n".getBytes(StandardCharsets.UTF_8)), false, (e, at, cut, text) -> {
            if (text.equals("um")) throw new IllegalStateException("quem recebe falhou");
            after.add(text);
        }, read, Clock.systemUTC());
        assertEquals(List.of("dois", "tres"), after);
        assertEquals(13, read.get());
    }
}
