package com.nekkora.client.remote;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;

import com.nekkora.client.auth.Json;
import com.nekkora.client.auth.PlatformOrigin;

/**
 * O canal DA MAQUINA com a Platform (contrato da maquina, versao 1), separado do canal da pessoa ({@code auth}): aqui
 * so circula a credencial da maquina, nunca o token de quem entrou no aplicativo. HTTPS sempre, com o certificado
 * conferido pela JVM, sem cookies e sem seguir redirecionamentos. As rotas sao uma lista fechada:
 * <ul>
 * <li>{@link #redeem}: resgata o codigo de habilitacao, declarando que a confirmacao foi feita NESTE computador;</li>
 * <li>{@link #heartbeat}: presenca; devolve o recebimento, cuja referencia e o servidor;</li>
 * <li>{@link #receiving}: pausar ou retomar o recebimento;</li>
 * <li>{@link #unlink}: encerrar o vinculo deste computador;</li>
 * <li>{@link #renew}: trocar a propria credencial perto do vencimento, sem pessoa e sem codigo;</li>
 * <li>{@link #publishInventory}: dizer quais versoes de Python este computador tem, sem caminhos.</li>
 * </ul>
 * Uma resposta so e aceita com a forma do contrato; qualquer outra coisa e {@link Problem#UNEXPECTED}, nunca sucesso.
 * Nenhuma excecao carrega codigo, segredo ou credencial.
 */
public final class MachineChannel {

    static final String CONTRACT = "1.0";
    static final String REDEEM = "/agent/api/v1/enrollments/redeem";
    static final String HEARTBEAT = "/agent/api/v1/heartbeat";
    static final String RECEIVING = "/agent/api/v1/remote/receiving";
    static final String UNLINK = "/agent/api/v1/link/unlink";
    static final String RENEW = "/agent/api/v1/remote/credential/renew";
    static final String INVENTORY = "/agent/api/v1/remote/python-environments";
    /** Entrega (T02): o que ha para esta maquina. */
    static final String NEXT = "/agent/api/v1/remote/next";
    /** Entrega (T02): confirmar o recebimento ({@code <tentativa>/ack}) e informar o estado ({@code <tentativa>/state}). */
    static final String ATTEMPTS = "/agent/api/v1/remote/attempts/";
    /** A intencao declarada no resgate: habilitar ESTE computador para execucao remota. */
    static final String INTENT = "pair-remote";

    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    /** Por que uma chamada nao deu certo, ja classificado para a tela. */
    public enum Problem {
        NETWORK, TLS, TIMEOUT, SERVER, RATE_LIMITED,
        /** Codigo invalido, vencido, cancelado ou ja usado. */
        CODE_REJECTED,
        /** Codigo de pareamento comum: nao foi criado para habilitar execucao remota. */
        CODE_NOT_REMOTE,
        /** Codigo de renovacao de credencial. */
        CODE_FOR_RENEWAL,
        /** A execucao remota esta desligada nesta Platform. */
        FEATURE_OFF,
        /** O posto de trabalho do codigo ja tem uma maquina. */
        SEAT_TAKEN,
        /** A Platform nao reconhece mais a credencial desta maquina: o vinculo foi encerrado. */
        LINK_ENDED,
        /** A maquina esta vinculada, mas nao habilitada para execucao remota. */
        NOT_ENABLED,
        /** Este computador nao consegue proteger ou gravar a credencial. */
        STORAGE,
        /** Ja ha um vinculo neste computador. */
        ALREADY_ENABLED,
        /** A credencial ainda esta longe do vencimento: a Platform nao a renova agora. */
        RENEWAL_NOT_DUE,
        /** O token ou a epoca desta entrega nao sao mais os vigentes: outra entrega tomou o lugar. Nada foi alterado. */
        ATTEMPT_STALE,
        /** A Platform nao tem essa tentativa para esta maquina. */
        ATTEMPT_GONE,
        /** A Platform nao aceita essa mudanca de estado a partir do estado em que a tentativa esta. */
        ATTEMPT_TRANSITION,
        /** A Platform nao tem os bytes do pacote da tentativa, ou eles nao conferem; ou o que chegou aqui nao confere. */
        PACKAGE_UNAVAILABLE,
        /** O numero do resultado ja tem outro arquivo, ou o envio de resultados desta tentativa ja foi fechado. */
        RESULT_CONFLICT,
        /** Registros: o envio ja foi fechado, ha buraco na sequencia ou a Platform tem outra coisa nessas linhas. */
        LOG_CONFLICT,
        /** Registros: a Platform chegou ao limite de linhas ou de tamanho desta tentativa. */
        LOG_LIMIT,
        /** O arquivo faria a tentativa passar do limite total de resultados: nao foi guardado. */
        RESULT_LIMIT,
        UNEXPECTED
    }

    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;
        private final Problem problem;

