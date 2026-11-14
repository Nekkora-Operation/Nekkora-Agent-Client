package com.nekkora.client.auth;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.signin.SignInState.Notice;

/**
 * As tres chamadas do aplicativo a Platform (contrato desktop-login/1), sempre por HTTPS com o certificado conferido
 * pela JVM, sem cookies e sem seguir redirecionamentos:
 * <ul>
 * <li>{@link #exchange}: troca o codigo de autorizacao, com o verificador PKCE, pela sessao do aplicativo;</li>
 * <li>{@link #session}: confere a sessao e le a propria conta;</li>
 * <li>{@link #revoke}: encerra a sessao.</li>
 * </ul>
 * Uma resposta so e aceita se tiver exatamente a forma do contrato. Qualquer outra coisa - status inesperado, JSON
 * malformado, token fora do formato, escopo diferente de {@code identity}, prazo fora do limite - e falha, nunca sucesso.
 * <p>
 * Cada falha e classificada pelo que a resposta de fato mostra: sem conexao, TLS, tempo, erro do servidor ({@code 5xx}),
 * acesso negado pelo contrato ({@code 403} com {@code {"error":"forbidden"}}), ou resposta que nao e do contrato.
 */
public final class PlatformApi {

    public static final String CLIENT_ID = "nekkora-agent-client";
    /**
     * O que o aplicativo pede: a propria identidade e a navegacao (organizacoes, ambientes e automacoes da pessoa). A
     * pagina de confirmacao da Platform diz isso a pessoa; o servidor devolve o escopo concedido, e so vale se for este.
     */
    /**
     * Navegacao e download de pacotes (contrato desktop-navigation/3). O escopo e pedido, mostrado na pagina de confirmacao
     * e aprovado como tal; a Platform anterior a esse contrato nao o reconhece e recusa o pedido de entrada.
     */
    public static final String SCOPE = "identity navigation packages";
    /** Teto de um pacote baixado: o do artefato na Platform (50 MiB). */
    static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;
    private static final Duration DOWNLOAD_DEADLINE = Duration.ofMinutes(10);
    /** Teto das respostas de leitura (paginas de ate 100 itens, ou ate 200 organizacoes). */
    private static final int MAX_READ_CHARS = 512 * 1024;
    /** Teto que o aplicativo aceita para a sessao, em segundos (o contrato diz 900). */
    static final long MAX_SESSION_SECONDS = 900;
    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]{1,64}@[^\\s@]{1,255}$");

    /** Falha de uma chamada, ja classificada para a tela. */
    public static final class Failure extends Exception {
        private static final long serialVersionUID = 1L;
        private final Notice notice;

        public Failure(Notice notice) {
            super(notice.name(), null, false, false);
            this.notice = notice;
        }

        public Notice notice() {
            return notice;
        }
    }

    /** Sessao emitida. {@code token} so circula em memoria; {@link #toString()} nunca o mostra. */
    public record Token(String value, long expiresInSeconds) {
        @Override
        public String toString() {
            return "Token[...]";
        }
    }

    public record Account(String email, long expiresInSeconds) {
    }

    private final PlatformOrigin origin;
    private final HttpClient http;
    private final Duration requestTimeout;

    public PlatformApi(PlatformOrigin origin) {
        this(origin, null, Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    /** {@code ssl} nulo: o contexto padrao da JVM (o repositorio de confianca do sistema ou o do launcher). */
    public PlatformApi(PlatformOrigin origin, SSLContext ssl, Duration connectTimeout, Duration requestTimeout) {
        if (!origin.api().startsWith("https://")) {
            throw new IllegalArgumentException("a API e sempre HTTPS");
        }
        this.origin = origin;
        this.requestTimeout = requestTimeout;
        HttpClient.Builder b = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout);
        if (ssl != null) {
            b.sslContext(ssl);
        }
        this.http = b.build();
    }

    public Token exchange(String code, String verifier, String redirectUri) throws Failure {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("client_id", CLIENT_ID);
        form.put("code_verifier", verifier);
        StringBuilder body = new StringBuilder();
        form.forEach((k, v) -> body.append(body.length() > 0 ? "&" : "").append(k).append('=').append(URLEncoder.encode(v, StandardCharsets.UTF_8)));
        HttpResponse<String> r = send(request("/api/v1/desktop/token").header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())));
        if (r.statusCode() == 400) {
            // invalid_grant, invalid_request...: o codigo nao serve; nada e reaproveitado
            throw new Failure(Notice.INVALID_RETURN);
        }
        requireOk(r);
        try {
            Map<String, Object> o = Json.object(r.body());
            String token = string(o, "access_token");
            long expires = seconds(o, "expires_in");
            if (!TOKEN.matcher(token).matches() || !"Bearer".equals(string(o, "token_type")) || !SCOPE.equals(string(o, "scope"))
                    || o.size() != 4) {
                throw new Failure(Notice.INVALID_RETURN);
            }
            return new Token(token, expires);
        } catch (IllegalArgumentException e) {
            throw new Failure(Notice.INVALID_RETURN);
        }
    }

    /** A conta da sessao. Sessao desconhecida, vencida ou revogada: {@link Notice#SESSION_EXPIRED}. */
    public Account session(Token token) throws Failure {
        HttpResponse<String> r = send(request("/api/v1/desktop/session").header("Authorization", "Bearer " + token.value()).GET());
        if (r.statusCode() == 401) {
            throw new Failure(Notice.SESSION_EXPIRED);
        }
        requireOk(r);
        try {
            Map<String, Object> o = Json.object(r.body());
            Object account = o.get("account");
            if (!(account instanceof Map<?, ?> a) || !SCOPE.equals(string(o, "scope")) || o.size() != 3 || a.size() != 1
                    || !(a.get("email") instanceof String email) || !EMAIL.matcher(email).matches()) {
                throw new Failure(Notice.INVALID_RETURN);
            }
            return new Account(email, seconds(o, "expiresIn"));
        } catch (IllegalArgumentException e) {
            throw new Failure(Notice.INVALID_RETURN);
        }
    }

    /**
     * Leitura autenticada pelo token do aplicativo (rotas {@code /api/v1/desktop/...} da navegacao). Classifica cada
     * falha pelo que a resposta mostra; so {@code 200} com JSON de objeto e sucesso.
     */
    Map<String, Object> read(String path, Token token) throws Directory.Failed {
        return call(request(path).header("Authorization", "Bearer " + token.value()).GET());
    }

    /** Escrita autenticada pelo token do aplicativo, com corpo JSON. So para as acoes do contrato de navegacao. */
    Map<String, Object> patch(String path, Token token, String json) throws Directory.Failed {
        return call(request(path).header("Authorization", "Bearer " + token.value()).header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)));
    }

    /**
     * Download autenticado do pacote de uma versao publicada. So aceita {@code 200 application/zip}, do proprio endereco da
     * Platform (este cliente HTTP nunca segue redirecionamento: um {@code 3xx} e resposta fora do contrato, e o token nao vai
     * a nenhum outro endereco), com {@code Content-Length} igual ao tamanho que a lista de versoes informou. Entrega os
     * bytes ao destino em trechos; para ao cancelar, ao passar do tamanho ou do prazo, ou quando o destino recusa.
     */
    void download(String path, Token token, long expectedSize, Directory.Sink sink) throws Directory.Failed {
        if (expectedSize <= 0 || expectedSize > MAX_PACKAGE_BYTES) {
            throw new Directory.Failed(Directory.Failure.INVALID);
        }
        HttpRequest request = HttpRequest.newBuilder(origin.apiPath(path)).timeout(requestTimeout).header("Accept", "application/zip, application/json")
                .header("Authorization", "Bearer " + token.value()).GET().build();
        HttpResponse<java.io.InputStream> r;
        try {
            r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (SSLException e) {
            throw new Directory.Failed(Directory.Failure.TLS);
        } catch (HttpTimeoutException e) {
            throw new Directory.Failed(Directory.Failure.TIMEOUT);
        } catch (IOException e) {
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof SSLException) {
                    throw new Directory.Failed(Directory.Failure.TLS);
                }
            }
            throw new Directory.Failed(Directory.Failure.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Directory.Failed(Directory.Failure.CANCELLED);
        }
        try (java.io.InputStream in = r.body()) {
            int s = r.statusCode();
            String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
            if (s != 200) {
                throw new Directory.Failed(switch (s) {
                    case 401 -> Directory.Failure.SESSION_ENDED;
                    case 403 -> type.startsWith("application/json") ? Directory.Failure.DENIED : Directory.Failure.INVALID;
                    case 404 -> Directory.Failure.NOT_FOUND;
                    case 409 -> type.startsWith("application/json") ? Directory.Failure.CONFLICT : Directory.Failure.INVALID;
                    case 429 -> Directory.Failure.RATE_LIMITED;
                    default -> s >= 500 ? Directory.Failure.SERVER : Directory.Failure.INVALID;
                });
            }
            long declared = r.headers().firstValueAsLong("Content-Length").orElse(-1);
            if (!type.startsWith("application/zip") || declared != expectedSize) {
                throw new Directory.Failed(Directory.Failure.INVALID);
            }
            long deadline = System.nanoTime() + DOWNLOAD_DEADLINE.toNanos();
            byte[] buffer = new byte[64 * 1024];
            long received = 0;
            sink.progress(0, expectedSize);
            while (true) {
                if (sink.cancelled()) {
                    throw new Directory.Failed(Directory.Failure.CANCELLED);
                }
                if (System.nanoTime() > deadline) {
                    throw new Directory.Failed(Directory.Failure.TIMEOUT);
                }
                int n = in.read(buffer);
                if (n < 0) {
                    break;
                }
                received += n;
                if (received > expectedSize) {
                    throw new Directory.Failed(Directory.Failure.INVALID);
                }
                if (!sink.write(buffer, n)) {
                    throw new Directory.Failed(Directory.Failure.LOCAL);
                }
                sink.progress(received, expectedSize);
            }
            if (received != expectedSize) {
                // conexao encerrada antes do fim: bytes parciais nunca sao um pacote
                throw new Directory.Failed(Directory.Failure.NETWORK);
            }
        } catch (IOException e) {
            throw new Directory.Failed(sink.cancelled() ? Directory.Failure.CANCELLED : Directory.Failure.NETWORK);
        }
    }

    Map<String, Object> call(HttpRequest.Builder b) throws Directory.Failed {
        HttpResponse<String> r;
        try {
            r = send(b);
        } catch (Failure f) {
            throw new Directory.Failed(switch (f.notice()) {
                case TLS -> Directory.Failure.TLS;
                case TIMEOUT -> Directory.Failure.TIMEOUT;
                default -> Directory.Failure.NETWORK;
            });
        }
        int s = r.statusCode();
        if (s == 200) {
            String type = r.headers().firstValue("Content-Type").orElse("");
            if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new Directory.Failed(Directory.Failure.INVALID);
            }
            try {
                return Json.object(r.body(), MAX_READ_CHARS);
            } catch (IllegalArgumentException e) {
                throw new Directory.Failed(Directory.Failure.INVALID);
            }
        }
        if (s == 401) {
            throw new Directory.Failed(Directory.Failure.SESSION_ENDED);
        }
        if (s == 403 && forbiddenByContract(r)) {
            throw new Directory.Failed(Directory.Failure.DENIED);
        }
        if (s == 404) {
            throw new Directory.Failed(Directory.Failure.NOT_FOUND);
        }
        if (s == 429) {
            throw new Directory.Failed(Directory.Failure.RATE_LIMITED);
        }
        if (s >= 500) {
            throw new Directory.Failed(Directory.Failure.SERVER);
        }
        if ((s == 400 || s == 409) && r.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            throw new Directory.Failed(s == 400 ? Directory.Failure.REJECTED : Directory.Failure.CONFLICT);
        }
        // qualquer outro status (inclusive 403 que nao e o do contrato): nao e resposta do contrato
        throw new Directory.Failed(Directory.Failure.INVALID);
    }

    /** Encerra a sessao na Platform. Melhor esforco: {@code false} se a Platform nao confirmou. */
    public boolean revoke(Token token) {
        try {
            HttpResponse<String> r = send(request("/api/v1/desktop/session/revoke").header("Authorization", "Bearer " + token.value())
                    .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.noBody()));
            return r.statusCode() == 204;
        } catch (Failure e) {
            return false;
        }
    }

    private HttpRequest.Builder request(String path) {
        URI uri = origin.apiPath(path);
        return HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Failure {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (SSLException e) {
            throw new Failure(Notice.TLS);
        } catch (HttpConnectTimeoutException e) {
            throw new Failure(Notice.TIMEOUT);
        } catch (HttpTimeoutException e) {
            throw new Failure(Notice.TIMEOUT);
        } catch (ConnectException e) {
            throw new Failure(Notice.NETWORK);
        } catch (IOException e) {
            // o cliente HTTP embrulha a falha de TLS em IOException em alguns caminhos
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof SSLException) {
                    throw new Failure(Notice.TLS);
                }
            }
            throw new Failure(Notice.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Failure(Notice.NETWORK);
        }
    }

    private static void requireOk(HttpResponse<String> r) throws Failure {
        int s = r.statusCode();
        if (s == 200) {
            String type = r.headers().firstValue("Content-Type").orElse("");
            if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                throw new Failure(Notice.INVALID_RETURN);
            }
            return;
        }
        if (s == 429) {
            throw new Failure(Notice.RATE_LIMITED);
        }
        if (s == 404) {
            // Platform sem a entrada do aplicativo (recurso desligado ou versao anterior)
            throw new Failure(Notice.UNAVAILABLE);
        }
        if (s >= 500) {
            throw new Failure(Notice.SERVER);
        }
        if (s == 403 && forbiddenByContract(r)) {
            throw new Failure(Notice.ACCESS_DENIED);
        }
        // redirecionamento, 401 na troca, 403 que nao e o do contrato (pagina de um intermediario, por exemplo) ou qualquer
        // outro status: nao e uma resposta do contrato, entao nao vira "acesso negado" nem "erro do servidor"
        throw new Failure(Notice.INVALID_RETURN);
    }

    /**
     * So o 403 do proprio contrato da API ({@code application/json}, corpo exatamente {@code {"error":"forbidden"}}) quer
     * dizer que a Platform negou o acesso. Um 403 com outro corpo ou outro tipo de conteudo nao e essa evidencia.
     */
    private static boolean forbiddenByContract(HttpResponse<String> r) {
        String type = r.headers().firstValue("Content-Type").orElse("");
        if (!type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
            return false;
        }
        try {
            Map<String, Object> o = Json.object(r.body());
            return o.size() == 1 && "forbidden".equals(o.get("error"));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String string(Map<String, Object> o, String key) {
        if (o.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key);
    }

    private static long seconds(Map<String, Object> o, String key) {
        if (o.get(key) instanceof Double d && d == Math.rint(d) && d >= 1 && d <= MAX_SESSION_SECONDS) {
            return d.longValue();
        }
        throw new IllegalArgumentException(key);
    }
}
