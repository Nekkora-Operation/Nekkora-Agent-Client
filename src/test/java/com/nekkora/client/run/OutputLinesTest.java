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

    /** Entrega {@code data} em pedacos do tamanho pedido: cada {@code read} devolve no maximo {@code chunk} bytes. */
    private static java.io.InputStream chunks(byte[] data, int chunk) {
        return new java.io.InputStream() {
            private int at;

            @Override
            public int read() {
                return at < data.length ? data[at++] & 0xff : -1;
            }

            @Override
            public int read(byte[] b, int off, int len) {
                if (at >= data.length) return -1;
                int n = Math.min(Math.min(len, chunk), data.length - at);
                System.arraycopy(data, at, b, off, n);
                at += n;
                return n;
            }
        };
    }

    @Test
    void caractereDivididoEntreLeiturasChegaInteiroEmQualquerPontoDeCorte() {
        // 2 bytes (ç, ã), 3 bytes (€, ✓) e 4 bytes (😀) por caractere, em duas linhas e com fim de linha do Windows
        String first = "ação nº 1 custa 10 € ✓ 😀";
        String second = "coração à mão 😀😀";
        byte[] data = (first + "\r\n" + second + "\n").getBytes(StandardCharsets.UTF_8);
        for (int chunk = 1; chunk <= 7; chunk++) {
            List<Seen> seen = new ArrayList<>();
            AtomicLong bytes = new AtomicLong();
            OutputLines.pump(chunks(data, chunk), false, (e, at, cut, text) -> seen.add(new Seen(e, cut, text)), bytes, Clock.systemUTC());
            assertEquals(List.of(new Seen(false, false, first), new Seen(false, false, second)), seen, "pedacos de " + chunk + " byte(s)");
            assertEquals(data.length, bytes.get());
        }
        // o mesmo na pagina de codigo do sistema (um byte por caractere acentuado, quando ela os tem): nada se perde nem se junta
        if (OutputLines.NATIVE.newEncoder().canEncode("ação à mão") && !OutputLines.NATIVE.equals(StandardCharsets.UTF_8)) {
            byte[] local = "ação à mão\r\nsegunda\n".getBytes(OutputLines.NATIVE);
            List<Seen> seen = new ArrayList<>();
            OutputLines.pump(chunks(local, 1), false, (e, at, cut, text) -> seen.add(new Seen(e, cut, text)), new AtomicLong(), Clock.systemUTC());
            assertEquals(List.of(new Seen(false, false, "ação à mão"), new Seen(false, false, "segunda")), seen);
        }
    }

    @Test
    void aDecisaoDeCodificacaoEPorLinhaInteiraEUmaLinhaNaoContaminaAOutra() {
        // linha 1: UTF-8 valido. linha 2: UTF-8 valido seguido de um byte que nao e UTF-8. linha 3: UTF-8 valido de novo.
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.writeBytes("válida\n".getBytes(StandardCharsets.UTF_8));
        byte[] mixed = concat("é".getBytes(StandardCharsets.UTF_8), new byte[] {(byte) 0xff});
        data.writeBytes(mixed);
        data.write('\n');
        data.writeBytes("também válida\n".getBytes(StandardCharsets.UTF_8));
        // sequencias UTF-8 malformadas: continuacao solta, inicio sem continuacao, forma longa demais, substituto codificado
        byte[][] malformed = {{(byte) 0x80}, {(byte) 0xc3}, {(byte) 0xc0, (byte) 0xaf}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}, {(byte) 0xf0, (byte) 0x9f, (byte) 0x98}};
        for (byte[] m : malformed) {
            data.writeBytes(m);
            data.write('\n');
        }
        List<Seen> seen = pump(data.toByteArray(), false, new AtomicLong());
        assertEquals(3 + malformed.length, seen.size());
        assertEquals("válida", seen.get(0).text());
        // a linha com byte invalido e lida INTEIRA na pagina de codigo do sistema: o "é" em UTF-8 vira dois caracteres dela
        assertEquals(OutputLines.clean(new String(mixed, OutputLines.NATIVE)), seen.get(1).text());
        assertEquals("também válida", seen.get(2).text(), "a linha seguinte volta a ser lida como UTF-8");
        for (int i = 0; i < malformed.length; i++) {
            Seen s = seen.get(3 + i);
            assertEquals(OutputLines.clean(new String(malformed[i], OutputLines.NATIVE)), s.text(), "malformada " + i);
            assertFalse(s.truncated());
            // nunca lanca, nunca some: toda linha lida vira uma linha de texto, sem caractere de controle
            assertTrue(s.text().chars().noneMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f), s.text());
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
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
