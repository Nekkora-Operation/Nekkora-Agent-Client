package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * so, {@code <dados>/remota/tentativa.json}, gravado de forma atomica ANTES de o recebimento ser confirmado, e apagado
 * quando a Platform confirmar o desfecho (ou disser que a tentativa nao e mais desta entrega). Serve so para a entrega:
 * nao e historico, nao guarda registros nem resultados.
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
    private static final Pattern HEX = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern WORD = Pattern.compile("^[A-Z_]{1,40}$");

    /**
     * {@code acked}: a Platform ja confirmou o recebimento. {@code report}: o desfecho que falta informar (nulo enquanto nao
     * ha), com {@code reason} quando e uma recusa. {@link #toString()} nunca mostra o token.
     */
    record Entry(String agentId, String attemptId, String remoteExecutionId, int attemptNumber, String automationId, String automationVersionId,
                 String packageFingerprint, String packageFingerprintRule, List<String> dependencies, String token, int epoch, boolean acked,
                 String report, String reason) {

        Entry {
            dependencies = List.copyOf(dependencies);
            if (!UUID.matcher(agentId).matches() || !UUID.matcher(attemptId).matches() || !UUID.matcher(remoteExecutionId).matches()
                    || !UUID.matcher(automationId).matches() || !UUID.matcher(automationVersionId).matches() || attemptNumber < 1 || epoch < 1
                    || !HEX.matcher(packageFingerprint).matches() || packageFingerprintRule.isBlank() || packageFingerprintRule.length() > 100
                    || !TOKEN.matcher(token).matches() || dependencies.size() > MAX_DEPENDENCIES
                    || dependencies.stream().anyMatch(d -> d.isBlank() || d.length() > 512) || (report != null && !WORD.matcher(report).matches())
                    || (reason != null && !WORD.matcher(reason).matches()) || (reason != null && report == null)) {
                throw new IllegalArgumentException("tentativa");
            }
        }

        Entry withAck() {
            return new Entry(agentId, attemptId, remoteExecutionId, attemptNumber, automationId, automationVersionId, packageFingerprint,
                    packageFingerprintRule, dependencies, token, epoch, true, report, reason);
        }

        Entry reporting(String state, String why) {
            return new Entry(agentId, attemptId, remoteExecutionId, attemptNumber, automationId, automationVersionId, packageFingerprint,
                    packageFingerprintRule, dependencies, token, epoch, acked, state, why);
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

    /** A tentativa pendente DESTA maquina ({@code agentId}); de outra maquina (vinculo anterior), nao vale. */
    Optional<Entry> load(String agentId) {
        Path f = dir.resolve(FILE);
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS) || Files.size(f) > MAX_FILE) {
                return Optional.empty();
            }
            Map<String, Object> o = Json.object(Files.readString(f, StandardCharsets.UTF_8));
            if (!(o.get("versao") instanceof Double v) || v != 1.0 || !agentId.equals(o.get("agente")) || !(o.get("confirmada") instanceof Boolean acked)
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
                return Optional.of(new Entry(agentId, text(o, "tentativa"), text(o, "solicitacao"), integer(o, "numero"), text(o, "automacao"),
                        text(o, "versaoDaAutomacao"), text(o, "impressao"), text(o, "regra"), deps, new String(plain, StandardCharsets.US_ASCII),
                        integer(o, "epoca"), acked, o.get("informar") instanceof String s ? s : null, o.get("motivo") instanceof String s ? s : null));
            } finally {
                Arrays.fill(plain, (byte) 0);
            }
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Grava (substitui) a tentativa pendente. Sem protecao disponivel, nada e gravado. */
    void save(Entry e) throws IOException {
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
        StringBuilder b = new StringBuilder("{\"versao\":1,\"agente\":").append(Json.quote(e.agentId())).append(",\"tentativa\":").append(Json.quote(e.attemptId()))
                .append(",\"solicitacao\":").append(Json.quote(e.remoteExecutionId())).append(",\"numero\":").append(e.attemptNumber())
                .append(",\"automacao\":").append(Json.quote(e.automationId())).append(",\"versaoDaAutomacao\":").append(Json.quote(e.automationVersionId()))
                .append(",\"impressao\":").append(Json.quote(e.packageFingerprint())).append(",\"regra\":").append(Json.quote(e.packageFingerprintRule()))
                .append(",\"dependencias\":[");
        for (int k = 0; k < e.dependencies().size(); k++) {
            b.append(k == 0 ? "" : ",").append(Json.quote(e.dependencies().get(k)));
        }
        b.append("],\"epoca\":").append(e.epoch()).append(",\"confirmada\":").append(e.acked());
        if (e.report() != null) {
            b.append(",\"informar\":").append(Json.quote(e.report()));
        }
        if (e.reason() != null) {
            b.append(",\"motivo\":").append(Json.quote(e.reason()));
        }
        b.append(",\"token\":").append(Json.quote(blob)).append('}');
        Files.createDirectories(dir);
        Path tmp = dir.resolve(FILE + ".novo");
        Files.writeString(tmp, b.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException x) {
            Files.move(tmp, dir.resolve(FILE), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void clear() throws IOException {
        Files.deleteIfExists(dir.resolve(FILE + ".novo"));
        Files.deleteIfExists(dir.resolve(FILE));
    }

    private static String text(Map<String, Object> o, String key) {
        if (o.get(key) instanceof String s) {
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
}
