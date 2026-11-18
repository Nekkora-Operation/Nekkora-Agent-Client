package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.RedactionVectors;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - o arquivo de texto com a saida do processo de uma execucao iniciada neste
 * computador: formato, canais, linha cortada, segredos removidos, limite que avisa e conta, e falha de disco que nao para nada.
 */
class OutputTextTest {

    @TempDir
    Path tmp;

    private static final long T0 = 1_763_300_000_000L; // 2025-11-16T13:33:20Z

    @Test
    void cadaLinhaTemInstanteCanalETextoEOsSegredosNaoVaoParaODisco() throws Exception {
        Path file = tmp.resolve("saida.txt");
        OutputText text = new OutputText(file, ZoneOffset.UTC);
        text.open();
        text.line(false, T0, false, "primeira linha com acentuação");
        text.line(true, T0 + 1, false, "aviso na saída de erro");
        text.line(false, T0 + 2, true, "linha longa");
        text.line(false, T0 + 3, false, "cred nka1." + "A".repeat(20) + " e Authorization: Bearer abcdefghijklmnop fim");
        text.line(false, T0 + 4, false, null);
        text.close(T0 + 5);
        text.line(false, T0 + 6, false, "depois de fechado nao entra");
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertEquals(List.of(OutputText.HEADER,
                "2025-11-16 13:33:20.000 saída      | primeira linha com acentuação",
                "2025-11-16 13:33:20.001 erro       | aviso na saída de erro",
                "2025-11-16 13:33:20.002 saída      | linha longa" + OutputText.CUT,
                "2025-11-16 13:33:20.003 saída      | cred [removido] e Authorization: [removido] fim",
                "2025-11-16 13:33:20.004 saída      | "), rows);
        assertEquals(5, text.lines());
        assertEquals(0, text.left());
        for (RedactionVectors.Vector v : RedactionVectors.ALL) {
            assertEquals(v.out(), OutputText.redact(v.in()), v.in());
            assertEquals(v.out(), OutputText.redact(OutputText.redact(v.in())), "repetir nao muda: " + v.in());
        }
    }

    @Test
    void oLimiteDeLinhasAvisaUmaVezContaOQueFicouDeForaEDizNoFim() throws Exception {
        Path file = tmp.resolve("saida.txt");
        OutputText text = new OutputText(file, ZoneOffset.UTC);
        text.open();
        for (int i = 1; i <= OutputText.MAX_LINES + 100; i++) {
            text.line(i % 2 == 0, T0 + i, false, "linha " + i);
        }
        text.close(T0 + 100_000);
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        // cabecalho + (limite - 1) linhas do processo + o aviso + o total nao guardado
        assertEquals(1 + (OutputText.MAX_LINES - 1) + 1 + 1, rows.size());
        assertEquals(OutputText.MAX_LINES - 1, text.lines());
        assertEquals(101, text.left());
        assertTrue(rows.get(rows.size() - 2).endsWith("aplicativo | " + OutputText.LIMIT_NOTICE), rows.get(rows.size() - 2));
        assertTrue(rows.get(rows.size() - 1).endsWith("aplicativo | 101 linhas do processo não foram guardadas neste arquivo."), rows.get(rows.size() - 1));
        assertEquals(1, rows.stream().filter(l -> l.contains(OutputText.LIMIT_NOTICE)).count(), "o aviso aparece uma vez so");
    }

    @Test
    void oLimiteDeTamanhoTambemVale() throws Exception {
        Path file = tmp.resolve("saida.txt");
        OutputText text = new OutputText(file, ZoneOffset.UTC);
        text.open();
        String big = "x".repeat(8 * 1024);
        for (int i = 0; i < 2700; i++) {
            text.line(false, T0, false, big);
        }
        text.close(T0);
        assertTrue(Files.size(file) <= OutputText.MAX_BYTES, String.valueOf(Files.size(file)));
        assertTrue(text.left() > 0);
        assertEquals(2700, text.lines() + text.left());
        List<String> rows = Files.readAllLines(file, StandardCharsets.UTF_8);
        assertTrue(rows.get(rows.size() - 1).contains("linhas do processo não foram guardadas"));
    }

    @Test
    void semConseguirCriarOArquivoAsLinhasSaoSoContadasENadaLanca() throws Exception {
        // ja existe um arquivo com este nome: a criacao exclusiva falha, e nada dele e sobrescrito
        Path file = tmp.resolve("saida.txt");
        Files.writeString(file, "conteudo anterior", StandardCharsets.UTF_8);
        OutputText text = new OutputText(file, ZoneOffset.UTC);
        text.open();
        text.line(false, T0, false, "um");
        text.line(true, T0, false, "dois");
        text.close(T0);
        assertEquals("conteudo anterior", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(0, text.lines());
        assertEquals(2, text.left());
        // pasta que nao existe: idem
        OutputText nowhere = new OutputText(tmp.resolve("nao-existe").resolve("saida.txt"), ZoneOffset.UTC);
        nowhere.open();
        nowhere.line(false, T0, false, "um");
        nowhere.close(T0);
        assertEquals(1, nowhere.left());
        assertFalse(Files.exists(tmp.resolve("nao-existe")));
    }
}
