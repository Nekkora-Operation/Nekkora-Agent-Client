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
    /** O resgate do codigo responde 409 com este motivo (para o teste do motivo que o aplicativo nao conhece). */
    public volatile String redeemConflict;
    /** O nome do Worker desta maquina, como a presenca o devolve; nulo = a "Platform" nao informa (API anterior). */
    public volatile String workerName;
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
    /** O ultimo relato de estado afirmou que o processo da tentativa nao esta mais em execucao na maquina? */
    public volatile boolean lastProcessAbsent;
    /** O que o ultimo relato de estado disse dos processos da tentativa (ABSENT, ACTIVE, UNCERTAIN, ou vazio). */
    public volatile String lastProcessTree = "";
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
    /** Os relatos de estado recebidos DEPOIS de a tentativa ter terminado (com "+ausente" quando afirmam o processo ausente). */
    public final List<String> afterEnd = new CopyOnWriteArrayList<>();

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
    /** O "servidor" confirma um arquivo com OUTRO tamanho (confirmacao que nao corresponde ao que foi enviado). */
    public volatile boolean wrongResultSize;
    /** O "servidor" aceita o fechamento mas responde com OUTRA contagem. */
    public volatile boolean wrongClosure;
    /** Um nome que o "servidor" recusa por limite (409 remote_result_limit). */
    public volatile String resultOverLimit;

    /** Registros recebidos: sequencia -> "canal|cortada|mensagem". */
    public final Map<Integer, String> logs = new java.util.concurrent.ConcurrentSkipListMap<>();
    /** O fechamento dos registros: "linhas|descartadas"; nulo = nao fechado. */
    public volatile String logsClosure;
    /** Respostas 503 para os proximos envios de registros (nada e processado). */
    public volatile int failLogs;
    /** Quantas linhas o "servidor" aceita por tentativa; lote que passaria: 409 remote_log_limit, nada gravado. */
    public volatile int logLimit = Integer.MAX_VALUE;
    /** O "servidor" aceita o fechamento dos registros mas responde com OUTRA contagem. */
    public volatile boolean wrongLogsClosure;
    /** Quantos lotes de registros chegaram (aceitos ou nao). */
    public final java.util.concurrent.atomic.AtomicInteger logBatches = new java.util.concurrent.atomic.AtomicInteger();

    /** Avisos de perda por prazo de guarda aceitos: {@code tentativa|tipo|quanto havia}. */
    public final List<String> losses = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Quantos avisos de perda falham (503) antes de voltar a aceitar. */
    public volatile int failLoss;

    private void loss(HttpExchange x, String path, String text) throws IOException {
        String attempt = path.substring("/agent/api/v1/remote/attempts/".length()).split("/")[0];
        if (failLoss > 0) {
            failLoss--;
            respond(x, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        if (!attempt.equals(attemptId)) {
            respond(x, 404, "{\"error\":\"not_found\"}");
            return;
        }
        Map<String, Object> body = com.nekkora.client.auth.Json.object(text);
        if (attemptToken == null || !attemptToken.equals(body.get("token")) || ((Number) body.get("epoch")).intValue() != attemptEpoch) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_stale\"}");
            return;
        }
        if (!List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ENDED_UNKNOWN").contains(attemptState)) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
            return;
        }
        String kind = String.valueOf(body.get("kind"));
        int count = ((Number) body.get("localCount")).intValue();
        if (losses.stream().noneMatch(l -> l.startsWith(attempt + "|" + kind + "|"))) {
            losses.add(attempt + "|" + kind + "|" + count);
        }
        respond(x, 200, "{\"kind\":\"" + kind + "\",\"localCount\":" + count + ",\"reportedAt\":\"2026-11-16T12:00:00Z\"}");
    }

    @SuppressWarnings("unchecked")
    private void logs(HttpExchange x, String path, String text) throws IOException {
        String[] parts = path.substring("/agent/api/v1/remote/attempts/".length()).split("/");
        if (!parts[0].equals(attemptId)) {
            respond(x, 404, "{\"error\":\"not_found\"}");
            return;
        }
        if (failLogs > 0) {
            failLogs--;
            respond(x, 503, "{\"error\":\"unavailable\"}");
            return;
        }
        Map<String, Object> body;
        try {
            // um lote cheio (200 linhas) passa do limite do leitor pequeno usado nas outras rotas
            body = com.nekkora.client.pack.JsonReader.object(text);
        } catch (com.nekkora.client.pack.JsonReader.Invalid e) {
            respond(x, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        if (attemptToken == null || !attemptToken.equals(body.get("token")) || ((Number) body.get("epoch")).intValue() != attemptEpoch) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_stale\"}");
            return;
        }
        boolean ended = List.of("SUCCEEDED", "FAILED", "CANCELLED", "TIMED_OUT", "ENDED_UNKNOWN").contains(attemptState);
        if (parts.length == 3 && parts[2].equals("complete")) {
            int lines = ((Number) body.get("lines")).intValue();
            long dropped = ((Number) body.get("droppedLines")).longValue();
            if (!ended) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
            } else if ((logsClosure != null && !logsClosure.equals(lines + "|" + dropped)) || lines != logs.size()) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_log_conflict\"}");
            } else {
                logsClosure = lines + "|" + dropped;
                respond(x, 200, "{\"lines\":" + (lines + (wrongLogsClosure ? 1 : 0)) + ",\"droppedLines\":" + dropped + "}");
            }
            return;
        }
        logBatches.incrementAndGet();
        if (!ended && !"RUNNING".equals(attemptState)) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_attempt_transition\"}");
            return;
        }
        List<Map<String, Object>> lines = (List<Map<String, Object>>) body.get("lines");
        if (lines.isEmpty() || lines.size() > 200) {
            respond(x, 400, "{\"error\":\"invalid_request\"}");
            return;
        }
        if (logsClosure != null || ((Number) lines.get(0).get("sequence")).intValue() > logs.size() + 1) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_log_conflict\"}");
            return;
        }
        Map<Integer, String> fresh = new java.util.LinkedHashMap<>();
        for (Map<String, Object> l : lines) {
            int sequence = ((Number) l.get("sequence")).intValue();
            String message = (String) l.get("message");
            if (message.getBytes(StandardCharsets.UTF_8).length > 8192 || message.contains("\n") || !String.valueOf(l.get("capturedAt")).endsWith("Z")) {
                respond(x, 400, "{\"error\":\"invalid_request\"}");
                return;
            }
            String row = l.get("channel") + "|" + l.get("truncated") + "|" + message;
            if (logs.containsKey(sequence)) {
                if (!logs.get(sequence).equals(row)) {
                    respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_log_conflict\"}");
                    return;
                }
            } else if (sequence > logLimit) {
                respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_log_limit\"}");
                return;
            } else {
                fresh.put(sequence, row);
            }
        }
        logs.putAll(fresh);
        respond(x, 200, "{\"received\":" + lines.size() + ",\"stored\":" + fresh.size() + ",\"lastSequence\":" + logs.size() + "}");
    }

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
                respond(x, 200, "{\"sent\":" + (Integer.parseInt(sent) + (wrongClosure ? 1 : 0)) + ",\"skippedByLimit\":" + skipped + "}");
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
            respond(x, 200, "{\"number\":" + number + ",\"fileName\":\"x\",\"sizeBytes\":" + (raw.length + (wrongResultSize ? 1 : 0)) + ",\"sha256\":\"" + sha
                    + "\",\"created\":" + created + "}");
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
    /**
     * Uma tentativa ANTERIOR, ja terminada, que a Platform continua reconhecendo enquanto outra esta ativa: o que a maquina
     * enviar para ela (registros e resultados) cai nos campos {@code earlier*}. Uma so.
     */
    public volatile String earlierId;
    private String earlierState;
    private String earlierToken;
    private int earlierEpoch;
    public final Map<Integer, String> earlierLogs = new java.util.concurrent.ConcurrentSkipListMap<>();
    public volatile String earlierLogsClosure;
    public final Map<Integer, String> earlierResults = new ConcurrentHashMap<>();
    public volatile String earlierResultsClosure;
    public final java.util.concurrent.atomic.AtomicInteger earlierLogBatches = new java.util.concurrent.atomic.AtomicInteger();

    /**
     * Espera o atendimento em curso de um pedido sobre a tentativa anterior terminar. O servidor falso responde ANTES de desfazer
     * a troca interna; sem isto, um teste que le {@code earlier*} logo depois da resposta pode pegar a destroca pela metade.
     */
    public synchronized void settle() {
    }

    /** Guarda a tentativa atual (terminada) como a anterior e enfileira outra. */
    public synchronized String queueKeepingEarlier() {
        earlierId = attemptId;
        earlierState = attemptState;
        earlierToken = attemptToken;
        earlierEpoch = attemptEpoch;
        earlierLogs.clear();
        earlierLogs.putAll(logs);
        earlierLogsClosure = logsClosure;
        earlierResults.clear();
        earlierResults.putAll(results);
        earlierResultsClosure = resultsClosure;
        earlierLogBatches.set(0);
        return queue();
    }

    /** Troca a tentativa atual pela anterior (e de volta): os tratadores de registros e resultados so conhecem "a atual". */
    private synchronized void swapEarlier() {
        String id = attemptId;
        attemptId = earlierId;
        earlierId = id;
        String state = attemptState;
        attemptState = earlierState;
        earlierState = state;
        String token = attemptToken;
        attemptToken = earlierToken;
        earlierToken = token;
        int epoch = attemptEpoch;
        attemptEpoch = earlierEpoch;
        earlierEpoch = epoch;
        Map<Integer, String> l = new java.util.TreeMap<>(logs);
        logs.clear();
        logs.putAll(earlierLogs);
        earlierLogs.clear();
        earlierLogs.putAll(l);
        String lc = logsClosure;
        logsClosure = earlierLogsClosure;
        earlierLogsClosure = lc;
        Map<Integer, String> r = new java.util.TreeMap<>(results);
        results.clear();
        results.putAll(earlierResults);
        earlierResults.clear();
        earlierResults.putAll(r);
        String rc = resultsClosure;
        resultsClosure = earlierResultsClosure;
        earlierResultsClosure = rc;
        int b = logBatches.get();
        logBatches.set(earlierLogBatches.get());
        earlierLogBatches.set(b);
    }

    private boolean forEarlier(String path) {
        String id = earlierId;
        return id != null && path.startsWith("/agent/api/v1/remote/attempts/" + id + "/");
    }

    public String queue() {
        attemptId = UUID.randomUUID().toString();
        attemptState = "QUEUED";
        attemptReason = null;
        attemptToken = null;
        attemptEpoch = 0;
        cancelRequested = false;
        results.clear();
        resultsClosure = null;
        logs.clear();
        logsClosure = null;
        return attemptId;
    }

    private static String newToken() {
        return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "").substring(0, 43);
    }

    /** Um pedido de verificacao de dependencias pendente (o objeto JSON, como a Platform o entrega); nulo = nao ha. */
    public volatile String dependencyCheck;
    /** As respostas de verificacao recebidas: {@code verificacao|corpo}. */
    public final List<String> checkAnswers = new CopyOnWriteArrayList<>();
    /** Respostas 503 para as proximas respostas de verificacao. */
    public volatile int failCheckAnswers;

    private String withCheck(String json) {
        String check = dependencyCheck;
        if (check == null) {
            return json;
        }
        return json.equals("{}") ? "{\"dependencyCheck\":" + check + "}" : json.substring(0, json.length() - 1) + ",\"dependencyCheck\":" + check + "}";
    }

    private void next(HttpExchange x) throws IOException {
        if (!enabled) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_execution_not_enabled\"}");
            return;
        }
        if (attemptId == null) {
            respond(x, 200, withCheck("{}"));
            return;
        }
        if (attemptState.equals("QUEUED") || attemptState.equals("OFFERED")) {
            if (cancelRequested) {
                attemptState = "CANCELLED";
                respond(x, 200, withCheck("{}"));
                return;
            }
            if (paused || !declaredRun) {
                respond(x, 200, withCheck("{}"));
                return;
            }
            attemptToken = newToken();
            attemptEpoch++;
            attemptState = "OFFERED";
            respond(x, 200, withCheck("{\"attempt\":{\"attemptId\":\"" + attemptId + "\",\"remoteExecutionId\":\"" + remoteExecutionId + "\",\"attemptNumber\":1,"
                    + "\"automationId\":\"" + automationId + "\",\"automationVersionId\":\"" + automationVersionId + "\",\"packageFingerprint\":\""
                    + "c".repeat(64) + "\",\"packageFingerprintRule\":\"" + offerRule + "\",\"dependencies\":[\"requests==2.32.3\"],"
                    + (offerWorkspace ? "\"workspaceId\":\"" + workspaceId + "\"," : "")
                    + (offerPython == null ? "" : "\"pythonEnvironmentId\":\"" + offerPython + "\",")
                    + "\"token\":\"" + (malformedOffer ? "nao e um token" : attemptToken) + "\",\"epoch\":" + attemptEpoch + ",\"leaseSeconds\":90}}"));
            return;
        }
        boolean live = List.of("RECEIVED", "PREPARING", "STARTING", "RUNNING").contains(attemptState);
        respond(x, 200, withCheck(live && cancelRequested ? "{\"cancel\":{\"attemptId\":\"" + attemptId + "\"}}" : "{}"));
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
            // relato repetido de uma tentativa ja terminada: nada muda; fica anotado o que foi dito
            afterEnd.add(field(text, "state") + (text.contains("\"processTree\":\"ABSENT\"") ? "+ausente" : "+" + field(text, "processTree")));
            lastProcessAbsent = text.contains("\"processTree\":\"ABSENT\"");
            lastProcessTree = field(text, "processTree");
            proceed = false;
        } else {
            String wanted = field(text, "state");
            String reason = field(text, "refusalReason");
            reported.add(wanted);
            lastProcessAbsent = text.contains("\"processTree\":\"ABSENT\"");
            lastProcessTree = field(text, "processTree");
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
        if (path.equals("/agent/api/v1/remote/dependency-checks/pending") && "GET".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                respond(x, 200, withCheck("{}"));
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/dependency-checks/") && path.endsWith("/answer") && "POST".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                if (failCheckAnswers > 0) {
                    failCheckAnswers--;
                    respond(x, 503, "{\"error\":\"unavailable\"}");
                    return;
                }
                String id = path.substring("/agent/api/v1/remote/dependency-checks/".length(), path.length() - "/answer".length());
                String pending = dependencyCheck;
                if (pending == null || !pending.contains(id)) {
                    respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"remote_dependency_check_closed\"}");
                    return;
                }
                checkAnswers.add(id + "|" + text);
                dependencyCheck = null;
                respond(x, 200, "{\"checkId\":\"" + id + "\",\"outcome\":\"ok\"}");
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && path.endsWith("/package") && "GET".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                pack(x, path);
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && (path.endsWith("/logs") || path.endsWith("/logs/complete"))
                && "POST".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                boolean earlier = forEarlier(path);
                if (earlier) {
                    // a troca, o atendimento (que ja responde) e a destroca sob a MESMA trava: quem ler o estado da tentativa
                    // anterior por settle() so ve o resultado depois de a destroca terminar
                    synchronized (this) {
                        swapEarlier();
                        try {
                            logs(x, path, text);
                        } finally {
                            swapEarlier();
                        }
                    }
                } else {
                    logs(x, path, text);
                }
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && path.endsWith("/retention-loss") && "POST".equals(x.getRequestMethod())) {
            if (authenticated(x, auth)) {
                loss(x, path, text);
            }
            return;
        }
        if (path.startsWith("/agent/api/v1/remote/attempts/") && path.contains("/results/")
                && ("PUT".equals(x.getRequestMethod()) || "POST".equals(x.getRequestMethod()))) {
            if (authenticated(x, auth)) {
                boolean earlier = forEarlier(path);
                if (earlier) {
                    // a troca, o atendimento (que ja responde) e a destroca sob a MESMA trava: quem ler o estado da tentativa
                    // anterior por settle() so ve o resultado depois de a destroca terminar
                    synchronized (this) {
                        swapEarlier();
                        try {
                            result(x, path, text, raw);
                        } finally {
                            swapEarlier();
                        }
                    }
                } else {
                    result(x, path, text, raw);
                }
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
                            + (enabled ? ",\"remote\":{\"receiving\":\"" + (paused ? "PAUSED" : "ENABLED") + "\""
                                    + (workerName == null ? "" : ",\"workerName\":" + com.nekkora.client.auth.Json.quote(workerName)) + "}" : "") + "}");
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
        if (redeemConflict != null) {
            respond(x, 409, "{\"error\":\"conflict\",\"reason\":\"" + redeemConflict + "\"}");
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
