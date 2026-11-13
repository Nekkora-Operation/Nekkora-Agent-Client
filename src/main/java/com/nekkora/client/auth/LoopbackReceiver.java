package com.nekkora.client.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

import com.nekkora.client.theme.ClientTheme;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Retorno local da entrada (RFC 8252 7.3): um servidor HTTP temporario que escuta SO em {@code 127.0.0.1}, numa porta
 * efemera escolhida pelo sistema, e aceita SO {@code GET /callback} da tentativa atual.
 *
 * <ul>
 * <li>nunca escuta em {@code 0.0.0.0} nem em outra interface; nenhuma regra de firewall;</li>
 * <li>outro caminho: 404; outro metodo: 405; nada disso afeta a tentativa;</li>
 * <li>parametro repetido ou desconhecido, {@code state} ausente ou diferente (tempo constante), {@code iss} diferente
 * do emissor configurado: 400 ao navegador, e a tentativa legitima CONTINUA esperando. Depois de
 * {@value #MAX_INVALID} requisicoes invalidas a tentativa termina como retorno invalido;</li>
 * <li>com o {@code state} certo, o primeiro retorno decide: {@code error=access_denied} (cancelado) ou {@code code}. O
 * codigo e entregue uma unica vez a quem troca com a API; a pagina so diz "Login concluido" depois dessa confirmacao;</li>
 * <li>as paginas nao levam credencial, script nem recurso externo, e pedem ao navegador que nao as guarde.</li>
 * </ul>
 */
public final class LoopbackReceiver implements AutoCloseable {

    static final String PATH = "/callback";
    static final int MAX_INVALID = 20;
    private static final Pattern CODE = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    /** Quanto a pagina espera a confirmacao da API antes de responder ao navegador. */
    private static final long RESULT_WAIT_SECONDS = 25;

    /** O que o retorno decidiu. */
    public sealed interface Outcome permits Code, Denied, Invalid {
    }

    /** Codigo de autorizacao da tentativa atual. */
    public record Code(String value) implements Outcome {
        @Override
        public String toString() {
            return "Code[...]";
        }
    }

    /** A pessoa cancelou no navegador. */
    public record Denied() implements Outcome {
    }

    /** Retorno com o state certo mas sem sentido, ou excesso de requisicoes invalidas. */
    public record Invalid() implements Outcome {
    }

    /** O que mostrar no navegador depois que o aplicativo conferiu o codigo com a API. */
    public enum Page { SIGNED_IN, FAILED }

    private final HttpServer server;
    private final ExecutorService pool;
    private final String state;
    private final String issuer;
    private final Function<Outcome, CompletableFuture<Page>> onOutcome;
    private final AtomicBoolean decided = new AtomicBoolean();
    private final AtomicInteger invalid = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LoopbackReceiver(HttpServer server, ExecutorService pool, String state, String issuer,
                             Function<Outcome, CompletableFuture<Page>> onOutcome) {
        this.server = server;
        this.pool = pool;
        this.state = state;
        this.issuer = issuer;
        this.onOutcome = onOutcome;
    }

    /**
     * Abre o retorno ANTES de o navegador ser aberto. {@code onOutcome} e chamado no maximo uma vez, com o que o retorno
     * decidiu, e devolve o que a pagina deve mostrar quando a conferencia com a API terminar.
     */
    public static LoopbackReceiver open(String state, String issuer, Function<Outcome, CompletableFuture<Page>> onOutcome) throws IOException {
        // IP literal de loopback, porta 0 (o sistema escolhe uma livre), fila curta
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        HttpServer server = HttpServer.create(new InetSocketAddress(loopback, 0), 8);
        ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "retorno-local");
            t.setDaemon(true);
            return t;
        });
        LoopbackReceiver r = new LoopbackReceiver(server, pool, state, issuer, onOutcome);
        server.createContext("/", r::handle);
        server.setExecutor(pool);
        server.start();
        return r;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    InetAddress address() {
        return server.getAddress().getAddress();
    }

    /** O {@code redirect_uri} desta tentativa: sempre o IP literal, nunca "localhost". */
    public String redirectUri() {
        return "http://127.0.0.1:" + port() + PATH;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            server.stop(0);
            pool.shutdownNow();
        }
    }

    private void handle(HttpExchange x) throws IOException {
        try {
            if (!PATH.equals(x.getRequestURI().getRawPath())) {
                respond(x, 404, page("Página não encontrada", "Este endereço não faz parte do Nekkora Agent."));
                return;
            }
            if (!"GET".equals(x.getRequestMethod())) {
                x.getResponseHeaders().set("Allow", "GET");
                respond(x, 405, page("Método não permitido", "Este endereço só recebe o retorno do login."));
                return;
            }
            Map<String, String> q = query(x.getRequestURI().getRawQuery());
            if (q == null || !Pkce.same(state, q.get("state")) || !issuer.equals(q.get("iss"))) {
                rejected(x);
                return;
            }
            String code = q.get("code");
            String error = q.get("error");
            Outcome outcome;
            if (error != null && code == null) {
                outcome = "access_denied".equals(error) ? new Denied() : new Invalid();
            } else if (code != null && error == null && CODE.matcher(code).matches()) {
                outcome = new Code(code);
            } else {
                outcome = new Invalid();
            }
            if (!decided.compareAndSet(false, true)) {
                // o retorno desta tentativa ja foi usado: repeticao (recarregar, voltar) nao reabre nada
                respond(x, 400, page("Este retorno já foi usado", "Volte ao Nekkora Agent. Se precisar, clique em Entrar de novo."));
                return;
            }
            CompletableFuture<Page> result = onOutcome.apply(outcome);
            if (outcome instanceof Denied) {
                respond(x, 200, page("Entrada cancelada", "Você pode fechar esta aba e voltar ao Nekkora Agent."));
                return;
            }
            if (!(outcome instanceof Code)) {
                // sem codigo nao ha o que a API confirmar: a pagina nunca diz "concluido"
                respond(x, 400, page("Não foi possível confirmar o login", "Volte ao Nekkora Agent e clique em Entrar para tentar de novo."));
                return;
            }
            Page p;
            try {
                p = result.get(RESULT_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                p = Page.FAILED;
            }
            if (p == Page.SIGNED_IN) {
                respond(x, 200, page("Login concluído", "Você pode voltar ao Nekkora Agent."));
            } else {
                respond(x, 200, page("Não foi possível confirmar o login", "Volte ao Nekkora Agent e clique em Entrar para tentar de novo."));
            }
        } finally {
            x.close();
        }
    }

    private void rejected(HttpExchange x) throws IOException {
        // a contagem (e o encerramento, no limite) vem antes da resposta: quem recebe o 400 ja encontra a tentativa decidida
        if (invalid.incrementAndGet() >= MAX_INVALID && decided.compareAndSet(false, true)) {
            onOutcome.apply(new Invalid());
        }
        respond(x, 400, page("Não foi possível confirmar o login", "Este retorno não pertence à tentativa em andamento. Volte ao Nekkora Agent."));
    }

    /**
     * Consulta estrita: cada parametro uma vez, so os quatro conhecidos, valores decodificaveis. Qualquer ambiguidade
     * ({@code state=a&state=b}, parametro a mais, sem consulta): nulo.
     */
    static Map<String, String> query(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > 2048) {
            return null;
        }
        Map<String, String> m = new HashMap<>();
        for (String pair : raw.split("&", -1)) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                return null;
            }
            String k = pair.substring(0, eq);
            if (!(k.equals("code") || k.equals("state") || k.equals("iss") || k.equals("error")) || m.containsKey(k)) {
                return null;
            }
            try {
                m.put(k, URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        return m;
    }

    private static void respond(HttpExchange x, int status, String html) throws IOException {
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        var h = x.getResponseHeaders();
        h.set("Content-Type", "text/html; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("Pragma", "no-cache");
        h.set("Referrer-Policy", "no-referrer");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        h.set("Connection", "close");
        x.sendResponseHeaders(status, body.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(body);
        }
    }

    /** Pagina estatica: titulo e uma frase. Sem script, sem recurso externo, sem nada do pedido ou da conta. */
    private static String page(String title, String text) {
        ClientTheme d = ClientTheme.SAKURA_DARK;
        ClientTheme l = ClientTheme.NEKKORA_LIGHT;
        return "<!doctype html><html lang=\"pt-BR\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta name=\"referrer\" content=\"no-referrer\"><title>Nekkora Agent</title><style>"
                + "body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:" + d.bg().toHex()
                + ";color:" + d.text().toHex() + ";font-family:'Segoe UI',system-ui,sans-serif}main{max-width:420px;padding:32px;text-align:center}"
                + "h1{font-size:24px;font-weight:600;margin:0 0 12px}p{font-size:14px;line-height:1.5;margin:0;color:" + d.textSecondary().toHex() + "}"
                + "@media (prefers-color-scheme:light){body{background:" + l.bg().toHex() + ";color:" + l.text().toHex() + "}p{color:"
                + l.textSecondary().toHex() + "}}"
                + "</style></head><body><main><h1>" + title + "</h1><p>" + text + "</p></main></body></html>";
    }
}
