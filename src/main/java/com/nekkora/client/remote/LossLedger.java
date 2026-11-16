package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001 - o registro MINIMO do que ficou neste computador sem a confirmacao de recebimento da
 * Platform, por tentativa: a fila de registros e/ou a pasta de saida. Serve a duas coisas, e so a elas:
 * <ol>
 * <li>depois que o prazo de guarda vence e o aplicativo remove o que guardava, fica dito AQUI que houve perda, de que tipo,
 * quanto havia e quando - nada do conteudo;</li>
 * <li>avisar a Platform dessa perda quando for possivel. Para isso o registro guarda o token e a epoca da atribuicao, que a
 * Platform exige; eles saem do registro assim que o aviso e aceito ou se mostra impossivel.</li>
 * </ol>
 *
 * <p>Um arquivo por tentativa em {@code guarda/}, protegido pelo sistema operacional ({@link Keyring}): so o mesmo usuario do
 * Windows, neste computador, o le. O registro sai {@link #KEEP_DAYS} dias depois da ultima perda, ou quando nao ha mais nada
 * pendente nem perdido.
 */
final class LossLedger {

    /** Por quantos dias o registro de uma perda fica neste computador depois dela. */
    static final int KEEP_DAYS = 30;

    private static final Pattern ATTEMPT = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    private static final String SUFFIX = ".guarda";

    /** O aviso a Platform de uma perda: ainda nao tentado/aceito, aceito, ou impossivel (a Platform nao reconhece mais a atribuicao). */
    enum Notice {
        PENDING, SENT, IMPOSSIBLE
    }

    /**
     * {@code logLines}/{@code resultFiles}: quanto havia (linhas capturadas; arquivos na pasta de saida); -1 = este tipo nao ficou
     * pendente. {@code logsLostAt}/{@code resultsLostAt}: quando foi removido pelo prazo (segundos); 0 = ainda guardado.
     */
    record Entry(String attemptId, String token, int epoch, long notedAt, int logLines, long logsLostAt, Notice logsNotice, int resultFiles,
                 long resultsLostAt, Notice resultsNotice) {

        boolean logsLost() {
            return logsLostAt > 0;
        }

        boolean resultsLost() {
            return resultsLostAt > 0;
        }

        /** Ainda ha aviso a dar a Platform. */
        boolean owes() {
            return (logsLost() && logsNotice == Notice.PENDING) || (resultsLost() && resultsNotice == Notice.PENDING);
        }

        private boolean anythingHeld() {
            return (logLines >= 0 && !logsLost()) || (resultFiles >= 0 && !resultsLost());
        }

        private long lastLoss() {
            return Math.max(logsLostAt, resultsLostAt);
        }
    }

    private final Path dir;
    private final Keyring keyring;

    LossLedger(Path dir, Keyring keyring) {
        this.dir = dir;
        this.keyring = keyring;
    }

    private Path file(String attemptId) {
        if (attemptId == null || !ATTEMPT.matcher(attemptId).matches()) {
            throw new IllegalArgumentException("tentativa");
        }
        return dir.resolve(attemptId + SUFFIX);
    }

    synchronized Optional<Entry> load(String attemptId) {
        return read(file(attemptId));
    }

    /** A fila de registros desta tentativa ficou no disco sem confirmacao, com {@code lines} linhas capturadas. */
    synchronized void logsHeld(String attemptId, String token, int epoch, long now, int lines) {
        Entry e = load(attemptId).orElse(blank(attemptId, token, epoch, now));
        save(new Entry(attemptId, token, epoch, e.notedAt(), Math.max(0, lines), 0, Notice.PENDING, e.resultFiles(), e.resultsLostAt(), e.resultsNotice()));
    }

    /** A pasta de saida desta tentativa ficou no disco sem confirmacao. Quantos arquivos havia e dito quando ela for removida. */
    synchronized void resultsHeld(String attemptId, String token, int epoch, long now) {
        Entry e = load(attemptId).orElse(blank(attemptId, token, epoch, now));
        save(new Entry(attemptId, token, epoch, e.notedAt(), e.logLines(), e.logsLostAt(), e.logsNotice(), Math.max(0, e.resultFiles()), 0, Notice.PENDING));
    }

    /** O que estava guardado foi confirmado ou retirado sem perda (a pessoa nao tem mais o que esperar deste tipo). */
    synchronized void logsSettled(String attemptId) {
        load(attemptId).ifPresent(e -> {
            if (!e.logsLost()) {
                save(new Entry(e.attemptId(), e.token(), e.epoch(), e.notedAt(), -1, 0, Notice.PENDING, e.resultFiles(), e.resultsLostAt(), e.resultsNotice()));
            }
        });
    }

    /** A fila de registros foi REMOVIDA pelo prazo de guarda. Sem registro previo (sobra de uma queda), nao ha o que avisar. */
    synchronized void logsLost(String attemptId, long now) {
        load(attemptId).ifPresent(e -> {
            if (e.logLines() >= 0 && !e.logsLost()) {
                save(new Entry(e.attemptId(), e.token(), e.epoch(), e.notedAt(), e.logLines(), now, Notice.PENDING, e.resultFiles(), e.resultsLostAt(),
                        e.resultsNotice()));
            }
        });
    }

    /** A pasta de saida foi REMOVIDA pelo prazo de guarda; havia {@code files} arquivos nela. */
    synchronized void resultsLost(String attemptId, int files, long now) {
        load(attemptId).ifPresent(e -> {
            if (e.resultFiles() >= 0 && !e.resultsLost()) {
                save(new Entry(e.attemptId(), e.token(), e.epoch(), e.notedAt(), e.logLines(), e.logsLostAt(), e.logsNotice(), Math.max(0, files), now,
                        Notice.PENDING));
            }
        });
    }

    /** O resultado do aviso a Platform. Quando nao ha mais aviso a dar, o token sai do registro. */
    synchronized void noticed(String attemptId, boolean logs, Notice outcome) {
        load(attemptId).ifPresent(e -> {
            Entry next = logs
                    ? new Entry(e.attemptId(), e.token(), e.epoch(), e.notedAt(), e.logLines(), e.logsLostAt(), outcome, e.resultFiles(), e.resultsLostAt(),
                            e.resultsNotice())
                    : new Entry(e.attemptId(), e.token(), e.epoch(), e.notedAt(), e.logLines(), e.logsLostAt(), e.logsNotice(), e.resultFiles(),
                            e.resultsLostAt(), outcome);
            if (!next.owes() && !next.anythingHeld()) {
                next = new Entry(next.attemptId(), "", next.epoch(), next.notedAt(), next.logLines(), next.logsLostAt(), next.logsNotice(), next.resultFiles(),
                        next.resultsLostAt(), next.resultsNotice());
            }
            save(next);
        });
    }

    /**
     * Todos os registros, do mais antigo ao mais novo. Antes de devolver, tira os que nao tem mais o que dizer: nada guardado
     * e nada perdido, ou perda de mais de {@link #KEEP_DAYS} dias.
     */
    synchronized List<Entry> all(long now) {
        List<Entry> out = new ArrayList<>();
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return out;
        }
        try (java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*" + SUFFIX)) {
            for (Path f : ds) {
                Optional<Entry> e = read(f);
                if (e.isEmpty()) {
                    continue;
                }
                Entry x = e.get();
                boolean nothing = !x.anythingHeld() && !x.logsLost() && !x.resultsLost();
                boolean old = !x.anythingHeld() && x.lastLoss() > 0 && now - x.lastLoss() > KEEP_DAYS * 86_400L;
                if (nothing || old) {
                    Files.deleteIfExists(f);
                    continue;
                }
                out.add(x);
            }
        } catch (IOException | RuntimeException e) {
            // o que ja foi lido vale
        }
        out.sort(Comparator.comparing(Entry::notedAt).thenComparing(Entry::attemptId));
        return out;
    }

    /** O vinculo acabou: os tokens nao servem mais. Os registros de perda ficam (sem token), o resto sai. */
    synchronized void unlink(long now) {
        for (Entry e : all(now)) {
            if (!e.logsLost() && !e.resultsLost()) {
                try {
                    Files.deleteIfExists(file(e.attemptId()));
                } catch (IOException x) {
                    // fica para a proxima leitura
                }
                continue;
            }
            save(new Entry(e.attemptId(), "", e.epoch(), e.notedAt(), e.logsLost() ? e.logLines() : -1, e.logsLostAt(),
                    e.logsNotice() == Notice.PENDING ? Notice.IMPOSSIBLE : e.logsNotice(), e.resultsLost() ? e.resultFiles() : -1, e.resultsLostAt(),
                    e.resultsNotice() == Notice.PENDING ? Notice.IMPOSSIBLE : e.resultsNotice()));
        }
    }

    private static Entry blank(String attemptId, String token, int epoch, long now) {
        return new Entry(attemptId, token, epoch, now, -1, 0, Notice.PENDING, -1, 0, Notice.PENDING);
    }

    private void save(Entry e) {
        String text = String.join("\t", "v1", e.attemptId(), e.token() == null ? "" : e.token(), String.valueOf(e.epoch()), String.valueOf(e.notedAt()),
                String.valueOf(e.logLines()), String.valueOf(e.logsLostAt()), e.logsNotice().name(), String.valueOf(e.resultFiles()),
                String.valueOf(e.resultsLostAt()), e.resultsNotice().name());
        try {
            Files.createDirectories(dir);
            Path target = file(e.attemptId());
            Path fresh = target.resolveSibling(target.getFileName() + ".novo");
            Files.write(fresh, keyring.protect(text.getBytes(StandardCharsets.UTF_8)));
            Files.move(fresh, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException x) {
            // sem conseguir gravar, o registro fica como estava: a guarda no disco nao depende dele
        }
    }

    private Optional<Entry> read(Path f) {
        try {
            if (!Files.isRegularFile(f, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            String[] p = new String(keyring.unprotect(Files.readAllBytes(f)), StandardCharsets.UTF_8).split("\t", -1);
            String name = String.valueOf(f.getFileName());
            if (p.length != 11 || !p[0].equals("v1") || !ATTEMPT.matcher(p[1]).matches() || !name.equals(p[1] + SUFFIX)) {
                return Optional.empty();
            }
            return Optional.of(new Entry(p[1], p[2], Integer.parseInt(p[3]), Long.parseLong(p[4]), Integer.parseInt(p[5]), Long.parseLong(p[6]),
                    Notice.valueOf(p[7]), Integer.parseInt(p[8]), Long.parseLong(p[9]), Notice.valueOf(p[10])));
        } catch (IOException | RuntimeException e) {
            // ilegivel (outro usuario, arquivo cortado): nao e um registro
            return Optional.empty();
        }
    }
}
