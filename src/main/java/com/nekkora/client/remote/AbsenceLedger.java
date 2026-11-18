package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * As tentativas cujo FIM ja foi informado a Platform SEM a afirmacao de que nada delas continua em andamento neste computador
 * (havia um processo filho vivo, ou nao foi possivel conferir naquele momento). Enquanto o registro existir, a cada consulta o
 * aplicativo confere de novo e, quando houver prova, repete o MESMO relato com a afirmacao - e o que libera esta maquina na
 * Platform quando a tentativa ficou la como "resultado nao confirmado".
 *
 * <p>Um arquivo por tentativa, protegido como a credencial (guarda o token da atribuicao). Sai quando a Platform aceita a
 * afirmacao, quando ela nao reconhece mais a atribuicao, quando este computador nao conhece mais a execucao, quando o vinculo
 * acaba, ou depois de {@link #KEEP_DAYS} dias.
 */
final class AbsenceLedger {

    static final int KEEP_DAYS = 30;
    private static final Pattern ATTEMPT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String SUFFIX = ".confirmar";

    /** {@code report}: o estado final ja informado; {@code exitCode}: -1 = sem codigo. */
    record Entry(String attemptId, String token, int epoch, String run, String report, int exitCode, long notedAt, String last) {
    }

    private final Path dir;
    private final Keyring keyring;

    AbsenceLedger(Path dir, Keyring keyring) {
        this.dir = dir;
        this.keyring = keyring;
    }

    private Path file(String attemptId) {
        if (attemptId == null || !ATTEMPT.matcher(attemptId).matches()) {
            throw new IllegalArgumentException("tentativa");
        }
        return dir.resolve(attemptId + SUFFIX);
    }

    /** Anota (uma vez por tentativa). Sem conseguir gravar, nada e anotado: a tentativa so sai por declaracao na Platform. */
    synchronized void note(String attemptId, String token, int epoch, String run, String report, Integer exitCode, String said, long now) {
        if (token == null || token.isEmpty() || run == null || run.isEmpty() || report == null || report.contains("\t") || run.contains("\t")
                || said == null || !said.matches("[A-Z]{1,20}")) {
            return;
        }
        try {
            if (Files.exists(file(attemptId), LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            save(new Entry(attemptId, token, epoch, run, report, exitCode == null ? -1 : exitCode, now, said));
        } catch (RuntimeException x) {
            // sem o registro, este computador nao repete o relato
        }
    }

    /** O que este computador disse por ultimo a Platform sobre os processos da tentativa. */
    synchronized void said(String attemptId, String said) {
        if (said == null || !said.matches("[A-Z]{1,20}")) {
            return;
        }
        read(file(attemptId)).ifPresent(e -> save(new Entry(e.attemptId(), e.token(), e.epoch(), e.run(), e.report(), e.exitCode(), e.notedAt(), said)));
    }

    private void save(Entry e) {
        try {
            String text = String.join("\t", "v2", e.attemptId(), e.token(), String.valueOf(e.epoch()), e.run(), e.report(), String.valueOf(e.exitCode()),
                    String.valueOf(e.notedAt()), e.last());
            Files.createDirectories(dir);
            Path target = file(e.attemptId());
            Path fresh = target.resolveSibling(target.getFileName() + ".novo");
            Files.write(fresh, keyring.protect(text.getBytes(StandardCharsets.UTF_8)));
            Replace.into(fresh, target);
        } catch (IOException | RuntimeException x) {
            // fica como estava
        }
    }

    synchronized void drop(String attemptId) {
        try {
            Files.deleteIfExists(file(attemptId));
        } catch (IOException | RuntimeException x) {
            // fica para a proxima
        }
    }

    /** Os registros, do mais antigo ao mais novo; os vencidos e os ilegiveis saem. */
    synchronized List<Entry> all(long now) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path f : ds) {
                Optional<Entry> e = read(f);
                if (e.isEmpty() || now - e.get().notedAt() > KEEP_DAYS * 86_400L) {
                    Files.deleteIfExists(f);
                    continue;
                }
                out.add(e.get());
            }
        } catch (IOException | RuntimeException e) {
            // o que ja foi lido vale
        }
        out.sort(Comparator.comparing(Entry::notedAt).thenComparing(Entry::attemptId));
        return out;
    }

    /** O vinculo acabou: os tokens nao servem mais, e nada mais pode ser afirmado por este caminho. */
    synchronized void clear() {
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX + "*")) {
            for (Path f : ds) {
                Files.deleteIfExists(f);
            }
        } catch (IOException | RuntimeException e) {
            // o que sobrar vence pelo prazo e nao e aceito pela Platform
        }
    }

    private Optional<Entry> read(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String[] p = new String(keyring.unprotect(Files.readAllBytes(f)), StandardCharsets.UTF_8).split("\t", -1);
            if (p.length != 9 || !p[0].equals("v2") || !ATTEMPT.matcher(p[1]).matches() || !String.valueOf(f.getFileName()).equals(p[1] + SUFFIX)) {
                return Optional.empty();
            }
            return Optional.of(new Entry(p[1], p[2], Integer.parseInt(p[3]), p[4], p[5], Integer.parseInt(p[6]), Long.parseLong(p[7]), p[8]));
        } catch (IOException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
