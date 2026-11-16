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
 * <li>{@link #renew}: trocar a propria credencial perto do vencimento, sem pessoa e sem codigo.</li>
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
     * Presenca. Nesta versao o aplicativo ainda nao executa solicitacoes da Platform, entao nao declara nenhuma capacidade
     * de execucao remota: a Platform mostra a maquina como incompativel, e nada lhe e entregue.
     */
    Presence heartbeat(String credential, String appVersion) throws Refused {
        String body = "{\"agentVersion\":" + Json.quote(appVersion) + ",\"contractVersion\":\"" + CONTRACT
                + "\",\"capabilities\":[],\"remote\":{\"busy\":false,\"diskPressure\":false}}";
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
