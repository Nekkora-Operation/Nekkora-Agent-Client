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
    /** AMBIENTE-PYTHON-001: a resposta da maquina a um pedido de resolucao ({@code <pedido>/answer}). */
    static final String RESOLUTIONS = "/agent/api/v1/remote/environment-resolutions/";
    /** A capacidade do ambiente gerenciado: so declarada depois de a presenca dizer que a Platform a oferece. */
    static final String MANAGED_ENV = "MANAGED_ENV_1_0";
    /** Tamanho maximo da resposta da consulta de trabalho (o lock de um ambiente gerenciado tem ate 400 itens). */
    static final int MAX_NEXT_CHARS = 1024 * 1024;
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
        /** Ha uma tentativa recebida da Platform e ainda nao resolvida com ela: o computador nao e desabilitado agora. */
        ATTEMPT_ACTIVE,
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
    /** {@code workerName}: o nome atual, na Platform, do Worker desta maquina; nulo se a Platform nao o informou. */
    /** {@code managedEnvironments}: a Platform oferece o ambiente gerenciado (AMBIENTE-PYTHON-001); falso se nao disse nada. */
    record Presence(boolean paused, String credentialExpiresAt, String workerName, boolean managedEnvironments) {
        Presence(boolean paused, String credentialExpiresAt, String workerName) {
            this(paused, credentialExpiresAt, workerName, false);
        }
    }

    /** O nome do Worker como veio, sem caracteres de controle e com ate 120 caracteres; nulo se ausente ou vazio. So para exibir. */
    private static String workerName(Object value) {
        if (!(value instanceof String s)) {
            return null;
        }
        String clean = s.replaceAll("\\p{Cntrl}", " ").strip();
        return clean.isEmpty() ? null : clean.substring(0, Math.min(clean.length(), 120));
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
        return heartbeat(credential, appVersion, declaresRun, busy, false);
    }

    /**
     * {@code busy} e {@code diskPressure} sao os UNICOS sinais de disponibilidade que saem deste computador: dois sim/nao, sem
     * dizer por que - nada identifica o que o esta ocupando (execucao da Platform ou iniciada aqui).
     */
    Presence heartbeat(String credential, String appVersion, boolean declaresRun, boolean busy, boolean diskPressure) throws Refused {
        return heartbeat(credential, appVersion, declaresRun, busy, diskPressure, false);
    }

    /**
     * {@code declaresManaged}: declara {@link #MANAGED_ENV} - so depois de uma presenca ter dito que a Platform oferece o ambiente
     * gerenciado. Sem ela, o corpo e exatamente o de antes: um Client novo nunca manda a uma Platform antiga uma capacidade que ela
     * nao conhece.
     */
    Presence heartbeat(String credential, String appVersion, boolean declaresRun, boolean busy, boolean diskPressure, boolean declaresManaged)
            throws Refused {
        String body = "{\"agentVersion\":" + Json.quote(appVersion) + ",\"contractVersion\":\"" + CONTRACT + "\",\"capabilities\":[\"PY_INVENTORY_1_0\""
                + (declaresRun ? ",\"REMOTE_RUN_1_0\"" : "") + (declaresManaged ? ",\"" + MANAGED_ENV + "\"" : "") + "],\"remote\":{\"busy\":" + busy
                + ",\"diskPressure\":" + diskPressure + "}}";
        HttpResponse<String> r = send(request(HEARTBEAT, credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        Map<String, Object> o = ok(authenticated(r));
        if (!(o.get("remote") instanceof Map<?, ?> remote)) {
            // vinculada, mas a Platform nao a tem como habilitada
            throw new Refused(Problem.NOT_ENABLED);
        }
        // a oferta do ambiente gerenciado vem na raiz da resposta; aceita tambem dentro de "remote". Ausente: nao e oferecido
        boolean managed = Boolean.TRUE.equals(o.get("managedEnvironments")) || Boolean.TRUE.equals(remote.get("managedEnvironments"));
        return new Presence(paused(remote.get("receiving")), text(o, "credentialExpiresAt"), workerName(remote.get("workerName")), managed);
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
                 int epoch, Managed environment) {

        /** Modo PREPARED (o de sempre): sem ambiente gerenciado. */
        Offer(String attemptId, String remoteExecutionId, int attemptNumber, String workspaceId, String automationId, String automationVersionId,
              String packageFingerprint, String packageFingerprintRule, java.util.List<String> dependencies, String pythonEnvironmentId, String token,
              int epoch) {
            this(attemptId, remoteExecutionId, attemptNumber, workspaceId, automationId, automationVersionId, packageFingerprint, packageFingerprintRule,
                    dependencies, pythonEnvironmentId, token, epoch, null);
        }

        @Override
        public String toString() {
            return "Offer[attempt=" + attemptId + "]";
        }
    }

    /** O que ha para esta maquina: uma tentativa a receber, ou o aviso de cancelamento de uma ja recebida, ou nada (os dois nulos). */
    record Next(Offer offer, String cancelAttemptId, Check check, Resolution resolution) {
        Next(Offer offer, String cancelAttemptId, Check check) {
            this(offer, cancelAttemptId, check, null);
        }
    }

    /**
     * AMBIENTE-PYTHON-001 - a tentativa no modo MANAGED: o lock aceito (o que sera instalado, exatamente), o hash dele e as
     * verificacoes externas escolhidas. Ausente no modo PREPARED.
     */
    record Managed(String lockSha256, java.util.List<RemoteMachine.LockItem> lock, java.util.List<String> checks) {
        Managed {
            lock = java.util.List.copyOf(lock);
            checks = java.util.List.copyOf(checks);
        }
    }

    /**
     * AMBIENTE-PYTHON-001 - um pedido de RESOLUCAO: a lista efetiva ({@code nome==versao}) a resolver no Python indicado pelo
     * identificador opaco que esta maquina publicou. So o indice publico.
     */
    record Resolution(String resolutionId, String pythonEnvironmentId, java.util.List<String> dependencies) {
    }

    static final int MAX_LOCK = 400;
    private static final Pattern WORD = Pattern.compile("^[A-Z_]{1,40}$");
    private static final Pattern HEX64 = Pattern.compile("^[0-9a-f]{64}$");

    /** O pedido de resolucao que veio na consulta, se veio e esta no formato; fora do formato (ou outro indice), e como se nao viesse. */
    private static Resolution resolution(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || !(m.get("resolutionId") instanceof String id) || !UUID.matcher(id).matches()
                || !(m.get("pythonEnvironmentId") instanceof String env) || !AttemptJournal.ENVIRONMENT.matcher(env).matches()
                || !(m.get("dependencies") instanceof java.util.List<?> list) || list.size() > AttemptJournal.MAX_DEPENDENCIES
                || (m.get("index") != null && !"PYPI".equals(m.get("index")))) {
            return null;
        }
        java.util.List<String> deps = new java.util.ArrayList<>();
        for (Object d : list) {
            if (!(d instanceof String t) || t.isBlank() || t.length() > 512) {
                return null;
            }
            deps.add(t);
        }
        return new Resolution(id, env, java.util.List.copyOf(deps));
    }

    /** O ambiente gerenciado da oferta: nulo no modo PREPARED (campo ausente ou {@code mode} diferente de MANAGED). */
    private static Managed managed(Object raw) throws Refused {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?> m)) {
            throw new Refused(Problem.UNEXPECTED);
        }
        if (!"MANAGED".equals(m.get("mode"))) {
            return null;
        }
        if ((m.get("index") != null && !"PYPI".equals(m.get("index"))) || !(m.get("lockSha256") instanceof String sha) || !HEX64.matcher(sha).matches()
                || !(m.get("lock") instanceof java.util.List<?> lock) || lock.size() > MAX_LOCK
                || (m.get("checks") != null && !(m.get("checks") instanceof java.util.List<?>))) {
            throw new Refused(Problem.UNEXPECTED);
        }
        java.util.List<RemoteMachine.LockItem> items = new java.util.ArrayList<>();
        for (Object o : lock) {
            if (!(o instanceof Map<?, ?> i) || !(i.get("name") instanceof String n) || !(i.get("version") instanceof String v)
                    || !(i.get("sha256") instanceof String h) || !(i.get("fileName") instanceof String f)) {
                throw new Refused(Problem.UNEXPECTED);
            }
            RemoteMachine.LockItem item = new RemoteMachine.LockItem(n, v, h, f);
            if (!item.valid()) {
                throw new Refused(Problem.UNEXPECTED);
            }
            items.add(item);
        }
        java.util.List<String> checks = new java.util.ArrayList<>();
        if (m.get("checks") instanceof java.util.List<?> list) {
            if (list.size() > 16) {
                throw new Refused(Problem.UNEXPECTED);
            }
            for (Object c : list) {
                if (!(c instanceof String t) || !WORD.matcher(t).matches()) {
                    throw new Refused(Problem.UNEXPECTED);
                }
                checks.add(t);
            }
        }
        return new Managed(sha, items, checks);
    }

    /**
     * Responde o pedido de resolucao (so uma vez, dentro do prazo; repetir igual devolve o gravado). Volta sem erro quando a Platform
     * tem a resposta, ou quando o pedido ja esta fechado la (respondido, vencido, desconhecido) e nao ha o que repetir. Falha
     * passageira: lanca, e a resposta e repetida na proxima consulta.
     */
    void answerResolution(String credential, String resolutionId, RemoteMachine.Resolved answer) throws Refused {
        if (!UUID.matcher(resolutionId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        StringBuilder b = new StringBuilder("{\"outcome\":").append(Json.quote(answer.outcome()));
        optional(b, "pythonVersion", answer.pythonVersion());
        optional(b, "pipVersion", answer.pipVersion());
        // o lock so existe numa resposta RESOLVED; nas outras ele vai nulo, e a falha so falta em RESOLVED e ENVIRONMENT_GONE
        if ("RESOLVED".equals(answer.outcome())) {
            b.append(",\"lock\":[");
            for (int k = 0; k < answer.lock().size(); k++) {
                RemoteMachine.LockItem i = answer.lock().get(k);
                b.append(k == 0 ? "" : ",").append("{\"name\":").append(Json.quote(i.name())).append(",\"version\":").append(Json.quote(i.version()))
                        .append(",\"sha256\":").append(Json.quote(i.sha256())).append(",\"fileName\":").append(Json.quote(i.fileName())).append('}');
            }
            b.append("],\"failure\":null");
        } else {
            b.append(",\"lock\":null,\"failure\":");
            failure(b, "ENVIRONMENT_GONE".equals(answer.outcome()) ? null : answer.failure());
        }
        HttpResponse<String> r = send(request(RESOLUTIONS + resolutionId + "/answer", credential).POST(HttpRequest.BodyPublishers.ofString(b.append('}').toString())));
        if (r.statusCode() == 409 || r.statusCode() == 404 || r.statusCode() == 400) {
            return;
        }
        ok(authenticated(r));
    }

    /** Informa a etapa do preparo do ambiente ({@code ENV_CREATING}, {@code DEPS_INSTALLING}); renova o prazo da comunicacao. */
    AttemptStatus stage(String credential, String attemptId, String token, int epoch, String stage) throws Refused {
        return attempt(credential, attemptId, "/stage", "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"stage\":" + Json.quote(stage) + "}");
    }

    /**
     * O relatorio do preparo do ambiente da tentativa (READY, FAILED ou INTERRUPTED), com o registro ja sem segredos. Gravado uma vez
     * na Platform: repetir igual devolve o mesmo; diferente (409 {@code managed_environment_report_conflict}) ou fora do formato (400)
     * nao tem o que repetir e volta sem erro. Token ou epoca que nao valem mais, tentativa que a Platform nao tem: lanca.
     */
    void environmentReport(String credential, String attemptId, String token, int epoch, RemoteMachine.EnvironmentReport report) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        StringBuilder b = new StringBuilder("{\"token\":").append(Json.quote(token)).append(",\"epoch\":").append(epoch).append(",\"outcome\":")
                .append(Json.quote(report.outcome()));
        optional(b, "pythonVersion", report.pythonVersion());
        optional(b, "pipVersion", report.pipVersion());
        b.append(",\"installed\":[");
        for (int k = 0; k < report.installed().size(); k++) {
            RemoteMachine.LockItem i = report.installed().get(k);
            b.append(k == 0 ? "" : ",").append("{\"name\":").append(Json.quote(i.name())).append(",\"version\":").append(Json.quote(i.version()))
                    .append(",\"sha256\":").append(Json.quote(i.sha256())).append('}');
        }
        b.append("],\"failure\":");
        failure(b, report.failure());
        b.append(",\"createMs\":").append(Math.max(0, report.createMs())).append(",\"installMs\":").append(Math.max(0, report.installMs()));
        optional(b, "log", report.log());
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + "/environment-report", credential)
                .PUT(HttpRequest.BodyPublishers.ofString(b.append('}').toString())));
        if (r.statusCode() == 409) {
            switch (reason(r)) {
                case "remote_attempt_stale" -> throw new Refused(Problem.ATTEMPT_STALE);
                case "remote_attempt_transition" -> throw new Refused(Problem.ATTEMPT_TRANSITION);
                default -> {
                    // managed_environment_report_conflict: a Platform ja tem um relatorio desta tentativa; nao ha o que repetir
                    return;
                }
            }
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            return;
        }
        ok(authenticated(r));
    }

    private static void optional(StringBuilder b, String key, String value) {
        if (value != null) {
            b.append(",\"").append(key).append("\":").append(Json.quote(value));
        }
    }

    private static void failure(StringBuilder b, RemoteMachine.EnvFailure f) {
        if (f == null) {
            b.append("null");
            return;
        }
        b.append("{\"requirement\":").append(f.requirement() == null ? "null" : Json.quote(f.requirement())).append(",\"phase\":").append(Json.quote(f.phase()))
                .append(",\"summary\":").append(f.summary() == null ? "null" : Json.quote(f.summary())).append('}');
    }

    /**
     * Um pedido da Platform para CONFERIR (nao instalar, nao executar) as dependencias de uma automacao no ambiente Python que foi
     * escolhido para ela nesta maquina. {@code pythonEnvironmentId}: o identificador opaco que esta maquina publicou.
     */
    record Check(String checkId, String pythonEnvironmentId, java.util.List<String> dependencies) {
    }

    static final String CHECKS = "/agent/api/v1/remote/dependency-checks/";

    /** O pedido de verificacao que veio numa resposta, se veio e esta no formato; fora do formato, e como se nao viesse. */
    private static Check check(Object raw) {
        if (!(raw instanceof Map<?, ?> m) || !(m.get("checkId") instanceof String id) || !UUID.matcher(id).matches()
                || !(m.get("pythonEnvironmentId") instanceof String env) || !AttemptJournal.ENVIRONMENT.matcher(env).matches()
                || !(m.get("dependencies") instanceof java.util.List<?> list) || list.size() > AttemptJournal.MAX_DEPENDENCIES) {
            return null;
        }
        java.util.List<String> deps = new java.util.ArrayList<>();
        for (Object d : list) {
            if (!(d instanceof String t) || t.isBlank() || t.length() > 512) {
                return null;
            }
            deps.add(t);
        }
        return new Check(id, env, java.util.List.copyOf(deps));
    }

    /** O pedido de verificacao pendente desta maquina, para quando ela nao esta perguntando por trabalho. Nulo: nao ha. */
    Check pendingCheck(String credential) throws Refused {
        return check(ok(authenticated(send(request(CHECKS + "pending", credential).GET()))).get("dependencyCheck"));
    }

    /**
     * Responde a verificacao: {@code SATISFIED}, {@code MISSING} (com o que falta, do que foi perguntado), {@code ENVIRONMENT_GONE}
     * ou {@code NOT_VERIFIED}. Nada do que esta instalado vai junto. Pedido que a Platform ja fechou (respondido, ou fora do
     * prazo) ou nao reconhece: nao ha o que fazer, e nada e lancado.
     */
    void answerCheck(String credential, String checkId, String outcome, java.util.List<String> missing) throws Refused {
        if (!UUID.matcher(checkId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        StringBuilder body = new StringBuilder("{\"outcome\":").append(Json.quote(outcome));
        if (missing != null && !missing.isEmpty()) {
            body.append(",\"missing\":[");
            for (int i = 0; i < missing.size(); i++) {
                body.append(i == 0 ? "" : ",").append(Json.quote(missing.get(i)));
            }
            body.append(']');
        }
        HttpResponse<String> r = send(request(CHECKS + checkId + "/answer", credential).POST(HttpRequest.BodyPublishers.ofString(body.append('}').toString())));
        if (r.statusCode() == 409 || r.statusCode() == 404 || r.statusCode() == 400) {
            return;
        }
        ok(authenticated(r));
    }

    /** {@code proceed}: {@code false} quando a maquina NAO deve seguir (o servidor recusou o inicio ou a tentativa ja terminou). */
    /** {@code refusalReason}: o motivo com que a PLATFORM recusou a tentativa (estado REFUSED), ou vazio. So para dizer a pessoa. */
    record AttemptStatus(String state, boolean proceed, boolean cancelRequested, String refusalReason) {
    }

    /** Pergunta o que ha para esta maquina. Entregar nao prova recebimento: so {@link #ack} confirma. */
    Next next(String credential) throws Refused {
        HttpResponse<String> r = send(request(NEXT, credential).GET());
        if (r.statusCode() == 409) {
            throw new Refused("remote_execution_not_enabled".equals(reason(r)) ? Problem.NOT_ENABLED : Problem.UNEXPECTED);
        }
        Map<String, Object> o = ok(authenticated(r), MAX_NEXT_CHARS);
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
                    java.util.List.copyOf(deps), python, token, positive(a, "epoch"), managed(a.get("environment")));
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
        return new Next(offer, cancel, check(o.get("dependencyCheck")), resolution(o.get("environmentResolution")));
    }

    /** Confirma que a tentativa foi RECEBIDA (ja gravada no diario daqui). Repetir com o mesmo token e epoca nao muda nada. */
    AttemptStatus ack(String credential, String attemptId, String token, int epoch) throws Refused {
        return attempt(credential, attemptId, "/ack", "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + "}");
    }

    /** Informa o estado da tentativa e renova o prazo da comunicacao. {@code refusalReason}: so com REFUSED. */
    AttemptStatus state(String credential, String attemptId, String token, int epoch, String state, String refusalReason, Integer exitCode) throws Refused {
        return state(credential, attemptId, token, epoch, state, refusalReason, exitCode, null);
    }

    /**
     * {@code processAbsent}: junto de um estado FINAL, este computador afirma que CONFERIU que nada daquela tentativa continua em
     * execucao aqui - o processo lancado e os processos filhos dele. E o que reconcilia a tentativa "pela maquina": sem isso a
     * Platform mantem a repeticao bloqueada e esta maquina indisponivel. So e enviado quando verdadeiro.
     */
    AttemptStatus state(String credential, String attemptId, String token, int epoch, String state, String refusalReason, Integer exitCode,
                        String processTree) throws Refused {
        return attempt(credential, attemptId, "/state", "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"state\":" + Json.quote(state)
                + (refusalReason == null ? "" : ",\"refusalReason\":" + Json.quote(refusalReason)) + (exitCode == null ? "" : ",\"exitCode\":" + exitCode)
                + (processTree == null ? "" : ",\"processTree\":" + Json.quote(processTree)) + "}");
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

    /**
     * Avisa a Platform que este computador REMOVEU, pelo prazo de guarda, o que guardava de uma tentativa sem confirmacao de
     * recebimento: a fila de registros ({@code logs}) ou a pasta de saida. {@code localCount}: quanto havia aqui (linhas
     * capturadas, ou arquivos). Nada do conteudo vai junto. {@code true}: a Platform gravou o aviso (ou ja o tinha).
     */
    boolean reportLoss(String credential, String attemptId, String token, int epoch, boolean logs, int localCount) throws Refused {
        if (!UUID.matcher(attemptId).matches()) {
            throw new Refused(Problem.UNEXPECTED);
        }
        String kind = logs ? "LOGS" : "RESULTS";
        String body = "{\"token\":" + Json.quote(token) + ",\"epoch\":" + epoch + ",\"kind\":\"" + kind + "\",\"localCount\":" + Math.max(0, localCount) + "}";
        HttpResponse<String> r = send(request(ATTEMPTS + attemptId + "/retention-loss", credential).POST(HttpRequest.BodyPublishers.ofString(body)));
        if (r.statusCode() == 409) {
            throw new Refused(switch (reason(r)) {
                case "remote_attempt_stale" -> Problem.ATTEMPT_STALE;
                case "remote_attempt_transition" -> Problem.ATTEMPT_TRANSITION;
                default -> Problem.UNEXPECTED;
            });
        }
        if (r.statusCode() == 404 && json(r)) {
            throw new Refused(Problem.ATTEMPT_GONE);
        }
        if (r.statusCode() == 400) {
            throw new Refused(Problem.UNEXPECTED);
        }
        return kind.equals(ok(authenticated(r)).get("kind"));
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
        // o motivo e opcional e so informativo: fora do formato, e como se nao tivesse vindo
        String why = o.get("refusalReason") instanceof String r0 && r0.matches("^[A-Z_]{1,40}$") ? r0 : "";
        return new AttemptStatus(state, proceed, cancel, why);
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
        return ok(r, 16_384);
    }

    /** {@code max}: o tamanho maximo da resposta, em caracteres (a consulta de trabalho pode trazer o lock de um ambiente gerenciado). */
    private static Map<String, Object> ok(HttpResponse<String> r, int max) throws Refused {
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
            return Json.object(r.body(), max);
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
