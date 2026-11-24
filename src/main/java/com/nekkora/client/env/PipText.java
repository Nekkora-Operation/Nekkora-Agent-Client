package com.nekkora.client.env;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * O que o aplicativo le do que o {@code pip} escreveu: a CLASSIFICACAO da falha (fase do contrato e o item a que ela se refere)
 * e a REMOCAO de segredos do registro do preparo antes de ele sair deste computador. Nenhum texto do pip decide sucesso: sucesso
 * e o codigo de saida zero mais as conferencias do proprio aplicativo.
 */
final class PipText {

    static final String MARK = "[removido]";
    /** Tamanho maximo do registro do preparo enviado a Platform (64 KiB, em bytes UTF-8). */
    static final int MAX_LOG = 64 * 1024;
    static final int MAX_SUMMARY = 500;

    private static final Pattern NO_MATCH = Pattern.compile("No matching distribution found for ([^\\s;]+)");
    private static final Pattern NOT_FOUND = Pattern.compile("Could not fetch URL \\S+: 404 Client Error");
    private static final Pattern CONFLICT = Pattern.compile("ResolutionImpossible|conflicting dependencies|Cannot install .+ because these package versions");
    private static final Pattern NETWORK = Pattern.compile("(?i)NewConnectionError|ConnectTimeoutError|ReadTimeoutError|Max retries exceeded|Failed to establish a new connection"
            + "|getaddrinfo failed|Name or service not known|No connection could be made|Connection refused|Network is unreachable|ProxyError|SSLError"
            + "|connection broken by|Connection aborted|Temporary failure in name resolution|RemoteDisconnected|BadStatusLine|ProtocolError|IncompleteRead");
    private static final Pattern HASH = Pattern.compile("THESE PACKAGES DO NOT MATCH THE HASHES");
    private static final Pattern HASH_ITEM = Pattern.compile("(?m)^\\s+([A-Za-z0-9][A-Za-z0-9._-]*==\\S+) from \\S+");
    private static final Pattern NO_SPACE = Pattern.compile("(?i)No space left on device|Errno 28|There is not enough space on the disk|not enough space");

    private static final Pattern URL_CREDENTIALS = Pattern.compile("(?i)(://)[^/@\\s:]+(?::[^/@\\s]*)?@");
    private static final Pattern MACHINE_SECRET = Pattern.compile("nk[ap]1\\.[A-Za-z0-9._~-]+");
    private static final Pattern AUTHORIZATION = Pattern.compile("(?i)\\b((?:proxy-)?authorization)\\b(\\s*[:=]\\s*)(?!\\[removido\\])(?:\\S+)(?:[ \\t]+(?!\\[removido\\])\\S+)?");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(bearer|basic)[ \\t]+(?!\\[removido\\])[A-Za-z0-9._~+/=-]{8,}");
    private static final Pattern NAMED_SECRET = Pattern.compile("(?i)\\b((?:access[_-]?)?token|secret|api[_-]?key|passw(?:or)?d|senha)\\b(\\s*[:=]\\s*)(?!\\[removido\\])\\S+");
    private static final Pattern QUERY_SECRET = Pattern.compile("(?i)([?&](?:token|key|signature|sig|access_token|password)=)[^&\\s]+");

    private PipText() {
    }

    /** O que a falha foi: a fase do contrato e o item (pode ser nulo). */
    record Classified(String phase, String requirement) {
    }

    /** Classifica a falha de uma RESOLUCAO (pip com {@code --dry-run --report}). A ordem importa: rede vem antes de "nao achou". */
    static Classified resolution(Path output, String tail) {
        String all = scan(output, tail);
        if (NETWORK.matcher(all).find()) {
            return new Classified("INDEX_UNREACHABLE", null);
        }
        if (CONFLICT.matcher(all).find()) {
            return new Classified("RESOLUTION_CONFLICT", null);
        }
        Matcher m = NO_MATCH.matcher(all);
        if (m.find()) {
            String req = clean(m.group(1));
            return new Classified(NOT_FOUND.matcher(all).find() ? "NOT_FOUND" : "NO_COMPATIBLE_WHEEL", req);
        }
        return new Classified("OTHER", null);
    }

    /** Classifica a falha de uma INSTALACAO do lock ({@code --no-deps --require-hashes}). */
    static Classified install(Path output, String tail) {
        String all = scan(output, tail);
        if (HASH.matcher(all).find()) {
            Matcher item = HASH_ITEM.matcher(all.substring(all.indexOf("THESE PACKAGES DO NOT MATCH THE HASHES")));
            return new Classified("HASH_MISMATCH", item.find() ? clean(item.group(1)) : null);
        }
        if (NO_SPACE.matcher(all).find()) {
            return new Classified("NO_SPACE", null);
        }
        if (NETWORK.matcher(all).find()) {
            return new Classified("DOWNLOAD", null);
        }
        Matcher m = NO_MATCH.matcher(all);
        if (m.find()) {
            return new Classified("NO_COMPATIBLE_WHEEL", clean(m.group(1)));
        }
        return new Classified("INSTALL", null);
    }

