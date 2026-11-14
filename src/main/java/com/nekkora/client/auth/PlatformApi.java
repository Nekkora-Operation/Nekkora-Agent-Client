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
    public static final String SCOPE = "identity";
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
