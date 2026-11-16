package com.nekkora.client.remote;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - a fila local dos registros (saida do processo) de UMA tentativa remota, so para
 * a entrega a Platform. Nao e historico: o aplicativo nao mostra o conteudo, e o arquivo e apagado quando a Platform confirma
 * o recebimento.
 *
 * <p><b>Protecao.</b> Cada bloco gravado no disco passa pela protecao do sistema operacional ({@link Keyring}): so o mesmo
 * usuario do Windows, neste computador, o le. Antes de entrar na fila (e portanto antes do disco e da rede), cada linha tem
 * removidos: a credencial da maquina, o token da atribuicao, codigos de habilitacao e valores de cabecalho de autorizacao.
 *
 * <p><b>Limites.</b> {@value #MAX_LINES} linhas e 20 MiB por tentativa. Ao atingir, a fila grava UMA linha do aplicativo
 * dizendo isso e passa a CONTAR o que chega, sem guardar: a captura nao para em silencio e o resultado do robo nao muda.
 *
 * <p>Uma execucao iniciada neste computador nunca tem fila: so quem conduz uma tentativa da Platform cria uma.
 */
final class LogQueue {

    static final int MAX_LINES = 50_000;
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int BATCH_LINES = 200;
    static final long BATCH_BYTES = 256L * 1024;
    static final String MARK = "[removido]";
    static final String LIMIT_NOTICE = "Limite de registros desta execução atingido: as linhas seguintes da saída não são guardadas. "
            + "A execução continua e o resultado dela não muda.";

    private static final Pattern MACHINE_SECRET = Pattern.compile("nk[ap]1\\.[A-Za-z0-9._~-]+");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b((?:proxy-)?authorization)\\b(\\s*[:=]\\s*)(?!\\[removido\\])(?:\\S+)(?:[ \\t]+(?!\\[removido\\])\\S+)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer)[ \\t]+[A-Za-z0-9._~+/=-]{8,}");
    /** Folga para a linha do aviso de limite caber sempre. */
    private static final long RESERVED_BYTES = 1024;
    private static final int FLUSH_LINES = 50;
    private static final long FLUSH_MILLIS = 1000;

    /** Uma linha da fila. {@code channel}: STDOUT, STDERR ou EXECUTOR. */
    record Line(int sequence, long epochMillis, String channel, boolean truncated, String message) {
    }

    private final Path file;
    private final Keyring keyring;
    private final List<String> secrets;
    /** Linhas ainda nao confirmadas pela Platform (as confirmadas saem da memoria). */
    private final List<Line> lines = new ArrayList<>();
    /** Linhas de {@link #lines} que ainda nao foram para o disco. */
    private final List<Line> unsaved = new ArrayList<>();
    private int last;
    private long bytes;
    private long dropped;
    private boolean full;
    private int acked;
    private long flushedAt;
    private long savedDropped;
    private final boolean existed;

    /** Abre a fila de uma tentativa; se o arquivo ja existe (aplicativo reaberto), o que estava nele volta. */
    LogQueue(Path file, Keyring keyring, List<String> secrets) {
        this.file = file;
        this.keyring = keyring;
        this.secrets = secrets.stream().filter(s -> s != null && s.length() >= 8).toList();
        this.existed = Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS);
        if (existed) load();
    }

    /** {@code true} se a fila ja existia no disco quando foi aberta (o aplicativo foi reaberto no meio da tentativa). */
    boolean existed() {
        return existed;
    }

    /** Uma linha da saida do processo. Nunca lanca e nunca espera a rede. */
    synchronized void add(boolean error, long epochMillis, boolean truncated, String text) {
        append(error ? "STDERR" : "STDOUT", epochMillis, truncated, text, false);
    }

    /** Uma linha do proprio aplicativo (canal EXECUTOR). */
    synchronized void note(long epochMillis, String text) {
        append("EXECUTOR", epochMillis, false, text, false);
    }

    private void append(String channel, long epochMillis, boolean truncated, String text, boolean notice) {
        if (full) {
            dropped++;
            return;
        }
        String clean = redact(text == null ? "" : text);
        long size = clean.getBytes(StandardCharsets.UTF_8).length;
        if (!notice && (last >= MAX_LINES - 1 || bytes + size > MAX_BYTES - RESERVED_BYTES)) {
            // o aviso ocupa a ultima vaga; dai em diante so se conta
            append("EXECUTOR", epochMillis, false, LIMIT_NOTICE, true);
            full = true;
            dropped++;
            flushQuietly();
            return;
        }
        Line l = new Line(++last, epochMillis, channel, truncated, clean);
        bytes += size;
        lines.add(l);
        unsaved.add(l);
        if (unsaved.size() >= FLUSH_LINES || epochMillis - flushedAt >= FLUSH_MILLIS) flushQuietly();
    }

    /** O que nunca entra na fila, mesmo que o processo tenha escrito. */
    String redact(String text) {
        String out = text;
        for (String secret : secrets) out = out.replace(secret, MARK);
        out = MACHINE_SECRET.matcher(out).replaceAll(Matcher.quoteReplacement(MARK));
        out = AUTHORIZATION.matcher(out).replaceAll("$1$2" + Matcher.quoteReplacement(MARK));
        return BEARER.matcher(out).replaceAll("$1 " + Matcher.quoteReplacement(MARK));
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (IOException e) {
            // fica na memoria e e tentado de novo
        }
    }

    /** Grava no disco, protegido, o que ainda esta so na memoria. */
    synchronized void flush() throws IOException {
        flushedAt = System.currentTimeMillis();
        if (unsaved.isEmpty() && dropped == savedDropped) return;
        StringBuilder b = new StringBuilder("v1\t").append(dropped).append('\t').append(full ? 1 : 0).append('\n');
        for (Line l : unsaved) {
            b.append(l.sequence()).append('\t').append(l.epochMillis()).append('\t').append(l.channel()).append('\t').append(l.truncated() ? 1 : 0).append('\t')
                    .append(l.message()).append('\n');
        }
        byte[] blob = keyring.protect(b.toString().getBytes(StandardCharsets.UTF_8));
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
             DataOutputStream data = new DataOutputStream(out)) {
            data.writeInt(blob.length);
            data.write(blob);
        }
        unsaved.clear();
        savedDropped = dropped;
    }

    private void load() {
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS); DataInputStream data = new DataInputStream(in)) {
            while (true) {
                int size;
                try {
                    size = data.readInt();
                } catch (EOFException end) {
                    break;
                }
                if (size <= 0 || size > 64 * 1024 * 1024) break;
                byte[] blob = new byte[size];
                data.readFully(blob);
                String[] rows = new String(keyring.unprotect(blob), StandardCharsets.UTF_8).split("\n");
                String[] head = rows[0].split("\t");
                dropped = Long.parseLong(head[1]);
                full = head[2].equals("1");
                for (int i = 1; i < rows.length; i++) {
                    String[] f = rows[i].split("\t", 5);
                    Line l = new Line(Integer.parseInt(f[0]), Long.parseLong(f[1]), f[2], f[3].equals("1"), f[4]);
                    if (l.sequence() != last + 1) continue;
                    last = l.sequence();
                    bytes += l.message().getBytes(StandardCharsets.UTF_8).length;
                    lines.add(l);
                }
            }
        } catch (IOException | RuntimeException e) {
            // bloco cortado por uma queda, ou ilegivel: vale o que foi lido ate ele
        }
        savedDropped = dropped;
    }

    /** Quantas linhas a fila tem (enviadas ou nao). */
    synchronized int lines() {
        return last;
    }

    /** Quantas linhas foram lidas do processo e NAO guardadas, por limite. */
    synchronized long dropped() {
        return dropped;
    }

    /** O proximo lote a enviar: as linhas depois da ultima confirmada, dentro dos limites do lote. Vazio: nada a enviar. */
    synchronized List<Line> batch() {
        List<Line> out = new ArrayList<>();
        long size = 0;
        for (Line l : lines) {
            if (l.sequence() <= acked) continue;
            long len = l.message().getBytes(StandardCharsets.UTF_8).length;
            if (!out.isEmpty() && (out.size() >= BATCH_LINES || size + len > BATCH_BYTES)) break;
            out.add(l);
            size += len;
        }
        return out;
    }

    /** A Platform confirmou ter as linhas ate {@code sequence}: elas saem da memoria. */
    synchronized void acked(int sequence) {
        if (sequence <= acked) return;
        acked = Math.min(sequence, last);
        lines.removeIf(l -> l.sequence() <= acked);
    }

    /**
     * A Platform nao aceita mais linhas desta tentativa (limite do lado dela): o que ainda nao foi enviado passa a contar como
     * descartado, e a fila nao guarda mais nada.
     */
    synchronized void refusedFrom(int sequence) {
        int cut = Math.max(sequence - 1, acked);
        dropped += last - cut;
        lines.removeIf(l -> l.sequence() > cut);
        unsaved.removeIf(l -> l.sequence() > cut);
        last = cut;
        full = true;
        flushQuietly();
    }

    /** {@code true} se tudo o que a fila tem ja foi confirmado pela Platform. */
    synchronized boolean drained() {
        return acked >= last;
    }

    /** Apaga o arquivo da fila (so depois da confirmacao de recebimento, ou quando a tentativa nao chegou a executar). */
    synchronized void delete() {
        lines.clear();
        unsaved.clear();
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // fica para a limpeza por prazo
        }
    }

    /** Para os testes: o conteudo bruto do arquivo. */
    static byte[] raw(Path file) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Files.copy(file, out);
        return out.toByteArray();
    }
}
