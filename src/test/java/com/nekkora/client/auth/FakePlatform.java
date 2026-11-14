package com.nekkora.client.auth;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Platform SIMULADA para os testes do Client (fica em src/test; nao entra no aplicativo). Fala HTTPS de verdade, com um
 * certificado criado na hora ({@code keytool}), e segue o contrato desktop-login/1 do lado do servidor: valida o pedido,
 * emite codigo de uso unico preso ao desafio PKCE e ao redirect, confere {@code S256(code_verifier)} na troca, emite
 * token opaco e responde a sessao. Cada teste pode trocar uma resposta para exercitar uma falha.
 *
 * <p>A prova contra a Platform REAL esta nos testes da API (DesktopLoginIT) e no aceite do laboratorio.
 */
final class FakePlatform implements AutoCloseable {

    record Grant(String challenge, String redirectUri, String email) {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static Path keystore;

    final HttpsServer server;
    final SSLContext clientTrust;
    final Map<String, Grant> codes = new ConcurrentHashMap<>();
    final Map<String, String> sessions = new ConcurrentHashMap<>();
    final Map<String, String> usedCodes = new ConcurrentHashMap<>();
    final List<String> requests = new CopyOnWriteArrayList<>();
    final List<String> revoked = new CopyOnWriteArrayList<>();
    final List<String> tokenBodies = new CopyOnWriteArrayList<>();

    volatile String email = "ana@empresa.test";
    volatile long expiresIn = 900;
    volatile long tokenDelayMillis;
    /** Substituem a resposta normal da troca e da sessao, quando nao nulos. */
    volatile Integer tokenStatus;
    volatile String tokenBody;
    volatile String tokenContentType = "application/json";
    volatile Integer sessionStatus;
    volatile String sessionBody;
    /** Navegacao: o que /me e /organizations devolvem; {@code readStatus}/{@code readBody} substituem a resposta normal. */
    volatile String displayName = "Ana Souza";
    volatile String organizationsBody = "{\"items\":[]}";
    volatile Integer readStatus;
    volatile String readBody;
    volatile String readContentType = "application/json";
    final List<String> readAuthorizations = new CopyOnWriteArrayList<>();