        public Refused(Problem problem) {
            super(problem.name(), null, false, false);
            this.problem = problem;
        }

        public Problem problem() {
            return problem;
        }
    }

    record Redeemed(String organizationId, String agentId, String workerId, String credentialId, String credentialExpiresAt) {
    }

    /** {@code paused}: o recebimento segundo o servidor. */
    record Presence(boolean paused, String credentialExpiresAt) {
    }

    private final PlatformOrigin origin;
    private final HttpClient http;
    private final Duration requestTimeout;

    public MachineChannel(PlatformOrigin origin) {
        this(origin, null, Duration.ofSeconds(10), Duration.ofSeconds(20));
    }

    /** {@code ssl} nulo: o contexto padrao da JVM. */
    public MachineChannel(PlatformOrigin origin, SSLContext ssl, Duration connectTimeout, Duration requestTimeout) {
        if (!origin.api().startsWith("https://")) {
            throw new IllegalArgumentException("o canal da maquina e sempre HTTPS");
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

    /** O endereco da Platform deste canal: a credencial guardada so vale para ele. */
    public String origin() {
        return origin.api();
    }

    Redeemed redeem(String code, String secret, String appVersion) throws Refused {
        String body = "{\"code\":" + Json.quote(code) + ",\"credentialSecret\":" + Json.quote(secret) + ",\"agentVersion\":" + Json.quote(appVersion)
                + ",\"contractVersions\":[\"" + CONTRACT + "\"]}";
        HttpResponse<String> r = send(request(REDEEM, null).header("Nekkora-Agent-Intent", INTENT).POST(HttpRequest.BodyPublishers.ofString(body)));
        int s = r.statusCode();
        if (s == 400 || s == 401) {
            throw new Refused(Problem.CODE_REJECTED);
        }
        if (s == 409) {
            throw new Refused(switch (reason(r)) {
                case "code_not_for_remote_execution" -> Problem.CODE_NOT_REMOTE;
                case "rotation_code_for_pairing" -> Problem.CODE_FOR_RENEWAL;
                case "remote_execution_unavailable" -> Problem.FEATURE_OFF;
                default -> Problem.SEAT_TAKEN;
            });
        }
        Map<String, Object> o = ok(r);
        // so vale a resposta que DIZ que a maquina foi habilitada: um pareamento comum nao e aceito como habilitacao
        if (!Boolean.TRUE.equals(o.get("remoteExecution"))) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return new Redeemed(uuid(o, "organizationId"), uuid(o, "agentId"), uuid(o, "workerId"), uuid(o, "credentialId"), text(o, "credentialExpiresAt"));
    }

    /**
     * Presenca. Nesta versao o aplicativo ainda nao executa solicitacoes da Platform, entao nao declara a capacidade de
     * execucao remota: a Platform mostra a maquina como incompativel, e nada lhe e entregue. Declara so que publica o
     * inventario de Python. {@code declaresRun} so e verdadeiro no ensaio de entrega do laboratorio (ver
     * {@link RemoteMachine#setDeliveryTrial}); {@code busy}: ha uma tentativa entregue ainda nao resolvida.
     */
    Presence heartbeat(String credential, String appVersion, boolean declaresRun, boolean busy) throws Refused {
        String body = "{\"agentVersion\":" + Json.quote(appVersion) + ",\"contractVersion\":\"" + CONTRACT + "\",\"capabilities\":[\"PY_INVENTORY_1_0\""
                + (declaresRun ? ",\"REMOTE_RUN_1_0\"" : "") + "],\"remote\":{\"busy\":" + busy + ",\"diskPressure\":false}}";
        HttpResponse<String> r = send(request(HEARTBEAT, credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        Map<String, Object> o = ok(authenticated(r));
        if (!(o.get("remote") instanceof Map<?, ?> remote)) {
            // vinculada, mas a Platform nao a tem como habilitada
            throw new Refused(Problem.NOT_ENABLED);
        }
        return new Presence(paused(remote.get("receiving")), text(o, "credentialExpiresAt"));
    }

    /** Pausa ou retoma; devolve o recebimento que ficou valendo no servidor ({@code true} = pausado). */
    boolean receiving(String credential, boolean pause) throws Refused {
        String body = "{\"receiving\":\"" + (pause ? "PAUSED" : "ENABLED") + "\"}";
        HttpResponse<String> r = send(request(RECEIVING, credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            throw new Refused("remote_execution_not_enabled".equals(reason(r)) ? Problem.NOT_ENABLED : Problem.UNEXPECTED);
        }
        return paused(ok(authenticated(r)).get("receiving"));
    }

    /**
     * Encerra o vinculo deste computador na Platform. {@code operationId} e gerado antes do primeiro envio e repetido em
     * toda nova tentativa. Vinculo que a Platform ja encerrou: {@link Problem#LINK_ENDED} (nao ha mais o que encerrar).
     */
    void unlink(String credential, String agentId, String operationId) throws Refused {
        String body = "{\"operationId\":" + Json.quote(operationId) + ",\"agentId\":" + Json.quote(agentId) + ",\"launchedRunRequests\":[]}";
        HttpResponse<String> r = send(request(UNLINK, credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            String reason = reason(r);
            throw new Refused(reason.startsWith("link_ended_") ? Problem.LINK_ENDED : Problem.UNEXPECTED);
        }
        ok(authenticated(r));
    }

    /**
     * Troca a credencial da maquina: prova a posse da atual e envia o segredo NOVO, gerado aqui. A resposta traz so o
     * identificador e a validade da nova. Repetir com a mesma credencial e o MESMO segredo (resposta perdida) devolve a mesma
     * credencial nova. Credencial vencida ou revogada: {@link Problem#LINK_ENDED} - ela nunca volta a valer.
     */
    Redeemed renew(String credential, String newSecret, Redeemed identity) throws Refused {
        HttpResponse<String> r = send(request(RENEW, credential).POST(HttpRequest.BodyPublishers.ofString("{\"credentialSecret\":" + Json.quote(newSecret) + "}")));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "credential_renewal_not_due" -> Problem.RENEWAL_NOT_DUE;
                case "remote_execution_not_enabled" -> Problem.NOT_ENABLED;
                case "remote_execution_unavailable" -> Problem.FEATURE_OFF;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r));
        return new Redeemed(identity.organizationId(), identity.agentId(), identity.workerId(), uuid(o, "credentialId"), text(o, "credentialExpiresAt"));
    }

    /** Um ambiente do inventario como vai para a Platform: identificador opaco, versao, tipo e instante. Nunca um caminho. */
    record Published(String environmentId, String pythonVersion, boolean virtual, String observedAt) {
    }

    /** Publica o retrato do Python deste computador (substitui o anterior). Devolve quantos ambientes a Platform gravou. */
    int publishInventory(String credential, java.util.List<Published> environments) throws Refused {
        StringBuilder b = new StringBuilder("{\"environments\":[");
        for (int k = 0; k < environments.size(); k++) {
            Published e = environments.get(k);
            b.append(k == 0 ? "" : ",").append("{\"environmentId\":").append(Json.quote(e.environmentId())).append(",\"pythonVersion\":")
                    .append(Json.quote(e.pythonVersion())).append(",\"kind\":\"").append(e.virtual() ? "VIRTUAL_ENVIRONMENT" : "INSTALLATION")
                    .append("\",\"observedAt\":").append(Json.quote(e.observedAt())).append('}');
        }
        HttpResponse<String> r = send(request(INVENTORY, credential).PUT(HttpRequest.BodyPublishers.ofString(b.append("]}").toString())));
        if (r.statusCode() == 409) {
            throw new Refused("remote_execution_not_enabled".equals(reason(r)) ? Problem.NOT_ENABLED : Problem.UNEXPECTED);
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r));
        if (o.get("count") instanceof Double d && d == Math.rint(d) && d >= 0 && d <= 32) {
            return d.intValue();
        }
        throw new Refused(Problem.UNEXPECTED);
    }

    /** A tentativa entregue. O token so existe nesta resposta: o servidor guarda apenas o hash. */
    record Offer(String attemptId, String remoteExecutionId, int attemptNumber, String workspaceId, String automationId, String automationVersionId,
                 String packageFingerprint, String packageFingerprintRule, java.util.List<String> dependencies, String pythonEnvironmentId, String token,
                 int epoch) {

        @Override
        public String toString() {
            return "Offer[attempt=" + attemptId + "]";
        }
    }

    /** O que ha para esta maquina: uma tentativa a receber, ou o aviso de cancelamento de uma ja recebida, ou nada (os dois nulos). */
    record Next(Offer offer, String cancelAttemptId) {
    }

    /** {@code proceed}: {@code false} quando a maquina NAO deve seguir (o servidor recusou o inicio ou a tentativa ja terminou). */
    record AttemptStatus(String state, boolean proceed, boolean cancelRequested) {
    }

    /** Pergunta o que ha para esta maquina. Entregar nao prova recebimento: so {@link #ack} confirma. */
    Next next(String credential) throws Refused {
        HttpResponse<String> r = send(request(NEXT, credential).GET());
        if (r.statusCode() == 409) {
            throw new Refused("remote_execution_not_enabled".equals(reason(r)) ? Problem.NOT_ENABLED : Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r));
        Offer offer = null;
        if (o.get("attempt") instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> a = (Map<String, Object>) raw;
            if (!(a.get("dependencies") instanceof java.util.List<?> list) || list.size() > AttemptJournal.MAX_DEPENDENCIES
                    || !(a.get("token") instanceof String token) || !AttemptJournal.TOKEN.matcher(token).matches()
                    || !(a.get("packageFingerprint") instanceof String fp) || !fp.matches("^[0-9a-f]{64}$")
                    || !(a.get("packageFingerprintRule") instanceof String rule) || rule.isBlank() || rule.length() > 100) {
                throw new Refused(Problem.UNEXPECTED);
            }
            java.util.List<String> deps = new java.util.ArrayList<>();
            for (Object d : list) {
                if (!(d instanceof String s) || s.isBlank() || s.length() > 512) {
                    throw new Refused(Problem.UNEXPECTED);
                }
                deps.add(s);
            }
            // o ambiente e o Python escolhido podem faltar numa Platform anterior: a tentativa e recebida, mas nao executada
            String python = a.get("pythonEnvironmentId") == null ? null : text(a, "pythonEnvironmentId");
            if (python != null && !AttemptJournal.ENVIRONMENT.matcher(python).matches()) {
                throw new Refused(Problem.UNEXPECTED);
            }
            offer = new Offer(uuid(a, "attemptId"), uuid(a, "remoteExecutionId"), positive(a, "attemptNumber"),
                    a.get("workspaceId") == null ? null : uuid(a, "workspaceId"), uuid(a, "automationId"), uuid(a, "automationVersionId"), fp, rule,
                    java.util.List.copyOf(deps), python, token, positive(a, "epoch"));
        } else if (o.get("attempt") != null) {
            throw new Refused(Problem.UNEXPECTED);
        }
        String cancel = null;
        if (o.get("cancel") instanceof Map<?, ?> raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> c = (Map<String, Object>) raw;
            cancel = uuid(c, "attemptId");
        } else if (o.get("cancel") != null) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return new Next(offer, cancel);
    }

    /** Confirma que a tentativa foi RECEBIDA (ja gravada no diario daqui). Repetir com o mesmo token e epoca nao muda nada. */
    AttemptStatus ack(String credential, String attemptId, String token, int epoch) throws Refused {
        return attempt(credential, attemptId, "/ack", "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + "}");
    }

    /** Informa o estado da tentativa e renova o prazo da comunicacao. {@code refusalReason}: so com REFUSED. */
    AttemptStatus state(String credential, String attemptId, String token, int epoch, String state, String refusalReason, Integer exitCode) throws Refused {
        return attempt(credential, attemptId, "/state", "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"state\":" + Json.quote(state)
                + (refusalReason == null ? "" : ",\"refusalReason\":" + Json.quote(refusalReason)) + (exitCode == null ? "" : ",\"exitCode\":" + exitCode)
                + "}");
    }

    /** Limites dos resultados de uma tentativa (os mesmos da Platform; pontos de partida de laboratorio). */
    static final int MAX_RESULTS = 50;
    static final long MAX_RESULT_BYTES = 20L * 1024 * 1024;
    static final long MAX_RESULTS_TOTAL_BYTES = 50L * 1024 * 1024;

    /**
     * Envia UM arquivo de resultado da tentativa. O nome e o RELATIVO a pasta de saida (nunca o caminho do arquivo neste
     * computador) e vai num cabecalho, com escape de URL; os bytes vao no corpo, com o SHA-256 calculado aqui. O token e a epoca
     * vao em cabecalhos. Repetir o mesmo arquivo no mesmo numero nao muda nada na Platform.
     */
    String sendResult(String credential, String attemptId, String token, int epoch, int number, String name, java.nio.file.Path file) throws Refused {
        if (!UUID.matcher(attemptId).matches() || number < 1 || number > MAX_RESULTS) {
            throw new Refused(Problem.UNEXPECTED);
        }
        byte[] data;
        String sha;
        try {
            if (java.nio.file.Files.size(file) > MAX_RESULT_BYTES) {
                throw new Refused(Problem.RESULT_LIMIT);
            }
            data = java.nio.file.Files.readAllBytes(file);
            sha = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (IOException | java.security.NoSuchAlgorithmException e) {
            throw new Refused(Problem.STORAGE);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(origin.apiPath(ATTEMPTS + attemptId + "/results/" + number)).timeout(Duration.ofMinutes(5))
                .header("Accept", "application/json").header("Content-Type", "application/octet-stream").header("Nekkora-Agent-Contract", CONTRACT)
                .header("Authorization", "Bearer " + credential).header("Nekkora-Attempt-Token", token).header("Nekkora-Attempt-Epoch", String.valueOf(epoch))
                .header("Nekkora-Result-Name", java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20"))
                .header("Nekkora-Result-Sha256", sha).PUT(HttpRequest.BodyPublishers.ofByteArray(data));
        HttpResponse<String> r = send(b);
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                case "remote_result_conflict" -> Problem.RESULT_CONFLICT;
                case "remote_result_limit" -> Problem.RESULT_LIMIT;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            // nome ou tamanho que a Platform nao aceita: este arquivo fica de fora, como os que passam do limite
            throw new Refused(Problem.RESULT_LIMIT);
        }
        // a confirmacao e DESTE arquivo: o numero, o tamanho e o SHA-256 que a Platform diz ter guardado sao os enviados
        Map<String, Object> o = ok(authenticated(r));
        if (!sha.equals(o.get("sha256")) || whole(o.get("number")) != number || whole(o.get("sizeBytes")) != data.length) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return sha;
    }

    /** Um inteiro de uma resposta; -1 se nao e um. */
    private static long whole(Object value) {
        try {
            return new java.math.BigDecimal(String.valueOf(value)).longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Envia um lote de linhas da saida do processo e devolve a ultima sequencia que a Platform tem. O token vai no corpo, nunca
     * na URL. Repetir um lote ja aceito nao muda nada la.
     */
    int sendLogs(String credential, String attemptId, String token, int epoch, java.util.List<LogQueue.Line> lines) throws Refused {
        if (!UUID.matcher(attemptId).matches() || lines.isEmpty()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        StringBuilder body = new StringBuilder("{\"token\":").append(Json.quote(token)).append(",\"epoch\":").append(epoch).append(",\"lines\":[");
        for (int i = 0; i < lines.size(); i++) {
            LogQueue.Line l = lines.get(i);
            body.append(i == 0 ? "" : ",").append("{\"sequence\":").append(l.sequence()).append(",\"capturedAt\":")
                    .append(Json.quote(java.time.Instant.ofEpochMilli(l.epochMillis()).toString())).append(",\"channel\":").append(Json.quote(l.channel()))
                    .append(",\"truncated\":").append(l.truncated()).append(",\"message\":").append(Json.quote(l.message())).append('}');
        }
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + "/logs", credential).POST(HttpRequest.BodyPublishers.ofString(body.append("]}").toString())));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                case "remote_log_conflict" -> Problem.LOG_CONFLICT;
                case "remote_log_limit" -> Problem.LOG_LIMIT;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            // lote que a Platform nao aceita como esta: nao adianta repetir
            throw new Refused(Problem.LOG_CONFLICT);
        }
        Map<String, Object> o = ok(authenticated(r));
        long lastSequence = whole(o.get("lastSequence"));
        if (lastSequence < lines.get(lines.size() - 1).sequence()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return (int) Math.min(lastSequence, Integer.MAX_VALUE);
    }

    /** Fecha o envio dos registros. {@code true} so se a Platform devolveu as mesmas contagens. */
    boolean completeLogs(String credential, String attemptId, String token, int epoch, int lines, long droppedLines) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        String body = "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"lines\":" + lines + ",\"droppedLines\":" + droppedLines + "}";
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + "/logs/complete", credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                case "remote_log_conflict" -> Problem.LOG_CONFLICT;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r));
        return whole(o.get("lines")) == lines && whole(o.get("droppedLines")) == droppedLines;
    }

    /** Fecha o envio de resultados: quantos arquivos foram enviados e quantos ficaram de fora por limite. */
    boolean completeResults(String credential, String attemptId, String token, int epoch, int sent, int skippedByLimit) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        String body = "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"sent\":" + sent + ",\"skippedByLimit\":" + skippedByLimit + "}";
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + "/results/complete", credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                case "remote_result_conflict" -> Problem.RESULT_CONFLICT;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        // o fechamento que a Platform gravou tem de ser o desta entrega: as mesmas contagens
        Map<String, Object> o = ok(authenticated(r));
        return whole(o.get("sent")) == sent && whole(o.get("skippedByLimit")) == skippedByLimit;
    }

    /** Limite do pacote baixado (o mesmo da Platform para um pacote publicado). */
    static final long MAX_PACKAGE_BYTES = 50L * 1024 * 1024;

    /**
     * Baixa o pacote da tentativa para {@code target} (arquivo novo) e devolve o SHA-256 que a Platform declarou para os bytes.
     * O token e a epoca vao em cabecalhos, nunca na URL. O arquivo so fica se chegou inteiro, dentro do limite e com o SHA-256
     * declarado: qualquer outra coisa apaga o que foi gravado e e {@link Problem#PACKAGE_UNAVAILABLE}. Quem chama ainda confere a
     * impressao digital e o conteudo antes de usar.
     */
    String download(String credential, String attemptId, String token, int epoch, java.nio.file.Path target) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        HttpRequest.Builder b = HttpRequest.newBuilder(origin.apiPath(ATTEMPTS + attemptId + "/package")).timeout(Duration.ofMinutes(5))
                .header("Accept", "application/zip").header("Nekkora-Agent-Contract", CONTRACT).header("Authorization", "Bearer " + credential)
                .header("Nekkora-Attempt-Token", token).header("Nekkora-Attempt-Epoch", String.valueOf(epoch)).GET();
        HttpResponse<java.io.InputStream> r;
        try {
            r = http.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (SSLException e) {
            throw new Refused(Problem.TLS);
        } catch (HttpTimeoutException e) {
            throw new Refused(Problem.TIMEOUT);
        } catch (IOException e) {
            throw new Refused(Problem.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused(Problem.NETWORK);
        }
        try (java.io.InputStream in = r.body()) {
            int s = r.statusCode();
            String type = r.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT);
            if (s != 200) {
                String text = type.startsWith("application/json") ? new String(in.readNBytes(4096), StandardCharsets.UTF_8) : "";
                String reason = "";
                try {
                    Map<String, Object> o = Json.object(text);
                    reason = "conflict".equals(o.get("error")) && o.get("reason") instanceof String x ? x : "";
                } catch (IllegalArgumentException e) {
                    // corpo que nao e o do contrato
                }
                throw new Refused(s == 401 ? Problem.LINK_ENDED : s == 404 ? Problem.ATTEMPT_GONE : s == 429 ? Problem.RATE_LIMITED : s >= 500 ? Problem.SERVER
                        : s != 409 ? Problem.UNEXPECTED : switch (reason) {
                            case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                            case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                            case "remote_package_unavailable" -> Problem.PACKAGE_UNAVAILABLE;
                            default -> Problem.UNEXPECTED;
                        });
            }
            String declared = r.headers().firstValue("Nekkora-Package-Sha256").orElse("");
            if (!type.startsWith("application/zip") || !declared.matches("^[0-9a-f]{64}$")) {
                throw new Refused(Problem.UNEXPECTED);
            }
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            long total = 0;
            boolean ok = false;
            try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(target, java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_PACKAGE_BYTES) {
                        throw new Refused(Problem.PACKAGE_UNAVAILABLE);
                    }
                    md.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
                ok = total > 0 && declared.equals(java.util.HexFormat.of().formatHex(md.digest()));
            } finally {
                if (!ok) {
                    java.nio.file.Files.deleteIfExists(target);
                }
            }
            if (!ok) {
                throw new Refused(Problem.PACKAGE_UNAVAILABLE);
            }
            return declared;
        } catch (java.nio.file.FileAlreadyExistsException e) {
            throw new Refused(Problem.STORAGE);
        } catch (IOException e) {
            throw new Refused(Problem.NETWORK);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new Refused(Problem.UNEXPECTED);
        }
    }

    private AttemptStatus attempt(String credential, String attemptId, String action, String body) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + action, credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                case "remote_execution_not_enabled" -> Problem.NOT_ENABLED;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r));
        if (!attemptId.equals(o.get("attemptId")) || !(o.get("state") instanceof String state) || !state.matches("^[A-Z_]{1,40}$")
                || !(o.get("proceed") instanceof Boolean proceed) || !(o.get("cancelRequested") instanceof Boolean cancel)) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return new AttemptStatus(state, proceed, cancel);
    }

    private static int positive(Map<String, Object> o, String key) throws Refused {
        if (o.get(key) instanceof Double d && d == Math.rint(d) && d >= 1 && d <= Integer.MAX_VALUE) {
            return d.intValue();
        }
        throw new Refused(Problem.UNEXPECTED);
    }

    private HttpRequest.Builder request(String path, String credential) {
        HttpRequest.Builder b = HttpRequest.newBuilder(origin.apiPath(path)).timeout(requestTimeout).header("Accept", "application/json")
                .header("Content-Type", "application/json").header("Nekkora-Agent-Contract", CONTRACT);
        if (credential != null) {
            b.header("Authorization", "Bearer " + credential);
        }
        return b;
    }

    private HttpResponse<String> send(HttpRequest.Builder b) throws Refused {
        try {
            return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (SSLException e) {
            throw new Refused(Problem.TLS);
        } catch (HttpTimeoutException e) {
            throw new Refused(Problem.TIMEOUT);
        } catch (ConnectException e) {
            throw new Refused(Problem.NETWORK);
        } catch (IOException e) {
            for (Throwable t = e.getCause(); t != null; t = t.getCause()) {
                if (t instanceof SSLException) {
                    throw new Refused(Problem.TLS);
                }
            }
            throw new Refused(Problem.NETWORK);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Refused(Problem.NETWORK);
        }
    }

    /** 401 numa chamada autenticada: a Platform nao reconhece mais a credencial desta maquina. */
    private static HttpResponse<String> authenticated(HttpResponse<String> r) throws Refused {
        if (r.statusCode() == 401 && json(r)) {
            throw new Refused(Problem.LINK_ENDED);
        }
        return r;
    }

    private static Map<String, Object> ok(HttpResponse<String> r) throws Refused {
        int s = r.statusCode();
        if (s == 429) {
            throw new Refused(Problem.RATE_LIMITED);
        }
        if (s >= 500) {
            throw new Refused(Problem.SERVER);
        }
        if (s != 200 || !json(r)) {
            throw new Refused(Problem.UNEXPECTED);
        }
        try {
            return Json.object(r.body());
        } catch (IllegalArgumentException e) {
            throw new Refused(Problem.UNEXPECTED);
        }
    }

    private static boolean json(HttpResponse<String> r) {
        return r.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("application/json");
    }

    /** O motivo de um 409 do contrato; vazio se a resposta nao for a do contrato. */
    private static String reason(HttpResponse<String> r) {
        if (!json(r)) {
            return "";
        }
        try {
            Map<String, Object> o = Json.object(r.body());
            return "conflict".equals(o.get("error")) && o.get("reason") instanceof String s ? s : "";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    private static boolean paused(Object receiving) throws Refused {
        if ("PAUSED".equals(receiving)) {
            return true;
        }
        if ("ENABLED".equals(receiving)) {
            return false;
        }
        throw new Refused(Problem.UNEXPECTED);
    }

    private static String text(Map<String, Object> o, String key) throws Refused {
        if (o.get(key) instanceof String s && !s.isBlank() && s.length() <= 64) {
            return s;
        }
        throw new Refused(Problem.UNEXPECTED);
    }

    private static String uuid(Map<String, Object> o, String key) throws Refused {
        String v = text(o, key);
        if (!UUID.matcher(v).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return v;
    }
}
