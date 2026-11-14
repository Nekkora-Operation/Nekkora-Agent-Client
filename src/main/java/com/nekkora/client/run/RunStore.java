package com.nekkora.client.run;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.PackageContext;
import com.nekkora.client.pack.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Pastas das execucoes em {@code <dados>/execucoes/<ExecutionId>/}: {@code estado.json} (gravacao atomica a cada
 * transicao) e {@code eventos.jsonl} (so nomes de evento, codigos, tempos e contagens; limitado a {@link #MAX_EVENTS}
 * linhas, a ultima delas o marcador {@link #LIMIT_MARKER}). Retencao: as {@link #KEEP} execucoes TERMINADAS mais
 * recentes de cada organizacao e ambiente; nada em andamento e apagado.
 *
 * <p>Os registros sao LOCAIS: ficam nesta maquina. Nao ha fila de envio nem entrega pendente neste nucleo.
 *
 * <p>Contexto imutavel: na pasta de uma execucao so se grava um registro com o MESMO contexto do que ja esta la, e um
 * registro terminado nunca e trocado por outro diferente.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.RunStore} (Nekkora-Agent 9b1e7e1), sem a retencao por entrega pendente.
 */
public final class RunStore {
    public static final int KEEP = 50;
    public static final int MAX_EVENTS = 500;
    /** Ultima linha do {@code eventos.jsonl} quando o limite e atingido (a perda nao e silenciosa). */
    public static final String LIMIT_MARKER = "EVENTS_LIMIT_REACHED";
    static final String RECORD_FILE = "estado.json";
    static final String EVENTS_FILE = "eventos.jsonl";

    private final Path root;
    private final AtomicWriter writer;

    /** {@code root}: a pasta das execucoes (ex.: {@code <dados>/execucoes}). */
    public RunStore(Path root, AtomicWriter writer) {
        this.root = root;
        this.writer = writer;
    }

    public static Path defaultRoot(Path dataRoot) {
        return dataRoot.resolve("execucoes");
    }

    public Path root() {
        return root;
    }

    public Path dir(ExecutionId id) {
        return root.resolve(id.value());
    }

    public synchronized void save(ExecutionRecord r) throws ExecRefused {
        try {
            Path f = dir(r.id()).resolve(RECORD_FILE);
            Optional<ExecutionRecord> before = load(f);
            // nada reatribui uma execucao: o contexto gravado e o do inicio; um registro terminado nao muda mais
            if (before.isPresent() && (!before.get().context().equals(r.context()) || before.get().state().terminal() && !before.get().equals(r))) {
                throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
            }
            Files.createDirectories(dir(r.id()));
            writer.write(f, json(r).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }

    /** Uma linha de evento (descartada alem do limite: o estado continua sendo gravado). */
    public void event(ExecutionId id, Instant at, String event, Map<String, Object> fields) {
        Path f = dir(id).resolve(EVENTS_FILE);
        try {
            Files.createDirectories(dir(id));
            long lines = 0;
            if (Files.exists(f)) {
                try (Stream<String> s = Files.lines(f, StandardCharsets.UTF_8)) {
                    lines = s.count();
                }
            }
            if (lines >= MAX_EVENTS) return;
            if (lines == MAX_EVENTS - 1) {
                // a ultima linha possivel vira o marcador do limite: quem le sabe que eventos seguintes nao foram gravados
                event = LIMIT_MARKER;
                fields = Map.of();
            }
            StringBuilder b = new StringBuilder("{\"at\": ").append(StrictJson.quote(at.toString())).append(", \"event\": ").append(StrictJson.quote(event));
            fields.forEach((k, v) -> b.append(", ").append(StrictJson.quote(k)).append(": ")
                    .append(v instanceof Number || v instanceof Boolean ? String.valueOf(v) : StrictJson.quote(String.valueOf(v))));
            b.append("}\n");
            Files.writeString(f, b, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException e) {
            // evento perdido nao muda a execucao: o estado.json e a fonte da verdade
        }
    }

    public List<String> events(ExecutionId id) {
        try {
            return Files.readAllLines(dir(id).resolve(EVENTS_FILE), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return List.of();
        }
    }

    /** O registro de uma execucao, se existir e for legivel. */
    public Optional<ExecutionRecord> record(ExecutionId id) {
        return load(dir(id).resolve(RECORD_FILE));
    }

    /**
     * A pasta de saida anotada no inicio da execucao ({@code saida.ref}), so quando e a pasta que o aplicativo criou para
     * ela: caminho absoluto cujo ultimo nome e o proprio identificador da execucao.
     */
    public Optional<Path> output(ExecutionId id) {
        try {
            Path ref = dir(id).resolve(RunController.OUTPUT_REF);
            if (!Files.isRegularFile(ref, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(ref) > 4096) return Optional.empty();
            Path p = Path.of(Files.readString(ref, StandardCharsets.UTF_8).strip());
            if (!p.isAbsolute() || p.getFileName() == null || !p.getFileName().toString().equals(id.value()) || !p.normalize().equals(p)) return Optional.empty();
            return Optional.of(p);
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Todas as execucoes registradas, de todos os contextos, da mais nova para a mais antiga (registro ilegivel fica de fora). */
    public List<ExecutionRecord> all() {
        return list().records();
    }

    /** As execucoes desta organizacao e deste ambiente, da mais nova para a mais antiga. As de outro contexto nunca aparecem. */
    public List<ExecutionRecord> all(PackageContext context) {
        return all().stream().filter(r -> r.context().in(context)).toList();
    }

    /**
     * O historico como a tela precisa: os registros legiveis (mais novos primeiro), quantos registros existem mas nao
     * puderam ser lidos e se a propria pasta do historico falhou: "nenhuma execucao" nunca e confundido com "nao foi
     * possivel ler".
     */
    public record Listing(List<ExecutionRecord> records, int unreadable, boolean failed) {
        public Listing {
            records = List.copyOf(records);
        }

        public static Listing failure() {
            return new Listing(List.of(), 0, true);
        }

        /** So os registros do contexto (as contagens de falha continuam as da pasta inteira). */
        public Listing in(PackageContext context) {
            return new Listing(records.stream().filter(r -> r.context().in(context)).toList(), unreadable, failed);
        }
    }

    public Listing list() {
        List<ExecutionRecord> out = new ArrayList<>();
        int unreadable = 0;
        boolean failed = false;
        // so "nao existe" confirmado e historico vazio; sem poder determinar (acesso negado etc.) e falha
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) return new Listing(out, 0, false);
        if (!Files.isDirectory(root)) return Listing.failure();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path d : ds) {
                if (!ExecutionId.valid(String.valueOf(d.getFileName()))) continue;
                Path f = d.resolve(RECORD_FILE);
                Optional<ExecutionRecord> r = load(f);
                // o identificador gravado precisa ser o da pasta
                if (r.isPresent() && r.get().id().value().equals(String.valueOf(d.getFileName()))) out.add(r.get());
                else if (!Files.notExists(f, LinkOption.NOFOLLOW_LINKS)) unreadable++; // sem estado.json: execucao sendo criada
            }
        } catch (IOException | RuntimeException e) {
            failed = true; // o que ja foi lido continua valendo; a tela avisa que a lista pode estar parcial
        }
        out.sort(Comparator.comparing(ExecutionRecord::created).reversed().thenComparing(r -> r.id().value()));
        return new Listing(out, unreadable, failed);
    }

    /** Apaga as execucoes terminadas mais antigas alem de {@link #KEEP} por organizacao e ambiente (so pastas do aplicativo: copia do pacote e registro). */
    public void prune() {
        Map<PackageContext, Integer> kept = new HashMap<>();
        for (ExecutionRecord r : all()) {
            if (!r.state().terminal()) continue;
            if (kept.merge(r.context().where(), 1, Integer::sum) > KEEP) deleteTree(dir(r.id()));
        }
    }

    static Optional<ExecutionRecord> load(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            Map<String, Object> m = StrictJson.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            if (!ExecutionRecord.SCHEMA.equals(m.get("schema")) || !ExecutionRecord.VERSION.equals(m.get("version"))) return Optional.empty();
            ProcessOwner owner = null;
            if (m.get("process") instanceof Map<?, ?> p && p.get("pid") instanceof Long pid && p.get("start") instanceof Long start
                    && p.get("executableDigest") instanceof String exe && exe.matches("sha256:[0-9a-f]{64}")) {
                owner = new ProcessOwner(pid, start, exe);
            }
            return Optional.of(new ExecutionRecord(new ExecutionId((String) m.get("id")), context(m.get("context")), ExecutionState.valueOf((String) m.get("state")),
                    code(m.get("code")), Instant.parse((String) m.get("created")), instant(m.get("started")), instant(m.get("ended")), owner,
                    m.get("exitCode") instanceof Long x ? Math.toIntExact(x) : null, Math.toIntExact((Long) m.get("suspensions")),
                    Math.toIntExact((Long) m.get("locks")), Boolean.TRUE.equals(m.get("adopted")), (Long) m.get("outputBytes"), cancellation(m.get("cancellation"))));
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Sem contexto completo e valido o registro e ilegivel: uma execucao nunca aparece "sem dono" nem com dono presumido. */
    private static RunContext context(Object v) {
        Map<?, ?> c = (Map<?, ?>) v;
        return new RunContext(PackageContext.uuid((String) c.get("organizationId")), PackageContext.uuid((String) c.get("workspaceId")),
                (String) c.get("fingerprint"), (String) c.get("localName"), uuid(c.get("automationId")), uuid(c.get("automationVersionId")));
    }

    private static UUID uuid(Object v) {
        return v instanceof String s ? PackageContext.uuid(s) : null;
    }

    private static ExecutionRecord.Cancellation cancellation(Object v) {
        if (!(v instanceof Map<?, ?> c)) return null;
        return new ExecutionRecord.Cancellation(Instant.parse((String) c.get("requested")), ExecutionRecord.CancelSource.valueOf((String) c.get("source")),
                ExecutionRecord.CancelOutcome.valueOf((String) c.get("outcome")));
    }

    private static ErrorCode code(Object v) {
        if (!(v instanceof String s)) return null;
        for (ErrorCode c : ErrorCode.values()) if (c.code().equals(s)) return c;
        return null;
    }

    private static Instant instant(Object v) {
        return v instanceof String s ? Instant.parse(s) : null;
    }

    static String json(ExecutionRecord r) {
        StringBuilder b = new StringBuilder("{\n");
        field(b, "schema", ExecutionRecord.SCHEMA);
        field(b, "version", ExecutionRecord.VERSION);
        field(b, "id", r.id().value());
        RunContext c = r.context();
        b.append("  \"context\": {\"organizationId\": ").append(StrictJson.quote(c.organizationId().toString())).append(", \"workspaceId\": ")
                .append(StrictJson.quote(c.workspaceId().toString())).append(", \"fingerprint\": ").append(StrictJson.quote(c.fingerprint()))
                .append(", \"localName\": ").append(StrictJson.quote(c.localName())).append(", \"automationId\": ").append(text(c.automationId()))
                .append(", \"automationVersionId\": ").append(text(c.automationVersionId())).append("},\n");
        field(b, "state", r.state().name());
        field(b, "code", r.code() == null ? null : r.code().code());
        field(b, "created", r.created().toString());
        field(b, "started", r.started() == null ? null : r.started().toString());
        field(b, "ended", r.ended() == null ? null : r.ended().toString());
        if (r.owner() != null) {
            b.append("  \"process\": {\"pid\": ").append(r.owner().pid()).append(", \"start\": ").append(r.owner().startMillis()).append(", \"executableDigest\": ")
                    .append(StrictJson.quote(r.owner().executableDigest())).append("},\n");
        }
        b.append("  \"exitCode\": ").append(r.exitCode() == null ? "null" : r.exitCode()).append(",\n");
        b.append("  \"suspensions\": ").append(r.suspensions()).append(",\n");
        b.append("  \"locks\": ").append(r.locks()).append(",\n");
        b.append("  \"adopted\": ").append(r.adopted()).append(",\n");
        if (r.cancellation() != null) {
            ExecutionRecord.Cancellation x = r.cancellation();
            b.append("  \"cancellation\": {\"requested\": ").append(StrictJson.quote(x.requested().toString())).append(", \"source\": ")
                    .append(StrictJson.quote(x.source().name())).append(", \"outcome\": ").append(StrictJson.quote(x.outcome().name())).append("},\n");
        }
        b.append("  \"outputBytes\": ").append(r.outputBytes()).append("\n}\n");
        return b.toString();
    }

    private static String text(UUID id) {
        return id == null ? "null" : StrictJson.quote(id.toString());
    }

    private static void field(StringBuilder b, String k, String v) {
        b.append("  ").append(StrictJson.quote(k)).append(": ").append(v == null ? "null" : StrictJson.quote(v)).append(",\n");
    }

    static void deleteTree(Path d) {
        if (!Files.exists(d)) return;
        try (Stream<Path> s = Files.walk(d)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
        } catch (IOException e) {
            // fica para a proxima limpeza
        }
    }
}
