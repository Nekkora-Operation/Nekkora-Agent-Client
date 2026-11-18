package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.nekkora.client.auth.Json;

/**
 * O diario da entrega: a tentativa que a Platform entregou a ESTA maquina e que ainda nao foi resolvida com ela. Um arquivo
 * so, {@code <dados>/remota/tentativa.json}, gravado de forma atomica ANTES de o recebimento ser confirmado, a cada passo
 * que nao pode ser repetido as cegas (confirmado, iniciando, em execucao, desfecho a informar), e apagado quando a Platform
 * confirmar o desfecho (ou disser que a tentativa nao e mais desta entrega). Serve so para a entrega: nao e historico, nao
 * guarda registros nem resultados.
 *
 * <p>O token da entrega e sempre protegido pelo {@link Keyring}. Arquivo ausente, ilegivel, de outra maquina ou fora do
 * formato: nao ha tentativa pendente ({@link #load} vazio), nunca uma tentativa "meio lida".
 */
final class AttemptJournal {

    static final String FILE = "tentativa.json";
    private static final int MAX_FILE = 64 * 1024;
    static final int MAX_DEPENDENCIES = 512;
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9_-]{20,128}$");
    static final Pattern ENVIRONMENT = Pattern.compile("^[A-Za-z0-9_-]{43}$");
    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern WORD = Pattern.compile("^[A-Z_]{1,40}$");
    private static final Pattern RUN = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

    /** Em que ponto a tentativa esta NESTE computador. */
    enum Phase {
        /** Gravada; o recebimento pode ainda nao estar confirmado. Nada foi baixado nem iniciado. */
        RECEIVED,
        /**
         * O inicio foi pedido ao motor e o identificador da execucao ainda nao foi gravado. Se o aplicativo fechar aqui, nao da
         * para saber se o processo foi lancado: a tentativa termina sem prova do resultado, e NADA e lancado de novo.
         */
        STARTING,
        /** Em execucao, com o identificador da execucao gravado. */
        RUNNING,
        /** O desfecho ja foi aceito pela Platform; falta enviar os arquivos de resultado e fechar o envio. */
        RESULTS
    }

    /**
     * {@code acked}: a Platform ja confirmou o recebimento. {@code run}: o identificador da execucao no motor deste
     * computador (so em {@link Phase#RUNNING}). {@code report}: o desfecho que falta informar (nulo enquanto nao ha), com
     * {@code reason} quando e uma recusa e {@code exitCode} quando o processo terminou com um codigo. {@code workspaceId} e
     * {@code pythonEnvironmentId} podem faltar numa entrega de uma Platform anterior: sem eles a tentativa nao e executada.
     * {@link #toString()} nunca mostra o token.
     */
    record Entry(String agentId, String attemptId, String remoteExecutionId, int attemptNumber, String workspaceId, String automationId,
                 String automationVersionId, String packageFingerprint, String packageFingerprintRule, List<String> dependencies,
                 String pythonEnvironmentId, String token, int epoch, boolean acked, Phase phase, String run, String report, String reason,
                 Integer exitCode) {

        Entry {
            dependencies = List.copyOf(dependencies);
            if (!UUID.matcher(agentId).matches() || !UUID.matcher(attemptId).matches() || !UUID.matcher(remoteExecutionId).matches()
                    || !UUID.matcher(automationId).matches() || !UUID.matcher(automationVersionId).matches() || attemptNumber < 1 || epoch < 1
                    || (workspaceId != null && !UUID.matcher(workspaceId).matches())
                    || (pythonEnvironmentId != null && !ENVIRONMENT.matcher(pythonEnvironmentId).matches())
                    || !HEX.matcher(packageFingerprint).matches() || packageFingerprintRule.isBlank() || packageFingerprintRule.length() > 100
                    || !TOKEN.matcher(token).matches() || dependencies.size() > MAX_DEPENDENCIES
                    || dependencies.stream().anyMatch(d -> d.isBlank() || d.length() > 512) || phase == null
                    || (run != null && !RUN.matcher(run).matches()) || (phase == Phase.RUNNING || phase == Phase.RESULTS) != (run != null)
                    || (report != null && !WORD.matcher(report).matches()) || (reason != null && !WORD.matcher(reason).matches())
                    || (reason != null && report == null) || (exitCode != null && report == null)) {
                throw new IllegalArgumentException("tentativa");
            }
        }

        /** A tentativa como chega: gravada, sem confirmacao, sem nada iniciado. */
        static Entry received(String agentId, MachineChannel.Offer o) {
            return new Entry(agentId, o.attemptId(), o.remoteExecutionId(), o.attemptNumber(), o.workspaceId(), o.automationId(), o.automationVersionId(),
                    o.packageFingerprint(), o.packageFingerprintRule(), o.dependencies(), o.pythonEnvironmentId(), o.token(), o.epoch(), false,
                    Phase.RECEIVED, null, null, null, null);
        }

        private Entry with(boolean ack, Phase p, String r, String state, String why, Integer exit) {
            return new Entry(agentId, attemptId, remoteExecutionId, attemptNumber, workspaceId, automationId, automationVersionId, packageFingerprint,
                    packageFingerprintRule, dependencies, pythonEnvironmentId, token, epoch, ack, p, r, state, why, exit);
        }

        Entry withAck() {
            return with(true, phase, run, report, reason, exitCode);
        }

        Entry starting() {
            return with(acked, Phase.STARTING, null, report, reason, exitCode);
        }

        Entry running(String runId) {
            return with(acked, Phase.RUNNING, runId, report, reason, exitCode);
        }

        /** O desfecho foi aceito pela Platform: daqui em diante so falta o envio dos resultados. */
        Entry results() {
            return with(acked, Phase.RESULTS, run, report, reason, exitCode);
        }

        /** O desfecho a informar. {@code why}: so numa recusa. {@code exit}: so quando o processo terminou com um codigo. */
        Entry reporting(String state, String why, Integer exit) {
            return with(acked, phase, run, state, why, exit);
        }

        @Override
        public String toString() {
            return "Entry[attempt=" + attemptId + "]";
        }
    }

    private final Path dir;
    private final Keyring keyring;

    AttemptJournal(Path dir, Keyring keyring) {
        this.dir = dir;
        this.keyring = keyring;
    }

    /*
     * Ler, gravar e apagar sao sincronizados: dentro deste aplicativo, uma leitura (a tela, a presenca) nunca esta com o arquivo
     * aberto enquanto a consulta o troca. Sem isso o Windows nega a troca e o diario fica para tras.
     */

    /** A tentativa pendente DESTA maquina ({@code agentId}); de outra maquina (vinculo anterior), nao vale. */
    synchronized Optional<Entry> load(String agentId) {
        Path f = dir.resolve(FILE);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > MAX_FILE) {
                return Optional.empty();
            }
            Map<String, Object> o = Json.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!(o.get("versao") instanceof Double v) || v != 2.0 || !agentId.equals(o.get("agente")) || !(o.get("confirmada") instanceof Boolean acked)
                    || !(o.get("dependencias") instanceof List<?> list)) {
                return Optional.empty();
            }
            List<String> deps = new ArrayList<>();
            for (Object d : list) {
                if (!(d instanceof String s)) {
                    return Optional.empty();
                }
                deps.add(s);
            }
            byte[] plain = keyring.unprotect(Base64.getDecoder().decode(text(o, "token")));
            try {
                return Optional.of(new Entry(agentId, text(o, "tentativa"), text(o, "solicitacao"), integer(o, "numero"), optional(o, "ambiente"),
                        text(o, "automacao"), text(o, "versaoDaAutomacao"), text(o, "impressao"), text(o, "regra"), deps, optional(o, "python"),
                        new String(plain, StandardCharsets.US_ASCII), integer(o, "epoca"), acked, Phase.valueOf(text(o, "fase")), optional(o, "execucao"),
                        optional(o, "informar"), optional(o, "motivo"), o.get("codigoDeSaida") == null ? null : signed(o, "codigoDeSaida")));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Grava (substitui) a tentativa pendente. Sem protecao disponivel, nada e gravado. */
    synchronized void save(Entry e) throws IOException {
        if (!keyring.available()) {
            throw new IOException("protecao indisponivel");
        }
        byte[] plain = e.token().getBytes(StandardCharsets.US_ASCII);
        String blob;
        try {
            blob = Base64.getEncoder().encodeToString(keyring.protect(plain));
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
        StringBuilder b = new StringBuilder("{\"versao\":2,\"agente\":").append(Json.quote(e.agentId())).append(",\"tentativa\":").append(Json.quote(e.attemptId()))
                .append(",\"solicitacao\":").append(Json.quote(e.remoteExecutionId())).append(",\"numero\":").append(e.attemptNumber())
                .append(",\"automacao\":").append(Json.quote(e.automationId())).append(",\"versaoDaAutomacao\":").append(Json.quote(e.automationVersionId()))
                .append(",\"impressao\":").append(Json.quote(e.packageFingerprint())).append(",\"regra\":").append(Json.quote(e.packageFingerprintRule()))
                .append(",\"dependencias\":[");
        for (int k = 0; k < e.dependencies().size(); k++) {
            b.append(k == 0 ? "" : ",").append(Json.quote(e.dependencies().get(k)));
        }
        b.append("],\"epoca\":").append(e.epoch()).append(",\"confirmada\":").append(e.acked()).append(",\"fase\":\"").append(e.phase().name()).append('"');
        field(b, "ambiente", e.workspaceId());
        field(b, "python", e.pythonEnvironmentId());
        field(b, "execucao", e.run());
        field(b, "informar", e.report());
        field(b, "motivo", e.reason());
        if (e.exitCode() != null) {
            b.append(",\"codigoDeSaida\":").append(e.exitCode());
        }
        b.append(",\"token\":").append(Json.quote(blob)).append('}');
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FILE + ".novo");
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        // a troca e negada no Windows enquanto outra leitura tem o diario aberto: tentada de novo, nunca dada por feita
        Replace.into(tmp, dir.resolve(FILE));
    }

    synchronized void clear() throws IOException {
        Files.deleteIfExists(dir.resolve(FILE + ".novo"));
        Files.deleteIfExists(dir.resolve(FILE));
    }

    private static void field(StringBuilder b, String key, String value) {
        if (value != null) {
            b.append(",\"").append(key).append("\":").append(Json.quote(value));
        }
    }

    private static String text(Map<String, Object> o, String key) {
        if (o.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key);
    }

    private static String optional(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v == null) {
            return null;
        }
        if (v instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException(key);
    }

    private static int integer(Map<String, Object> o, String key) {
        if (o.get(key) instanceof Double d && d == Math.rint(d) && d >= 0 && d <= Integer.MAX_VALUE) {
            return d.intValue();
        }
        throw new IllegalArgumentException(key);
    }

    private static int signed(Map<String, Object> o, String key) {
        if (o.get(key) instanceof Double d && d == Math.rint(d) && d >= Integer.MIN_VALUE && d <= Integer.MAX_VALUE) {
            return d.intValue();
        }
        throw new IllegalArgumentException(key);
    }
}
