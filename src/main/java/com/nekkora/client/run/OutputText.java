package com.nekkora.client.run;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - o que o processo de uma execucao iniciada NESTE computador escreveu na saida
 * padrao e na saida de erro, guardado num arquivo de texto na pasta do registro dessa execucao. Fica SO neste computador:
 * esta classe nao conhece rede, nada aqui e lido por quem fala com a Platform, e o arquivo sai junto com o registro da
 * execucao quando ele e removido pelo limite de registros guardados.
 *
 * <p><b>Formato.</b> Texto UTF-8, uma linha por linha capturada: instante (relogio deste computador), canal e o texto. Os
 * canais sao {@code saida}, {@code erro} (a saida de erro e um canal, nao uma falha) e {@code aplicativo} (o que o proprio
 * aplicativo anota: o limite atingido e quantas linhas ficaram de fora).
 *
 * <p><b>Limites.</b> {@value #MAX_LINES} linhas e 20 MiB por execucao. Ao atingir, o arquivo recebe UMA linha do aplicativo
 * dizendo isso; dai em diante as linhas sao lidas (o processo nao trava) e CONTADAS, sem guardar; no fim, outra linha diz
 * quantas ficaram de fora. O resultado da execucao nao muda.
 *
 * <p><b>O que nao entra.</b> Credencial de maquina, codigo de habilitacao e valores de cabecalho de autorizacao sao trocados
 * por {@code [removido]} antes de ir para o disco, com as mesmas regras da execucao pedida pela Platform. Nao ha promessa
 * sobre outros dados que o proprio script imprima.
 *
 * <p>Falha de disco nunca interrompe a execucao: a linha se perde, a leitura continua, e o que faltou e contado.
 */
final class OutputText implements OutputLines.Sink {

    static final int MAX_LINES = 50_000;
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final String MARK = "[removido]";
    static final String HEADER = "O que o processo desta execução escreveu, capturado pelo Nekkora Agent neste computador. Não é enviado à Platform.";
    static final String LIMIT_NOTICE = "Limite deste arquivo atingido: as linhas seguintes do processo não são guardadas. "
            + "A execução continua e o resultado dela não muda.";
    static final String CUT = " [linha cortada no limite de 8 KiB]";

    private static final Pattern MACHINE_SECRET = Pattern.compile("nk[ap]1\\.[A-Za-z0-9._~-]+");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b((?:proxy-)?authorization)\\b(\\s*[:=]\\s*)(?!\\[removido\\])(?:\\S+)(?:[ \\t]+(?!\\[removido\\])\\S+)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)[ \\t]+[A-Za-z0-9._~+/=-]{8,}");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long FLUSH_MILLIS = 1000;
    private static final int FLUSH_LINES = 50;
    /** Bytes do fim de linha deste sistema: entram na conta do tamanho do arquivo. */
    private static final int EOL = System.lineSeparator().length();

    private final Path file;
    private final ZoneId zone;
    private BufferedWriter out;
    private int lines;
    private long bytes;
    private long left;
    private boolean full;
    private boolean closed;
    private int unflushed;
    private long flushedAt;

    OutputText(Path file, ZoneId zone) {
        this.file = file;
        this.zone = zone;
    }

    /** Cria o arquivo e grava o cabecalho. Se nao der, as linhas passam a ser so contadas. */
    synchronized void open() {
        try {
            out = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            out.write(HEADER);
            out.newLine();
            out.flush();
            bytes = HEADER.getBytes(StandardCharsets.UTF_8).length + EOL;
        } catch (IOException | RuntimeException e) {
            out = null;
        }
    }

    @Override
    public synchronized void line(boolean error, long epochMillis, boolean truncated, String text) {
        if (closed) return;
        if (full || out == null) {
            left++;
            return;
        }
        String row = row(epochMillis, error ? "erro" : "saída", redact(text == null ? "" : text) + (truncated ? CUT : ""));
        long size = row.getBytes(StandardCharsets.UTF_8).length + EOL;
        if (lines >= MAX_LINES - 1 || bytes + size > MAX_BYTES - 1024) {
            write(row(epochMillis, "aplicativo", LIMIT_NOTICE));
            full = true;
            left++;
            flush();
            return;
        }
        if (!write(row)) {
            left++;
            return;
        }
        lines++;
        bytes += size;
        if (++unflushed >= FLUSH_LINES || epochMillis - flushedAt >= FLUSH_MILLIS) {
            flush();
            flushedAt = epochMillis;
        }
    }

    private String row(long epochMillis, String channel, String text) {
        return TIME.format(Instant.ofEpochMilli(epochMillis).atZone(zone)) + " " + String.format("%-10s", channel) + " | " + text;
    }

    private boolean write(String row) {
        try {
            out.write(row);
            out.newLine();
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private void flush() {
        unflushed = 0;
        try {
            if (out != null) out.flush();
        } catch (IOException | RuntimeException e) {
            // a proxima gravacao tenta de novo
        }
    }

    /** O que nunca vai para o arquivo, mesmo que o processo tenha escrito. Repetir nao muda o que ja saiu limpo. */
    static String redact(String text) {
        String s = MACHINE_SECRET.matcher(text).replaceAll(Matcher.quoteReplacement(MARK));
        s = AUTHORIZATION.matcher(s).replaceAll("$1$2" + Matcher.quoteReplacement(MARK));
        return BEARER.matcher(s).replaceAll("$1 " + Matcher.quoteReplacement(MARK));
    }

    /** O processo terminou (ou os fluxos fecharam): diz quantas linhas ficaram de fora, se alguma, e fecha o arquivo. */
    synchronized void close(long epochMillis) {
        if (closed) return;
        closed = true;
        if (out == null) return;
        if (left > 0) {
            write(row(epochMillis, "aplicativo", (left == 1 ? "1 linha do processo não foi guardada" : left + " linhas do processo não foram guardadas")
                    + " neste arquivo."));
        }
        try {
            out.close();
        } catch (IOException | RuntimeException e) {
            // o que ja foi gravado fica
        }
    }

    /** Quantas linhas do processo este arquivo guardou. */
    synchronized int lines() {
        return lines;
    }

    /** Quantas linhas foram lidas do processo e nao guardadas (limite ou falha de disco). */
    synchronized long left() {
        return left;
    }
}