    /** Espaco em disco acabou? */
    static boolean noSpace(String text) {
        return NO_SPACE.matcher(text).find();
    }

    /**
     * Le o arquivo de saida inteiro (ate 8 MiB) para a classificacao: as mensagens de rede do {@code pip -v} podem estar no comeco
     * e o veredito no fim. Sem arquivo, so o fim ja lido.
     */
    private static String scan(Path output, String tail) {
        if (output == null) {
            return tail == null ? "" : tail;
        }
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new java.io.InputStreamReader(Files.newInputStream(output),
                StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)))) {
            String line;
            while ((line = r.readLine()) != null && b.length() < 8 * 1024 * 1024) {
                // so as linhas que podem decidir: avisos, erros e as de busca de pagina
                if (line.contains("ERROR") || line.contains("WARNING") || line.contains("Could not") || line.contains("Error") || line.contains("Errno")
                        || line.contains("No matching") || line.contains("THESE PACKAGES") || line.contains(" from ") || line.contains("Expected")
                        || line.contains("space")) {
                    b.append(line).append('\n');
                }
            }
        } catch (IOException | RuntimeException e) {
            return tail == null ? "" : tail;
        }
        return b.toString();
    }

    /** Um item como o pip o citou, sem pontuacao em volta; limitado para nao virar texto livre. */
    private static String clean(String s) {
        String t = s.replaceAll("^[\"'(]+|[\"'),.:]+$", "");
        return t.length() > 200 ? t.substring(0, 200) : t;
    }

    /**
     * O resumo da falha para a Platform (ate 500 caracteres, sem segredos): as linhas de erro do fim da saida, ou o texto dado.
     */
    static String summary(String fallback, String tail) {
        StringBuilder b = new StringBuilder();
        if (tail != null) {
            for (String line : tail.split("\\R")) {
                String l = line.strip();
                if (l.startsWith("ERROR:") || l.startsWith("error:") || l.contains("Error:")) {
                    b.append(b.length() == 0 ? "" : " ").append(l);
                }
            }
        }
        String text = b.length() == 0 ? fallback : fallback + " " + b;
        text = redact(text).replaceAll("\\p{Cntrl}", " ").replaceAll(" {2,}", " ").strip();
        return text.length() > MAX_SUMMARY ? text.substring(0, MAX_SUMMARY - 1) + "…" : text;
    }

    /** Tira do texto o que nunca sai deste computador: credenciais em URL, cabecalhos de autorizacao, tokens e senhas. */
    static String redact(String text) {
        if (text == null) {
            return "";
        }
        String s = URL_CREDENTIALS.matcher(text).replaceAll("$1" + Matcher.quoteReplacement(MARK) + "@");
        s = MACHINE_SECRET.matcher(s).replaceAll(Matcher.quoteReplacement(MARK));
        s = AUTHORIZATION.matcher(s).replaceAll("$1$2" + Matcher.quoteReplacement(MARK));
        s = BEARER.matcher(s).replaceAll("$1 " + Matcher.quoteReplacement(MARK));
        s = NAMED_SECRET.matcher(s).replaceAll("$1$2" + Matcher.quoteReplacement(MARK));
        return QUERY_SECRET.matcher(s).replaceAll("$1" + Matcher.quoteReplacement(MARK));
    }

    /**
     * O registro do preparo como vai para a Platform: sem segredos, sem caracteres de controle (fora a quebra de linha e a
     * tabulacao) e com no maximo {@link #MAX_LOG} bytes. Passando do limite, fica o comeco e o fim, com o corte dito no meio.
     */
    static String log(String text) {
        String s = redact(text).replace("\r\n", "\n").replace('\r', '\n').replaceAll("[\\p{Cntrl}&&[^\n\t]]", "�");
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= MAX_LOG) {
            return s;
        }
        String cut = "\n[... registro cortado no limite de 64 KiB ...]\n";
        int room = MAX_LOG - cut.getBytes(StandardCharsets.UTF_8).length - 8;
        String head = utf8Prefix(s, room / 4);
        String tail = utf8Suffix(s, room - head.getBytes(StandardCharsets.UTF_8).length);
        return head + cut + tail;
    }

    private static String utf8Prefix(String s, int bytes) {
        int used = 0;
        int i = 0;
        while (i < s.length()) {
            int cp = s.codePointAt(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + n > bytes) {
                break;
            }
            used += n;
            i += Character.charCount(cp);
        }
        return s.substring(0, i);
    }

    private static String utf8Suffix(String s, int bytes) {
        int used = 0;
        int i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            int n = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (used + n > bytes) {
                break;
            }
            used += n;
            i -= Character.charCount(cp);
        }
        return s.substring(i);
    }
}
