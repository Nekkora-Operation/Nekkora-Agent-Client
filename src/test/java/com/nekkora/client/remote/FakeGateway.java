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

    // ---- entrega (T02): no maximo uma tentativa, como no servidor real
    /** A tentativa desta maquina; nula = nao ha. */
    public volatile String attemptId;
    /** QUEUED, OFFERED, RECEIVED, REFUSED, CANCELLED... */
    public volatile String attemptState;
    public volatile String attemptReason;
    public volatile String attemptToken;
    public volatile int attemptEpoch;
    public volatile boolean cancelRequested;
    /** A ultima presenca declarou a capacidade de execucao remota? Sem ela, nada e entregue. */
    public volatile boolean declaredRun;
    public volatile String lastHeartbeat;
    /** Confirmacoes processadas cuja resposta se perde (o cliente do JDK repete uma vez sozinho: para perder de verdade, 2). */
    public volatile int dropAckResponses;
    /** Respostas 503 para as proximas confirmacoes / informacoes de estado (nada e processado). */
    public volatile int failAcks;
    public volatile int failStates;
    /** A entrega devolve um token fora do formato. */
    public volatile boolean malformedOffer;
    public final String remoteExecutionId = UUID.randomUUID().toString();
    public final String automationId = UUID.randomUUID().toString();
    public final String automationVersionId = UUID.randomUUID().toString();
    public final String workspaceId = UUID.randomUUID().toString();
    /** O que a entrega diz do Python escolhido (identificador opaco) e da regra do pacote; nulo = campo ausente. */
    public volatile String offerPython;
    public volatile String offerRule = "nekkora-agent/external-python-fingerprint/1";
    public volatile boolean offerWorkspace = true;
    /** Os bytes do pacote da tentativa e o SHA-256 que o "servidor" declara para eles (nulo = o verdadeiro). */
    public volatile byte[] packageBytes = "PK pacote sintetico".getBytes(StandardCharsets.US_ASCII);
    public volatile String declaredSha256;
    /** O download responde 409 remote_package_unavailable. */
    public volatile boolean packageUnavailable;
    /** Antes de iniciar, o "servidor" recusa com este motivo (revalidacao). */
    public volatile String refuseStart;
    public volatile Integer attemptExit;
    /** Os estados que a maquina informou, na ordem. */
    public final List<String> reported = new CopyOnWriteArrayList<>();

    public static String sha256(byte[] data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resultados recebidos: numero -> "nome|sha256|tamanho". */
    public final Map<Integer, String> results = new ConcurrentHashMap<>();
    /** O fechamento recebido: "enviados|foraDoLimite"; nulo = o envio nao foi fechado. */
    public volatile String resultsClosure;
    /** Respostas 503 para os proximos envios de resultado (nada e processado). */
    public volatile int failResults;
    /** Um nome que o "servidor" recusa por limite (409 remote_result_limit). */
    public volatile String resultOverLimit;

    private void result(HttpExchange x, String path, String text, byte[] raw) throws IOException {
        String[] parts = path.substring("/agent/api/v1/remote/attempts/".length()).split("/");
        if (!parts[0].equals(attemptId)) {
            respond(x, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (failResults > 0) {
            failResults--;
            respond(x, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        boolean complete = parts[2].equals("complete");
        String token = complete ? field(text, "token") : x.getRequestHeaders().getFirst("Nekkora-Attempt-Token");
        String epoch = complete ? text.replaceAll(".*\"epoch\":(\\d+).*", "$1") : x.getRequestHeaders().getFirst("Nekkora-Attempt-Epoch");
        if (attemptToken == null || !attemptToken.equals(token) || !String.valueOf(attemptEpoch).equals(epoch)) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_stale\"}");
            return;
        }
        boolean ended = List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ENDED_UNKNOWN").contains(attemptState);
        if (complete) {
            String sent = text.replaceAll(".*\"sent\":(\\d+).*", "$1");
            String skipped = text.replaceAll(".*\"skippedByLimit\":(\\d+).*", "$1");
            if (!ended) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
            } else if ((resultsClosure != null && !resultsClosure.equals(sent + "|" + skipped)) || Integer.parseInt(sent) != results.size()) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_result_conflict\"}");
            } else {
                resultsClosure = sent + "|" + skipped;
                respond(x, 200, "{\"sent\":" + sent + ",\"skippedByLimit\":" + skipped + "}");
            }
            return;
        }
        int number = Integer.parseInt(parts[2]);
        String name = java.net.URLDecoder.decode(x.getRequestHeaders().getFirst("Nekkora-Result-Name"), StandardCharsets.UTF_8);
        String sha = x.getRequestHeaders().getFirst("Nekkora-Result-Sha256");
        if (!sha256(raw).equals(sha) || name.contains("..") || name.contains("\\") || name.startsWith("/")) {
            respond(x, 400, "{\"error\":\"invalid_request\"}");
        } else if (name.equals(resultOverLimit)) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_result_limit\"}");
        } else if (resultsClosure != null || (results.containsKey(number) && !results.get(number).equals(name + "|" + sha + "|" + raw.length))) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_result_conflict\"}");
        } else {
            boolean created = results.put(number, name + "|" + sha + "|" + raw.length) == null;
            respond(x, 200, "{\"number\":" + number + ",\"fileName\":\"x\",\"sizeBytes\":" + raw.length + ",\"sha256\":\"" + sha + "\",\"created\":" + created + "}");
        }
    }

    private void pack(HttpExchange x, String path) throws IOException {
        String id = path.substring("/agent/api/v1/remote/attempts/".length()).split("/")[0];
        if (!id.equals(attemptId)) {
            respond(x, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (attemptToken == null || !attemptToken.equals(x.getRequestHeaders().getFirst("Nekkora-Attempt-Token"))
                || !String.valueOf(attemptEpoch).equals(x.getRequestHeaders().getFirst("Nekkora-Attempt-Epoch"))) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_stale\"}");
            return;
        }
        if (!attemptState.equals("RECEIVED") && !attemptState.equals("PREPARING")) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
            return;
        }
        if (packageUnavailable) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_package_unavailable\"}");
            return;
        }
        byte[] b = packageBytes;
        x.getResponseHeaders().set("Content-Type", "application/zip");
        x.getResponseHeaders().set("Cache-Control", "no-store");
        x.getResponseHeaders().set("Nekkora-Package-Sha256", declaredSha256 == null ? sha256(b) : declaredSha256);
        x.sendResponseHeaders(200, b.length);
        try (OutputStream out = x.getResponseBody()) {
            out.write(b);
        }
    }

    /** Uma solicitacao aceita para esta maquina, ainda nao entregue. */
    public String queue() {
        attemptId = UUID.randomUUID().toString();
        attemptState = "QUEUED";
        attemptReason = null;
        attemptToken = null;
        attemptEpoch = 0;
        cancelRequested = false;
        results.clear();
        resultsClosure = null;
        return attemptId;
    }

    private static String newToken() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 43);
    }

    private void next(HttpExchange x) throws IOException {
        if (!enabled) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_not_enabled\"}");
            return;
        }
        if (attemptId == null) {
            respond(x, 200, "{}");
            return;
        }
        if (attemptState.equals("QUEUED") || attemptState.equals("OFFERED")) {
            if (cancelRequested) {
                attemptState = "CANCELLED";
                respond(x, 200, "{}");
                return;
            }
            if (paused || !declaredRun) {
                respond(x, 200, "{}");
                return;
            }
            attemptToken = newToken();
            attemptEpoch++;
            attemptState = "OFFERED";
            respond(x, 200, "{\"attempt\":{\"attemptId\":\"" + attemptId + "\",\"remoteExecutionId\":\"" + remoteExecutionId + "\",\"attemptNumber\":1,"
                    + "\"automationId\":\"" + automationId + "\",\"automationVersionId\":\"" + automationVersionId + "\",\"packageFingerprint\":\""
                    + "c".repeat(64) + "\",\"packageFingerprintRule\":\"" + offerRule + "\",\"dependencies\":[\"requests==2.32.3\"],"
                    + (offerWorkspace ? "\"workspaceId\":\"" + workspaceId + "\"," : "")
                    + (offerPython == null ? "" : "\"pythonEnvironmentId\":\"" + offerPython + "\",")
                    + "\"token\":\"" + (malformedOffer ? "nao e um token" : attemptToken) + "\",\"epoch\":" + attemptEpoch + ",\"leaseSeconds\":90}}");
            return;
        }
        boolean live = List.of("RECEIVED", "PREPARING", "STARTING", "RUNNING").contains(attemptState);
        respond(x, 200, live && cancelRequested ? "{\"cancel\":{\"attemptId\":\"" + attemptId + "\"}}" : "{}");
    }

    private void attempt(HttpExchange x, String path, String text) throws IOException {
        String[] parts = path.substring("/agent/api/v1/remote/attempts/".length()).split("/");
        if (parts.length != 2 || !parts[0].equals(attemptId)) {
            respond(x, 404, "{\"error\":\"not_found\"}");
            return;
        }
        boolean ack = parts[1].equals("ack");
        if (ack ? failAcks > 0 : failStates > 0) {
            if (ack) {
                failAcks--;
            } else {
                failStates--;
            }
            respond(x, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        java.util.regex.Matcher epoch = java.util.regex.Pattern.compile("\"epoch\":(\\d+)").matcher(text);
        if (attemptToken == null || !attemptToken.equals(field(text, "token")) || !epoch.find() || Integer.parseInt(epoch.group(1)) != attemptEpoch) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_stale\"}");
            return;
        }
        boolean ended = List.of("REFUSED", "CANCELLED", "EXPIRED", "SUCCEEDED", "FAILED", "TIMED_OUT", "ENDED_UNKNOWN").contains(attemptState);
        boolean proceed;
        if (ack) {
            if (attemptState.equals("OFFERED")) {
                attemptState = "RECEIVED";
            }
            proceed = attemptState.equals("RECEIVED");
            if (dropAckResponses > 0) {
                dropAckResponses--;
                x.close();
                return;
            }
        } else if (ended) {
            proceed = false;
        } else {
            String wanted = field(text, "state");
            String reason = field(text, "refusalReason");
            reported.add(wanted);
            java.util.regex.Matcher exit = java.util.regex.Pattern.compile("\"exitCode\":(-?\\d+)").matcher(text);
            // as mesmas transicoes do servidor real (046)
            List<String> next = switch (attemptState) {
                case "RECEIVED" -> List.of("PREPARING", "CANCELLED", "REFUSED", "ENDED_UNKNOWN");
                case "PREPARING" -> List.of("STARTING", "CANCELLED", "REFUSED", "ENDED_UNKNOWN");
                case "STARTING" -> List.of("RUNNING", "FAILED", "CANCELLED", "REFUSED", "ENDED_UNKNOWN");
                case "RUNNING" -> List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ENDED_UNKNOWN");
                default -> List.<String>of();
            };
            if (wanted.equals(attemptState)) {
                proceed = true;
            } else if (!next.contains(wanted) || wanted.equals("REFUSED") == reason.isEmpty()
                    || (exit.find() && !List.of("SUCCEEDED", "FAILED", "TIMED_OUT").contains(wanted))) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
                return;
            } else if (wanted.equals("STARTING") && cancelRequested) {
                attemptState = "CANCELLED";
                proceed = false;
            } else if (wanted.equals("STARTING") && refuseStart != null) {
                attemptState = "REFUSED";
                attemptReason = refuseStart;
                proceed = false;
            } else {
                attemptState = wanted;
                attemptReason = reason.isEmpty() ? null : reason;
                exit.reset();
                attemptExit = exit.find() ? Integer.valueOf(exit.group(1)) : null;
                proceed = List.of("PREPARING", "STARTING", "RUNNING").contains(wanted);
            }
        }
        respond(x, 200, "{\"attemptId\":\"" + attemptId + "\",\"state\":\"" + attemptState + "\",\"proceed\":" + proceed
                + (attemptReason == null ? "" : ",\"refusalReason\":\"" + attemptReason + "\"") + ",\"cancelRequested\":" + cancelRequested
                + ",\"leaseSeconds\":90}");
    }

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
        byte[] raw = x.getRequestBody().readAllBytes();
        String text = new String(raw, StandardCharsets.UTF_8);
        String auth = x.getRequestHeaders().getFirst("Authorization");
        calls.add(new Call(path, x.getRequestHeaders().getFirst("Nekkora-Agent-Contract"), x.getRequestHeaders().getFirst("Nekkora-Agent-Intent"), auth, text));
        if (status != null) {
            respond(x, status, body == null ? "{}" : body);
            return;
        }
        boolean put = "PUT".equals(x.getRequestMethod()) && path.equals("/agent/api/v1/remote/python-environments");
        boolean get = "GET".equals(x.getRequestMethod()) && path.equals("/agent/api/v1/remote/next");
        if (get && "1.0".equals(x.getRequestHeaders().getFirst("Nekkora-Agent-Contract"))) {
            if (authenticated(x, auth)) {
                next(x);
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && path.endsWith("/package") && "GET".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                pack(x, path);
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && path.contains("/results/")
                && ("PUT".equals(x.getRequestMethod()) || "POST".equals(x.getRequestMethod()))) {
            if (authenticated(x, auth)) {
                result(x, path, text, raw);
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && "POST".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                attempt(x, path, text);
            }
            return;
        }
        if (!("POST".equals(x.getRequestMethod()) || put) || !"1.0".equals(x.getRequestHeaders().getFirst("Nekkora-Agent-Contract"))) {
            respond(x, 400, "{\"error\":\"unsupported_contract\"}");
            return;
        }
        switch (path) {
            case "/agent/api/v1/enrollments/redeem" -> redeem(x, text);
            case "/agent/api/v1/heartbeat" -> {
                if (authenticated(x, auth)) {
                    declaredRun = text.contains("\"REMOTE_RUN_1_0\"");
                    lastHeartbeat = text;
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
