package com.nekkora.client.remote;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import com.nekkora.client.auth.PlatformOrigin;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Canal da maquina SIMULADO para os testes do Client (fica em src/test; nao entra no aplicativo). Fala HTTPS de verdade,
 * com um certificado criado na hora, e segue o contrato da maquina do lado do servidor no que a habilitacao usa: resgate
 * com a intencao declarada, presenca, pausar/retomar e encerramento do vinculo. Cada teste pode trocar uma resposta.
 *
 * <p>A prova contra o gateway REAL esta nos testes da API (RemoteEnablementIT) e no aceite do laboratorio.
 */
public final class FakeGateway implements AutoCloseable {

    public record Call(String path, String contract, String intent, String authorization, String body) {
    }

    private static Path keystore;

    final HttpsServer server;
    public final SSLContext clientTrust;
    /** Codigos validos: codigo -> "remote" | "plain" | "rotation". Usado com sucesso, sai daqui e fica em {@link #consumed}. */
    public final Map<String, String> codes = new ConcurrentHashMap<>();
    /** codigo consumido -> segredo com que foi resgatado (a repeticao com o MESMO segredo devolve a mesma identidade). */
    final Map<String, String> consumed = new ConcurrentHashMap<>();
    public final List<Call> calls = new CopyOnWriteArrayList<>();

    public final String organizationId = UUID.randomUUID().toString();
    public final String agentId = UUID.randomUUID().toString();
    public final String workerId = UUID.randomUUID().toString();
    public final String credentialId = UUID.randomUUID().toString();
    public volatile String credentialExpiresAt = "2027-02-13T12:00:00Z";
    /** A credencial que o "servidor" reconhece; nula = nenhuma (vinculo encerrado). */
    public volatile String credential;
    public volatile boolean featureOn = true;
    public volatile boolean enabled;
    public volatile boolean paused;
    public volatile boolean workerTaken;
    /** Substitui a resposta de TODA rota, quando nao nulo. */
    public volatile Integer status;
    public volatile String body;
    public volatile String contentType = "application/json";
    /** O resgate e processado, mas a conexao cai antes da resposta (uma vez). */
    public volatile boolean dropRedeemResponse;
    /** O ultimo inventario de Python recebido (corpo do pedido). */
    public volatile String inventory;
    /** Renovacao: a credencial esta na janela do servidor? */
    public volatile boolean renewalDue = true;
    /**
     * A renovacao e processada, mas a conexao cai antes da resposta, por este numero de pedidos. O cliente HTTP do JDK repete
     * sozinho, uma vez, um pedido que falha numa conexao reaproveitada: para perder a resposta de verdade, o teste derruba 2.
     */
    public volatile int dropRenewResponses;
    /** credencial anterior (revogada por uma renovacao) -> segredo novo com que foi trocada. */
    final Map<String, String> renewedFrom = new ConcurrentHashMap<>();
    public volatile String renewedCredentialId = UUID.randomUUID().toString();
    public volatile String renewedExpiresAt = "2027-05-14T12:00:00Z";
    /** O 409 do encerramento, quando nao nulo (vinculo ja encerrado de outro modo). */
    public volatile String unlinkConflict;
    public final List<String> unlinkOperations = new CopyOnWriteArrayList<>();

