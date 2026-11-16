package com.nekkora.client.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A saida de um processo, lida LINHA A LINHA enquanto ele roda (funcionalidade nova: antes a saida era lida e descartada).
 * A leitura nunca para: o processo nao trava por saida cheia, com ou sem quem receba as linhas. O que e feito com cada
 * linha e decisao de quem entrega o {@link Sink}; esta classe nao grava nem envia nada.
 *
 * <p>Regras da linha: fim em {@code \n} (o {@code \r} sozinho e ignorado); o texto e lido como UTF-8 se a linha for UTF-8
 * valido e, se nao for, na pagina de codigo do Windows deste computador (e nela que o Python escreve num fluxo redirecionado,
 * a menos que o script mude); caractere de controle (fora a tabulacao) vira o caractere de substituicao; no maximo
 * {@link #MAX_LINE_BYTES} bytes em UTF-8 - o excedente da linha e lido e desprezado, e a linha vai marcada como cortada.
 */
public final class OutputLines {

    /** Limite de uma linha, em bytes UTF-8. */
    public static final int MAX_LINE_BYTES = 8 * 1024;

    /** Quem recebe as linhas. Chamado da linha de leitura do fluxo: tem de voltar logo e nao pode lancar. */
    public interface Sink {
        /** {@code error}: veio da saida de erro. {@code epochMillis}: quando a linha foi lida. */
        void line(boolean error, long epochMillis, boolean truncated, String text);
    }

    private OutputLines() {
    }

    /** Le {@code in} ate o fim, contando os bytes em {@code bytes} e entregando cada linha a {@code sink} (que pode ser nulo). */
    static void pump(InputStream in, boolean error, Sink sink, AtomicLong bytes, Clock clock) {
        byte[] buf = new byte[8192];
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        boolean over = false;
        try (in) {
            for (int n; (n = in.read(buf)) >= 0; ) {
                bytes.addAndGet(n);
                if (sink == null) continue;
                for (int i = 0; i < n; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        emit(sink, error, clock, line, over);
                        line.reset();
                        over = false;
                    } else if (b == '\r') {
                        // fim de linha do Windows, ou retorno de carro de barra de progresso: nao entra no texto
                    } else if (line.size() < MAX_LINE_BYTES) {
                        line.write(b);
                    } else {
                        over = true;
                    }
                }
            }
        } catch (IOException e) {
            // fluxo fechado: o processo terminou
        }
        if (sink != null && (line.size() > 0 || over)) emit(sink, error, clock, line, over);
    }

    private static void emit(Sink sink, boolean error, Clock clock, ByteArrayOutputStream raw, boolean over) {
        try {
            String text = clean(decode(raw.toByteArray(), over));
            String fitted = fit(text);
            sink.line(error, clock.millis(), over || fitted.length() != text.length(), fitted);
        } catch (RuntimeException e) {
            // quem recebe falhou: a leitura continua
        }
    }

    /** A pagina de codigo do sistema (onde o Python escreve por padrao num fluxo redirecionado); UTF-8 se nao der para saber. */
    static final java.nio.charset.Charset NATIVE = nativeCharset();

    private static java.nio.charset.Charset nativeCharset() {
        try {
            String name = System.getProperty("native.encoding");
            return name == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(name);
        } catch (RuntimeException e) {
            return StandardCharsets.UTF_8;
        }
    }

    /**
     * UTF-8 se os bytes forem UTF-8 valido; senao, a pagina de codigo do sistema. Numa linha cortada no limite, um caractere
     * UTF-8 partido no fim nao conta como invalido: os bytes dele saem.
     */
    static String decode(byte[] raw, boolean cut) {
        for (int drop = 0; drop <= (cut ? 3 : 0) && drop <= raw.length; drop++) {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(raw, 0, raw.length - drop)).toString();
            } catch (java.nio.charset.CharacterCodingException e) {
                // tenta sem o ultimo byte (so em linha cortada), ou cai na pagina de codigo
            }
        }
        return new String(raw, NATIVE);
    }

    /** Caractere de controle (fora a tabulacao) vira o caractere de substituicao: a linha e sempre uma linha de texto. */
    static String clean(String text) {
        StringBuilder b = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7f) {
                if (b == null) b = new StringBuilder(text);
                b.setCharAt(i, '\uFFFD');
            }
        }
        return b == null ? text : b.toString();
    }

    /** O maior prefixo de {@code text} que cabe em {@link #MAX_LINE_BYTES} bytes UTF-8, sem partir um caractere. */
    static String fit(String text) {
        if (text.length() * 3L <= MAX_LINE_BYTES || text.getBytes(StandardCharsets.UTF_8).length <= MAX_LINE_BYTES) return text;
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int cp = text.codePointAt(i);
            int size = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + size > MAX_LINE_BYTES) break;
            bytes += size;
            i += Character.charCount(cp);
        }
        return text.substring(0, i);
    }
}