    FakePlatform() throws Exception {
        char[] pw = "changeit".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystore())) {
            ks.load(in, pw);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, pw);
        SSLContext serverSsl = SSLContext.getInstance("TLS");
        serverSsl.init(kmf.getKeyManagers(), null, null);

        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        trust.setCertificateEntry("lab", ks.getCertificate("t"));
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        clientTrust = SSLContext.getInstance("TLS");
        clientTrust.init(null, tmf.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 16);
        server.setHttpsConfigurator(new HttpsConfigurator(serverSsl));
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "platform-simulada");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    /** Certificado autoassinado para "localhost", criado uma vez por execucao dos testes. */
    private static synchronized Path keystore() throws Exception {
        if (keystore == null) {
            Path dir = Files.createTempDirectory("client-tls");
            Path f = dir.resolve("servidor.p12");
            Path keytool = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase().contains("win") ? "keytool.exe" : "keytool");
            Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "t", "-keyalg", "EC", "-groupname", "secp256r1", "-dname",
                    "CN=localhost", "-ext", "SAN=dns:localhost", "-validity", "2", "-storetype", "PKCS12", "-keystore", f.toString(), "-storepass",
                    "changeit").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("keytool falhou");
            }
            f.toFile().deleteOnExit();
            dir.toFile().deleteOnExit();
            keystore = f;
        }
        return keystore;
    }

    int port() {
        return server.getAddress().getPort();
    }

    String issuer() {
        return "https://localhost:" + port();
    }

    PlatformOrigin origin() {
        Properties p = new Properties();
        p.setProperty(PlatformOrigin.BROWSER_PROPERTY, issuer());
        p.setProperty(PlatformOrigin.API_PROPERTY, issuer());
        return PlatformOrigin.resolve(p);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    static String token() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    // ------------------------------------------------------------------ o que a pagina da Web + API fariam

    /**
     * "A pessoa autorizou no navegador": valida o pedido como a API e devolve o retorno ao aplicativo, com o codigo. Pedido
     * fora da regra: {@link IllegalArgumentException} (a API responderia 400, sem redirecionar).
     */
    String authorize(URI page) {
        Map<String, String> q = query(page.getRawQuery());
        if (!"/entrar/aplicativo".equals(page.getPath()) || q.size() != 7 || !"code".equals(q.get("response_type"))
                || !"nekkora-agent-client".equals(q.get("client_id")) || !"identity navigation".equals(q.get("scope"))
                || !"S256".equals(q.get("code_challenge_method")) || !q.get("code_challenge").matches("[A-Za-z0-9_-]{43}")
                || !q.get("state").matches("[A-Za-z0-9_-]{22,128}") || !q.get("redirect_uri").matches("http://127\\.0\\.0\\.1:[1-9][0-9]{3,4}/callback")) {
            throw new IllegalArgumentException("pedido fora do contrato: " + page);
        }
        String code = token();
        codes.put(code, new Grant(q.get("code_challenge"), q.get("redirect_uri"), email));
        return q.get("redirect_uri") + "?code=" + code + "&state=" + enc(q.get("state")) + "&iss=" + enc(issuer());
    }

    /** "A pessoa cancelou no navegador". */
    String deny(URI page) {
        Map<String, String> q = query(page.getRawQuery());
        return q.get("redirect_uri") + "?error=access_denied&state=" + enc(q.get("state")) + "&iss=" + enc(issuer());
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int eq = pair.indexOf('=');
                if (m.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)) != null) {
                    throw new IllegalArgumentException("parametro repetido");
                }
            }
        }
        return m;
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ rotas do aplicativo

    private void handle(HttpExchange x) throws IOException {
        try {
            String path = x.getRequestURI().getPath();
            requests.add(x.getRequestMethod() + " " + path);
            if (x.getRequestHeaders().containsKey("Cookie")) {
                reply(x, 418, "text/plain", "o aplicativo nao envia cookies");
                return;
            }
            switch (x.getRequestMethod() + " " + path) {
                case "POST /api/v1/desktop/token" -> token(x);
                case "GET /api/v1/desktop/session" -> session(x);
                case "GET /api/v1/desktop/me", "GET /api/v1/desktop/organizations" -> read(x, path);
                case "POST /api/v1/desktop/session/revoke" -> {
                    String t = bearer(x);
                    if (t != null && sessions.remove(t) != null) {
                        revoked.add(t);
                    }
                    x.sendResponseHeaders(204, -1);
                }
                default -> reply(x, 404, "application/json", "{\"error\":\"not_found\"}");
            }
        } finally {
            x.close();
        }
    }

    private void token(HttpExchange x) throws IOException {
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        tokenBodies.add(body);
        if (tokenDelayMillis > 0) {
            try {
                Thread.sleep(tokenDelayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (tokenStatus != null || tokenBody != null) {
            reply(x, tokenStatus == null ? 200 : tokenStatus, tokenContentType, tokenBody == null ? "{}" : tokenBody);
            return;
        }
        Map<String, String> f = query(body);
        String type = x.getRequestHeaders().getFirst("Content-Type");
        if (!"application/x-www-form-urlencoded".equals(type) || !"authorization_code".equals(f.get("grant_type")) || f.get("code_verifier") == null) {
            reply(x, 400, "application/json", "{\"error\":\"invalid_request\"}");
            return;
        }
        Grant g = codes.remove(f.get("code"));
        if (g == null) {
            String old = usedCodes.get(String.valueOf(f.get("code")));
            if (old != null && sessions.remove(old) != null) {
                revoked.add(old); // reuso do codigo revoga a sessao que ele emitiu
            }
            reply(x, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            return;
        }
        if (!g.redirectUri().equals(f.get("redirect_uri")) || !"nekkora-agent-client".equals(f.get("client_id"))
                || !g.challenge().equals(Pkce.s256(f.get("code_verifier")))) {
            reply(x, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            return;
        }
        String t = token();
        sessions.put(t, g.email());
        usedCodes.put(f.get("code"), t);
        reply(x, 200, "application/json", "{\"access_token\":\"" + t + "\",\"token_type\":\"Bearer\",\"expires_in\":" + expiresIn + ",\"scope\":\"identity navigation\"}");
    }

    private void session(HttpExchange x) throws IOException {
        if (sessionStatus != null || sessionBody != null) {
            reply(x, sessionStatus == null ? 200 : sessionStatus, "application/json", sessionBody == null ? "{}" : sessionBody);
            return;
        }
        String t = bearer(x);
        String who = t == null ? null : sessions.get(t);
        if (who == null) {
            reply(x, 401, "application/json", "{\"error\":\"unauthenticated\"}");
            return;
        }
        reply(x, 200, "application/json", "{\"account\":{\"email\":\"" + who + "\"},\"scope\":\"identity navigation\",\"expiresIn\":" + expiresIn + "}");
    }

    private void read(HttpExchange x, String path) throws IOException {
        readAuthorizations.add(String.valueOf(x.getRequestHeaders().getFirst("Authorization")));
        if (readStatus != null || readBody != null) {
            reply(x, readStatus == null ? 200 : readStatus, readContentType, readBody == null ? "{}" : readBody);
            return;
        }
        String t = bearer(x);
        String who = t == null ? null : sessions.get(t);
        if (who == null) {
            reply(x, 401, "application/json", "{\"error\":\"unauthenticated\"}");
            return;
        }
        if (path.endsWith("/me")) {
            reply(x, 200, "application/json", "{\"email\":\"" + who + "\",\"displayName\":" + (displayName == null ? "null" : "\"" + displayName + "\"") + "}");
        } else {
            reply(x, 200, "application/json", organizationsBody);
        }
    }

    private static String bearer(HttpExchange x) {
        String h = x.getRequestHeaders().getFirst("Authorization");
        return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
    }

    private static void reply(HttpExchange x, int status, String type, String body) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", type);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(status, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }

    List<String> requestsTo(String route) {
        List<String> l = new ArrayList<>();
        for (String r : requests) {
            if (r.equals(route)) {
                l.add(r);
            }
        }
        return l;
    }
}
