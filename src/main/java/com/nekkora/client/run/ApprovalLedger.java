package com.nekkora.client.run;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.StrictJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aprovacoes locais de pacotes ({@code nekkora-agent/approvals} 1.0.0), por PERFIL (pasta de dados): a pessoa aprova UM
 * pacote pela sua impressao digital. Arquivo mudado: outra impressao: sem aprovacao. Guarda so a impressao, o tipo, um
 * rotulo derivado da impressao e a data; nenhum nome de pasta, caminho, entrada ou dado. Arquivo ilegivel vale como
 * "nenhuma aprovacao" (nada e presumido aprovado).
 *
 * <p>Aprovar NAO inicia nada: so grava a decisao. O inicio e outro ato ({@link RunController#start}), que confere a
 * impressao de novo.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.ApprovalStore} (Nekkora-Agent 9b1e7e1): mesmo formato e mesma chave.
 */
public final class ApprovalLedger {
    public static final String SCHEMA = "nekkora-agent/approvals";
    public static final String VERSION = "1.0.0";
    static final int MAX = 200;

    public record Approval(String fingerprint, ExecutionPackage.Kind kind, String name, Instant at) {}

    private final Path file;
    private final AtomicWriter writer;

    /** {@code file}: o arquivo das aprovacoes do perfil (ex.: {@code <dados>/execucao/aprovacoes.json}). */
    public ApprovalLedger(Path file, AtomicWriter writer) {
        this.file = file;
        this.writer = writer;
    }

    public static Path defaultFile(Path dataRoot) {
        return dataRoot.resolve("execucao").resolve("aprovacoes.json");
    }

    public synchronized boolean approved(ExecutionPackage p) {
        return load().stream().anyMatch(a -> a.fingerprint().equals(p.fingerprint()) && a.kind() == p.kind());
    }

    /** A impressao esta aprovada neste perfil? (para a lista de pacotes, sem inspecionar a pasta) */
    public synchronized boolean approved(String fingerprint) {
        return load().stream().anyMatch(a -> a.fingerprint().equals(fingerprint));
    }

    /** Registra a aprovacao (idempotente). Nada e iniciado. */
    public synchronized void approve(ExecutionPackage p, Instant at) throws ExecRefused {
        List<Approval> all = new ArrayList<>(load());
        all.removeIf(a -> a.fingerprint().equals(p.fingerprint()));
        // nunca o nome da pasta nem o do pacote: so o resumo da impressao digital
        all.add(new Approval(p.fingerprint(), p.kind(), "pacote " + p.shortFingerprint(), at));
        while (all.size() > MAX) all.remove(0);
        save(all);
    }

    /** Retira a aprovacao de um pacote (o pacote volta a precisar de aprovacao). */
    public synchronized void revoke(ExecutionPackage p) throws ExecRefused {
        revoke(p.fingerprint());
    }

    public synchronized void revoke(String fingerprint) throws ExecRefused {
        List<Approval> all = new ArrayList<>(load());
        if (all.removeIf(a -> a.fingerprint().equals(fingerprint))) save(all);
    }

    List<Approval> load() {
        if (!Files.isRegularFile(file)) return List.of();
        try {
            Map<String, Object> m = StrictJson.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            if (!SCHEMA.equals(m.get("schema")) || !VERSION.equals(m.get("version")) || !(m.get("approvals") instanceof Map<?, ?> list)) return List.of();
            List<Approval> out = new ArrayList<>();
            for (Object o : list.values()) {
                if (!(o instanceof Map<?, ?> a) || !(a.get("fingerprint") instanceof String fp) || !fp.matches("sha256:[0-9a-f]{64}")
                        || !(a.get("kind") instanceof String k) || !(a.get("name") instanceof String n) || !(a.get("at") instanceof String at)) {
                    return List.of();
                }
                out.add(new Approval(fp, ExecutionPackage.Kind.valueOf(k), n, Instant.parse(at)));
            }
            return out;
        } catch (IOException | StrictJson.SyntaxException | RuntimeException e) {
            return List.of();
        }
    }

    private void save(List<Approval> all) throws ExecRefused {
        // StrictJson nao tem listas: as aprovacoes vao como objeto indexado pela ordem
        StringBuilder b = new StringBuilder("{\n  \"schema\": ").append(StrictJson.quote(SCHEMA)).append(",\n  \"version\": ")
                .append(StrictJson.quote(VERSION)).append(",\n  \"approvals\": {");
        Map<String, Approval> indexed = new LinkedHashMap<>();
        for (int i = 0; i < all.size(); i++) indexed.put(String.format("%04d", i), all.get(i));
        int n = 0;
        for (Map.Entry<String, Approval> e : indexed.entrySet()) {
            Approval a = e.getValue();
            b.append(n++ == 0 ? "\n" : ",\n").append("    ").append(StrictJson.quote(e.getKey())).append(": {\"fingerprint\": ")
                    .append(StrictJson.quote(a.fingerprint())).append(", \"kind\": ").append(StrictJson.quote(a.kind().name())).append(", \"name\": ")
                    .append(StrictJson.quote(a.name())).append(", \"at\": ").append(StrictJson.quote(a.at().toString())).append('}');
        }
        b.append(all.isEmpty() ? "}\n}\n" : "\n  }\n}\n");
        try {
            Files.createDirectories(file.getParent());
            writer.write(file, b.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        }
    }
}