    public FakeGateway() throws Exception {
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
            Thread t = new Thread(r, "gateway-simulado");
            t.setDaemon(true);
            return t;
        }));
        server.start();
    }

    private static synchronized Path keystore() throws Exception {
        if (keystore == null) {
            Path dir = Files.createTempDirectory("client-gw-tls");
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

    public PlatformOrigin origin() {
        String o = "https://localhost:" + server.getAddress().getPort();
        return new PlatformOrigin(o, o);
    }

    public MachineChannel channel() {
        return new MachineChannel(origin(), clientTrust, java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5));
    }

    /** Um codigo no formato do contrato, registrado como valido para o proposito dado. */
    public String code(String kind) {
        String c = "nkp1." + organizationId + "." + UUID.randomUUID() + "." + "A".repeat(43);
        codes.put(c, kind);
        return c;
    }

    public long count(String path) {
        return calls.stream().filter(c -> c.path().equals(path)).count();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange x) throws IOException {
        String path = x.getRequestURI().getPath();
        String text = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String auth = x.getRequestHeaders().getFirst("Authorization");
        calls.add(new Call(path, x.getRequestHeaders().getFirst("Nekkora-Agent-Contract"), x.getRequestHeaders().getFirst("Nekkora-Agent-Intent"), auth, text));
        if (status != null) {
            respond(x, status, body == null ? "{}" : body);
            return;
        }
        boolean put = "PUT".equals(x.getRequestMethod()) && path.equals("/agent/api/v1/remote/python-environments");
        if (!("POST".equals(x.getRequestMethod()) || put) || !"1.0".equals(x.getRequestHeaders().getFirst("Nekkora-Agent-Contract"))) {
            respond(x, 400, "{\"error\":\"unsupported_contract\"}");
            return;
        }
        switch (path) {
            case "/agent/api/v1/enrollments/redeem" -> redeem(x, text);
            case "/agent/api/v1/heartbeat" -> {
                if (authenticated(x, auth)) {
                    respond(x, 200, "{\"serverTime\":\"2026-11-15T12:00:00Z\",\"credentialExpiresAt\":\"" + credentialExpiresAt + "\",\"contractVersion\":\"1.0\""
                            + (enabled ? ",\"remote\":{\"receiving\":\"" + (paused ? "PAUSED" : "ENABLED") + "\"}" : "") + "}");
                }
            }
            case "/agent/api/v1/remote/receiving" -> {
                if (authenticated(x, auth)) {
                    if (!enabled) {
                        respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_not_enabled\"}");
                    } else if (text.contains("\"PAUSED\"") || text.contains("\"ENABLED\"")) {
                        paused = text.contains("\"PAUSED\"");
                        respond(x, 200, "{\"receiving\":\"" + (paused ? "PAUSED" : "ENABLED") + "\"}");
                    } else {
                        respond(x, 400, "{\"error\":\"invalid_request\"}");
                    }
                }
            }
            case "/agent/api/v1/remote/credential/renew" -> renew(x, text, auth);
            case "/agent/api/v1/remote/python-environments" -> {
                if (authenticated(x, auth)) {
                    if (!enabled) {
                        respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_not_enabled\"}");
                    } else {
                        inventory = text;
                        int count = text.split("\"environmentId\"", -1).length - 1;
                        respond(x, 200, "{\"count\":" + count + ",\"reportedAt\":\"2026-11-15T12:00:00Z\"}");
                    }
                }
            }
            case "/agent/api/v1/link/unlink" -> {
                String op = field(text, "operationId");
                unlinkOperations.add(op);
                if (unlinkConflict != null) {
                    respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"" + unlinkConflict + "\"}");
                } else if (authenticated(x, auth)) {
                    if (!agentId.equals(field(text, "agentId")) || !text.contains("\"launchedRunRequests\":[]")) {
                        respond(x, 400, "{\"error\":\"invalid_request\"}");
                        return;
                    }
                    credential = null;
                    enabled = false;
                    respond(x, 200, "{\"operationId\":\"" + op + "\",\"outcome\":\"UNLINKED\",\"agentId\":\"" + agentId + "\",\"workerId\":\"" + workerId
                            + "\",\"unlinkedAt\":\"2026-11-15T12:00:00Z\",\"runRequestsClosed\":[],\"runRequestsKeptAccepted\":[]}");
                }
            }
            default -> respond(x, 404, "{\"error\":\"not_found\"}");
        }
    }

    private void redeem(HttpExchange x, String text) throws IOException {
        String code = field(text, "code");
        String secret = field(text, "credentialSecret");
        String intent = x.getRequestHeaders().getFirst("Nekkora-Agent-Intent");
        if (secret.equals(consumed.get(code))) {
            respond(x, 200, identity());
            return;
        }
        String kind = codes.get(code);
        if (kind == null || !secret.matches("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")) {
            respond(x, 401, "{\"error\":\"unauthenticated\"}");
            return;
        }
        String refusal = null;
        if (kind.equals("rotation")) {
            refusal = "rotation_code_for_pairing";
        } else if (kind.equals("remote") && !"pair-remote".equals(intent)) {
            refusal = "remote_code_without_enablement";
        } else if (kind.equals("plain") && "pair-remote".equals(intent)) {
            refusal = "code_not_for_remote_execution";
        } else if (kind.equals("remote") && !featureOn) {
            refusal = "remote_execution_unavailable";
        }
        if (refusal != null) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"" + refusal + "\"}");
            return;
        }
        if (workerTaken) {
            respond(x, 409, "{\"error\":\"conflict\"}");
            return;
        }
        codes.remove(code);
        consumed.put(code, secret);
        credential = "nka1." + organizationId + "." + credentialId + "." + secret;
        enabled = kind.equals("remote");
        paused = false;
        if (dropRedeemResponse) {
            dropRedeemResponse = false;
            x.close();
            return;
        }
        respond(x, 200, identity());
    }

    /** Como o servidor real: troca numa vez so; a repeticao com a credencial anterior e o MESMO segredo devolve a mesma nova. */
    private void renew(HttpExchange x, String text, String auth) throws IOException {
        String secret = field(text, "credentialSecret");
        String bearer = auth == null ? "" : auth.replaceFirst("^Bearer ", "");
        String answer = "{\"credentialId\":\"" + renewedCredentialId + "\",\"credentialExpiresAt\":\"" + renewedExpiresAt + "\"}";
        if (secret.equals(renewedFrom.get(bearer)) && credential != null && credential.endsWith("." + secret)) {
            if (dropRenewResponses > 0) {
                dropRenewResponses--;
                x.close();
                return;
            }
            respond(x, 200, answer);
            return;
        }
        if (!authenticated(x, auth)) {
            return;
        }
        if (!featureOn) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_unavailable\"}");
        } else if (!enabled) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_not_enabled\"}");
        } else if (!secret.matches("^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$") || credential.endsWith("." + secret)) {
            respond(x, 400, "{\"error\":\"invalid_request\"}");
        } else if (!renewalDue) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"credential_renewal_not_due\"}");
        } else {
            renewedFrom.put(credential, secret);
            credential = "nka1." + organizationId + "." + renewedCredentialId + "." + secret;
            credentialExpiresAt = renewedExpiresAt;
            renewalDue = false;
            if (dropRenewResponses > 0) {
                dropRenewResponses--;
                x.close();
                return;
            }
            respond(x, 200, answer);
        }
    }

    private String identity() {
        return "{\"organizationId\":\"" + organizationId + "\",\"agentId\":\"" + agentId + "\",\"installationId\":\"" + UUID.randomUUID() + "\",\"workerId\":\""
                + workerId + "\",\"credentialId\":\"" + credentialId + "\",\"credentialExpiresAt\":\"" + credentialExpiresAt + "\",\"contractVersion\":\"1.0\""
                + (enabled ? ",\"remoteExecution\":true" : "") + "}";
    }

    private boolean authenticated(HttpExchange x, String auth) throws IOException {
        if (credential == null || !("Bearer " + credential).equals(auth)) {
            respond(x, 401, "{\"error\":\"unauthenticated\"}");
            return false;
        }
        return true;
    }

    private static String field(String json, String name) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"" + name + "\":\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    private void respond(HttpExchange x, int code, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", contentType);
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.sendResponseHeaders(code, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }
}
