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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001 - o registro MINIMO do que ficou neste computador sem a confirmacao de recebimento da
 * Platform, por tentativa: a fila de registros e/ou a pasta de saida. Serve a tres coisas, e so a elas:
 * <ol>
 * <li>CONTINUAR A ENTREGA depois que a tentativa terminou: informar o desfecho nao encerra o envio. Enquanto nada o impede
 * ({@link Block#NONE}), o aplicativo tenta de novo com o token e a epoca guardados aqui, ate a Platform confirmar, o prazo de
 * guarda vencer, ou surgir um impedimento - que fica dito, com o motivo, e nunca e contornado;</li>
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
     * Por que o que esta guardado NAO e mais enviado. {@code NONE}: nada impede, o envio continua sendo tentado.
     * {@code LIMIT}: a Platform fechou o envio sabendo que ha arquivos fora do limite - eles so existem aqui.
     * {@code CONFLICT}: a Platform tem outra coisa, ou ja fechou o envio com outra contagem - nao ha como confirmar.
     * {@code AUTHORIZATION}: a credencial desta maquina deixou de valer, ou a Platform nao reconhece mais esta atribuicao.
     */
    enum Block {
        NONE, LIMIT, CONFLICT, AUTHORIZATION
    }

    /**
     * {@code logLines}/{@code resultFiles}: quanto havia (linhas capturadas; arquivos na pasta de saida); -1 = este tipo nao ficou
     * pendente. {@code logsLostAt}/{@code resultsLostAt}: quando foi removido pelo prazo (segundos); 0 = ainda guardado.
     */
    record Entry(String attemptId, String token, int epoch, long notedAt, int logLines, long logsLostAt, Notice logsNotice, int resultFiles,
                 long resultsLostAt, Notice resultsNotice, String run, Block logsBlock, Block resultsBlock) {

        /** A fila de registros esta guardada neste computador (nem confirmada, nem removida). */
        boolean logsHeld() {
            return logLines >= 0 && !logsLost();
        }

        /** A pasta de saida esta guardada neste computador (nem confirmada, nem removida). */
        boolean resultsHeld() {
            return resultFiles >= 0 && !resultsLost();
        }

        /** Ha o que tentar enviar de novo: algo guardado, sem impedimento, e o token da atribuicao ainda esta aqui. */
        boolean deliverable() {
            return !token.isEmpty() && ((logsHeld() && logsBlock == Block.NONE) || (resultsHeld() && resultsBlock == Block.NONE));
        }

        private Entry with(int lines, long logsLost, Notice logsNote, Block logsWhy, int files, long resultsLost, Notice resultsNote, Block resultsWhy) {
            return new Entry(attemptId, token, epoch, notedAt, lines, logsLost, logsNote, files, resultsLost, resultsNote, run, logsWhy, resultsWhy);
        }

        private Entry withoutToken() {
            return new Entry(attemptId, "", epoch, notedAt, logLines, logsLostAt, logsNotice, resultFiles, resultsLostAt, resultsNotice, run, logsBlock,
                    resultsBlock);
        }

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

        boolean anythingHeld() {
            return logsHeld() || resultsHeld();
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

    /**
     * A tentativa TERMINOU e o desfecho esta na Platform: o que ela deixou aqui (a fila de registros e, se {@code run} nao e
     * nulo, a pasta de saida da execucao) entra na entrega continuada, sem impedimento. Repetir nao muda a data nem o que ja
     * estiver dito (uma queda entre este registro e a limpeza do diario repete a chamada).
     */
    synchronized void enqueue(String attemptId, String token, int epoch, String run, long now) {
        Entry e = load(attemptId).orElse(null);
        if (e != null) {
            return;
        }
        save(new Entry(attemptId, token, epoch, now, 0, 0, Notice.PENDING, run == null ? -1 : 0, 0, Notice.PENDING, run == null ? "" : run, Block.NONE,
                Block.NONE));
    }

    /**
     * A fila de registros desta tentativa esta no disco sem confirmacao, com {@code lines} linhas capturadas; {@code why}: o que
     * impede o envio (ou {@link Block#NONE}: continua sendo tentado).
     */
    synchronized void logsHeld(String attemptId, String token, int epoch, long now, int lines, Block why) {
        Entry e = load(attemptId).orElse(blank(attemptId, token, epoch, now));
        save(e.with(Math.max(0, lines), 0, Notice.PENDING, why, e.resultFiles(), e.resultsLostAt(), e.resultsNotice(), e.resultsBlock()));
    }

    /** A pasta de saida desta tentativa esta no disco sem confirmacao; {@code why}: o que impede o envio (ou {@link Block#NONE}). */
    synchronized void resultsHeld(String attemptId, String token, int epoch, long now, Block why) {
        Entry e = load(attemptId).orElse(blank(attemptId, token, epoch, now));
        save(e.with(e.logLines(), e.logsLostAt(), e.logsNotice(), e.logsBlock(), Math.max(0, e.resultFiles()), 0, Notice.PENDING, why));
    }

    /** O que estava guardado foi confirmado ou retirado sem perda (a pessoa nao tem mais o que esperar deste tipo). */
    synchronized void logsSettled(String attemptId) {
        load(attemptId).ifPresent(e -> {
            if (!e.logsLost()) {
                settle(e.with(-1, 0, Notice.PENDING, Block.NONE, e.resultFiles(), e.resultsLostAt(), e.resultsNotice(), e.resultsBlock()));
            }
        });
    }

    /** A pasta de saida foi confirmada pela Platform e saiu daqui, sem perda. */
    synchronized void resultsSettled(String attemptId) {
        load(attemptId).ifPresent(e -> {
            if (!e.resultsLost()) {
                settle(e.with(e.logLines(), e.logsLostAt(), e.logsNotice(), e.logsBlock(), -1, 0, Notice.PENDING, Block.NONE));
            }
        });
    }

    /** Grava; se nao sobrou nada guardado nem perdido, o registro (e o token dele) sai. */
    private void settle(Entry e) {
        if (!e.anythingHeld() && !e.logsLost() && !e.resultsLost()) {
            try {
                Files.deleteIfExists(file(e.attemptId()));
            } catch (IOException x) {
                save(e.withoutToken());
            }
            return;
        }
        save(e);
    }

    /** A Platform nao reconhece mais esta atribuicao: o que esta guardado dela nao e mais enviado, e isso fica dito. */
    synchronized void blocked(String attemptId, Block why) {
        load(attemptId).ifPresent(e -> save(e.with(e.logLines(), e.logsLostAt(), e.logsNotice(), e.logsHeld() ? why : e.logsBlock(), e.resultFiles(),
                e.resultsLostAt(), e.resultsNotice(), e.resultsHeld() ? why : e.resultsBlock())));
    }

    /** A fila de registros foi REMOVIDA pelo prazo de guarda. Sem registro previo (sobra de uma queda), nao ha o que avisar. */
    synchronized void logsLost(String attemptId, long now) {
        load(attemptId).ifPresent(e -> {
            if (e.logLines() >= 0 && !e.logsLost()) {
                // sem o token (vinculo encerrado), nao ha como avisar a Platform: o aviso ja nasce impossivel
                save(e.with(e.logLines(), now, e.token().isEmpty() ? Notice.IMPOSSIBLE : Notice.PENDING, e.logsBlock(), e.resultFiles(), e.resultsLostAt(),
                        e.resultsNotice(), e.resultsBlock()));
            }
        });
    }

    /** A pasta de saida foi REMOVIDA pelo prazo de guarda; havia {@code files} arquivos nela. */
    synchronized void resultsLost(String attemptId, int files, long now) {
        load(attemptId).ifPresent(e -> {
            if (e.resultFiles() >= 0 && !e.resultsLost()) {
                save(e.with(e.logLines(), e.logsLostAt(), e.logsNotice(), e.logsBlock(), Math.max(0, files), now,
                        e.token().isEmpty() ? Notice.IMPOSSIBLE : Notice.PENDING, e.resultsBlock()));
            }
        });
    }

    /** O resultado do aviso a Platform. Quando nao ha mais aviso a dar, o token sai do registro. */
    synchronized void noticed(String attemptId, boolean logs, Notice outcome) {
        load(attemptId).ifPresent(e -> {
            Entry next = logs
                    ? e.with(e.logLines(), e.logsLostAt(), outcome, e.logsBlock(), e.resultFiles(), e.resultsLostAt(), e.resultsNotice(), e.resultsBlock())
                    : e.with(e.logLines(), e.logsLostAt(), e.logsNotice(), e.logsBlock(), e.resultFiles(), e.resultsLostAt(), outcome, e.resultsBlock());
            if (!next.owes() && !next.anythingHeld()) {
                next = next.withoutToken();
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

    /**
     * O vinculo acabou: os tokens nao servem mais e saem dos registros. O que ainda esta guardado NAO pode mais ser enviado
     * (a revogacao nao e contornada): fica dito, com o motivo {@link Block#AUTHORIZATION}, ate o prazo de guarda o remover. O
     * aviso de uma perda que a Platform ainda nao conhecia vira impossivel.
     */
    synchronized void unlink(long now) {
        for (Entry e : all(now)) {
            save(e.with(e.logLines(), e.logsLostAt(), e.logsNotice() == Notice.PENDING && e.logsLost() ? Notice.IMPOSSIBLE : e.logsNotice(),
                    e.logsHeld() ? Block.AUTHORIZATION : e.logsBlock(), e.resultFiles(), e.resultsLostAt(),
                    e.resultsNotice() == Notice.PENDING && e.resultsLost() ? Notice.IMPOSSIBLE : e.resultsNotice(),
                    e.resultsHeld() ? Block.AUTHORIZATION : e.resultsBlock()).withoutToken());
        }
    }

    private static Entry blank(String attemptId, String token, int epoch, long now) {
        return new Entry(attemptId, token, epoch, now, -1, 0, Notice.PENDING, -1, 0, Notice.PENDING, "", Block.NONE, Block.NONE);
    }

    private void save(Entry e) {
        String text = String.join("\t", "v2", e.attemptId(), e.token() == null ? "" : e.token(), String.valueOf(e.epoch()), String.valueOf(e.notedAt()),
                String.valueOf(e.logLines()), String.valueOf(e.logsLostAt()), e.logsNotice().name(), String.valueOf(e.resultFiles()),
                String.valueOf(e.resultsLostAt()), e.resultsNotice().name(), e.run() == null ? "" : e.run(), e.logsBlock().name(), e.resultsBlock().name());
        try {
            Files.createDirectories(dir);
            Path target = file(e.attemptId());
            Path fresh = target.resolveSibling(target.getFileName() + ".novo");
            Files.write(fresh, keyring.protect(text.getBytes(StandardCharsets.UTF_8)));
            Replace.into(fresh, target);
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
            boolean v1 = p.length == 11 && p[0].equals("v1");
            boolean v2 = p.length == 14 && p[0].equals("v2");
            if ((!v1 && !v2) || !ATTEMPT.matcher(p[1]).matches() || !name.equals(p[1] + SUFFIX)) {
                return Optional.empty();
            }
            // v1 (antes da entrega continuada): o que ficou guardado era o que a Platform nao tinha confirmado - sem novo envio
            return Optional.of(new Entry(p[1], p[2], Integer.parseInt(p[3]), Long.parseLong(p[4]), Integer.parseInt(p[5]), Long.parseLong(p[6]),
                    Notice.valueOf(p[7]), Integer.parseInt(p[8]), Long.parseLong(p[9]), Notice.valueOf(p[10]), v2 ? p[11] : "",
                    v2 ? Block.valueOf(p[12]) : Block.CONFLICT, v2 ? Block.valueOf(p[13]) : Block.CONFLICT));
        } catch (IOException | RuntimeException e) {
            // ilegivel (outro usuario, arquivo cortado): nao e um registro
            return Optional.empty();
        }
    }
}
