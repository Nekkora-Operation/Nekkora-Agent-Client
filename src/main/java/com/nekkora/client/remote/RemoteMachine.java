package com.nekkora.client.remote;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;

/**
 * A habilitacao DESTE computador para execucoes solicitadas pela Platform. E um ato da maquina, distinto da entrada de
 * uma pessoa no aplicativo: nada aqui recebe, le ou guarda a sessao de quem entrou, e entrar ou sair nao habilita nem
 * desabilita nada.
 * <ul>
 * <li>{@link #enable}: so com um codigo criado na Platform para isso e com a confirmacao feita neste computador;</li>
 * <li>{@link #pause} e {@link #resume}: o recebimento de novos trabalhos; a referencia e o servidor;</li>
 * <li>{@link #disable}: encerra o vinculo na Platform e remove a credencial deste computador;</li>
 * <li>{@link #beat}: a presenca, enquanto o aplicativo esta aberto.</li>
 * </ul>
 * Nesta versao o aplicativo ainda NAO executa o que a Platform solicitar: ele so mantem o vinculo e a presenca.
 * A execucao feita aqui pela pessoa continua local e nao passa por esta classe.
 */
public final class RemoteMachine implements AutoCloseable {

    public enum Status {
        /** Este computador nao consegue proteger a credencial: a habilitacao nao e oferecida. */
        UNAVAILABLE,
        NOT_ENABLED,
        RECEIVING,
        PAUSED,
        /** Ha um vinculo guardado, mas a Platform o encerrou (ou ele e de outro endereco, ou esta ilegivel). */
        ENDED
    }

    /** {@code inContact}: a ultima presenca foi aceita; {@code false} antes da primeira ou depois de uma falha de rede. */
    public record View(Status status, boolean inContact, Optional<String> credentialExpiresAt, Optional<Integer> pythonEnvironments) {
    }

    /**
     * Uma instalacao de Python que este computador achou. {@code localId} identifica o ambiente AQUI (inclui o caminho) e
     * nunca sai do computador: o que vai para a Platform e um identificador opaco derivado dele.
     */
    public record PythonSeen(String localId, boolean virtual, String version) {
    }

    /** Quem descobre o Python deste computador (entregue por quem monta o aplicativo; esta camada nao cria processos). */
    public interface PythonFinder {
        List<PythonSeen> find();
    }

    static final int MAX_ENVIRONMENTS = 32;
    static final Pattern PYTHON_VERSION = Pattern.compile("^3\\.[0-9]{1,2}\\.[0-9]{1,3}([abc]|rc)?[0-9]{0,2}$");
    /** O inventario e publicado ao habilitar, na primeira presenca de cada abertura e, depois, a cada 6 horas. */
    static final long INVENTORY_EVERY_SECONDS = 6 * 3600;
    static final long INVENTORY_RETRY_SECONDS = 600;

    static final Pattern CODE = Pattern.compile("^nkp1\\.[0-9a-f-]{36}\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}$");
    static final long BEAT_SECONDS = 60;
    /** A credencial e renovada quando faltarem ate estes dias para o vencimento (a mesma janela padrao do servidor). */
    static final long RENEW_WITHIN_DAYS = 30;
    /** Depois de uma falha passageira da renovacao: espera crescente, limitada. A credencial atual continua valendo. */
    static final long RENEW_RETRY_FIRST_SECONDS = 60;
    static final long RENEW_RETRY_MAX_SECONDS = 3600;

    private final MachineVault vault;
    private final MachineChannel channel;
    private final String appVersion;
    private final SecureRandom random = new SecureRandom();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Uma operacao de rede por vez (a presenca nao atravessa um pausar, por exemplo). */
    private final Object operation = new Object();

    private MachineVault.Link link;
    private boolean ended;
    private boolean inContact;
    /** Resposta perdida no resgate: o mesmo codigo e repetido com o MESMO segredo. So em memoria. */
    private String pendingCode;
    private String pendingSecret;
    /** Encerramento em curso: a mesma operacao em toda nova tentativa. So em memoria. */
    private String pendingUnlink;
    private ScheduledExecutorService beats;
    private final java.time.Clock clock;
    /** Inventario de Python: quem descobre (nulo = este aplicativo nao publica), quantos foram publicados e quando tentar de novo. */
    private volatile PythonFinder pythons;
    private Integer published;
    private java.time.Instant inventoryNotBefore = java.time.Instant.MIN;
    /** Renovacao: quando tentar de novo depois de uma falha passageira, e a espera da proxima. So em memoria. */
    private java.time.Instant renewNotBefore = java.time.Instant.MIN;
    private long renewWaitSeconds = RENEW_RETRY_FIRST_SECONDS;

    /** {@code appVersion}: a versao do build, ou vazio na execucao de desenvolvimento. */
    public RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion) {
        this(vault, channel, appVersion, java.time.Clock.systemUTC());
    }

    /** {@code clock}: o relogio que decide a janela de renovacao (os testes adiantam o tempo por ele). */
    RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion, java.time.Clock clock) {
        this.clock = clock;
        this.vault = vault;
        this.channel = channel;
        this.appVersion = appVersion.map(v -> v + "+client").orElse("0.0.0+client.dev");
        Optional<MachineVault.Link> stored = vault.load();
        this.link = stored.orElse(null);
        // vinculo de outro endereco da Platform, ou arquivo que nao se le: a credencial NAO e usada; resta remover
        this.ended = stored.map(l -> !l.origin().equals(channel.origin())).orElse(vault.present());
    }

    public synchronized View view() {
        if (ended) {
            return new View(Status.ENDED, false, Optional.empty(), Optional.empty());
        }
        if (link == null) {
            return new View(vault.available() ? Status.NOT_ENABLED : Status.UNAVAILABLE, false, Optional.empty(), Optional.empty());
        }
        return new View(link.paused() ? Status.PAUSED : Status.RECEIVING, inContact, Optional.of(link.credentialExpiresAt()), Optional.ofNullable(published));
    }

    /** Liga a publicacao do inventario de Python (quem monta o aplicativo entrega a descoberta). */
    public void setPythonFinder(PythonFinder finder) {
        this.pythons = finder;
    }

    /**
     * Publica agora o retrato do Python deste computador. Para a Platform vao so um identificador opaco por ambiente, a
     * versao, o tipo e o instante: nenhum caminho, nome de pasta ou lista de pacotes. Sem vinculo utilizavel, nao faz nada.
     * A descoberta (processos, com prazo) roda FORA da trava das operacoes, para nao segurar pausar ou desabilitar.
     */
    public View publishInventory() throws Refused {
        PythonFinder finder = pythons;
        if (finder == null || usable() == null) {
            return view();
        }
        String observedAt = java.time.OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC).withNano(0).toString();
        List<PythonSeen> seen;
        try {
            seen = finder.find();
        } catch (RuntimeException | LinkageError e) {
            throw new Refused(Problem.UNEXPECTED);
        }
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                return view();
            }
            List<MachineChannel.Published> out = new java.util.ArrayList<>();
            java.util.Set<String> ids = new java.util.HashSet<>();
            try {
                javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
                mac.init(new javax.crypto.spec.SecretKeySpec(vault.inventorySalt(), "HmacSHA256"));
                for (PythonSeen s : seen) {
                    if (s == null || s.localId() == null || s.version() == null || !PYTHON_VERSION.matcher(s.version()).matches() || out.size() >= MAX_ENVIRONMENTS) {
                        continue;
                    }
                    String id = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.localId().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                    if (ids.add(id)) {
                        out.add(new MachineChannel.Published(id, s.version(), s.virtual(), observedAt));
                    }
                }
            } catch (IOException | java.security.GeneralSecurityException e) {
                throw new Refused(Problem.STORAGE);
            }
            int count;
            try {
                count = channel.publishInventory(current.credential(), out);
            } catch (Refused e) {
                synchronized (this) {
                    inventoryNotBefore = clock.instant().plusSeconds(INVENTORY_RETRY_SECONDS);
                }
                if (e.problem() == Problem.LINK_ENDED || e.problem() == Problem.NOT_ENABLED) {
                    failed(e.problem());
                }
                throw e;
            }
            synchronized (this) {
                published = count;
                inventoryNotBefore = clock.instant().plusSeconds(INVENTORY_EVERY_SECONDS);
            }
            changed();
            return view();
        }
    }

    public void addListener(Runnable listener) {
        listeners.add(listener);
    }

    /**
     * Habilita este computador com o codigo criado na Platform. Quem chama ja obteve a confirmacao explicita da pessoa,
     * NESTE computador. Falha: nada fica guardado.
     */
    public View enable(String typedCode) throws Refused {
        synchronized (operation) {
            String code = typedCode == null ? "" : typedCode.strip();
            String secret;
            synchronized (this) {
                if (!vault.available()) {
                    throw new Refused(Problem.STORAGE);
                }
                if (link != null || ended) {
                    throw new Refused(Problem.ALREADY_ENABLED);
                }
                if (!CODE.matcher(code).matches()) {
                    throw new Refused(Problem.CODE_REJECTED);
                }
                if (!code.equals(pendingCode)) {
                    byte[] bytes = new byte[32];
                    random.nextBytes(bytes);
                    pendingCode = code;
                    pendingSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                }
                secret = pendingSecret;
            }
            MachineChannel.Redeemed r;
            try {
                r = channel.redeem(code, secret, appVersion);
            } catch (Refused e) {
                if (e.problem() != Problem.NETWORK && e.problem() != Problem.TIMEOUT && e.problem() != Problem.SERVER && e.problem() != Problem.RATE_LIMITED) {
                    // recusa definitiva: o segredo gerado para este codigo nao serve para mais nada
                    forgetPending();
                }
                throw e;
            }
            MachineVault.Link created;
            try {
                created = new MachineVault.Link(channel.origin(), r.organizationId(), r.agentId(), r.workerId(), r.credentialId(), r.credentialExpiresAt(),
                        secret, false);
                vault.save(created);
            } catch (IOException | IllegalArgumentException e) {
                // a Platform habilitou, mas este computador nao conseguiu guardar: o vinculo e encerrado la, para nao ficar
                // uma maquina habilitada que ninguem controla. Se nem isso for possivel, a Platform a vera sem contato.
                try {
                    channel.unlink("nka1." + r.organizationId() + "." + r.credentialId() + "." + secret, r.agentId(), UUID.randomUUID().toString());
                } catch (Refused ignored) {
                    // sem contato: fica para quem administra revogar na Platform
                }
                forgetPending();
                throw new Refused(Problem.STORAGE);
            }
            synchronized (this) {
                link = created;
                ended = false;
                inContact = true;
                pendingCode = null;
                pendingSecret = null;
            }
            changed();
            return view();
        }
    }

    public View pause() throws Refused {
        return receiving(true);
    }

    public View resume() throws Refused {
        return receiving(false);
    }

    private View receiving(boolean pause) throws Refused {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                throw new Refused(Problem.NOT_ENABLED);
            }
            boolean paused;
            try {
                paused = channel.receiving(current.credential(), pause);
            } catch (Refused e) {
                failed(e.problem());
                throw e;
            }
            store(current.withPaused(paused), true);
            return view();
        }
    }

    /**
     * Desabilita este computador: encerra o vinculo na Platform e remove a credencial daqui. Sem contato com a Platform,
     * NADA e removido e a falha e devolvida: a pessoa tenta de novo, ou quem administra revoga a maquina na Platform.
     * Vinculo que a Platform ja encerrou (ou que este computador nao consegue usar): so a remocao local.
     */
    public View disable() throws Refused {
        synchronized (operation) {
            MachineVault.Link current;
            boolean onlyLocal;
            String op;
            synchronized (this) {
                current = link;
                onlyLocal = ended || current == null;
                if (pendingUnlink == null) {
                    pendingUnlink = UUID.randomUUID().toString();
                }
                op = pendingUnlink;
            }
            if (!onlyLocal) {
                try {
                    channel.unlink(current.credential(), current.agentId(), op);
                } catch (Refused e) {
                    if (e.problem() != Problem.LINK_ENDED) {
                        failed(e.problem());
                        throw e;
                    }
                }
            }
            try {
                vault.clear();
            } catch (IOException e) {
                synchronized (this) {
                    ended = true;
                }
                changed();
                throw new Refused(Problem.STORAGE);
            }
            synchronized (this) {
                link = null;
                ended = false;
                inContact = false;
                pendingUnlink = null;
                published = null;
                inventoryNotBefore = java.time.Instant.MIN;
            }
            changed();
            return view();
        }
    }

    /** Uma presenca. Sem vinculo utilizavel, nao faz nada. Nunca lanca. */
    public void beat() {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                return;
            }
            // perto do vencimento (ou com uma troca interrompida), a credencial e renovada ANTES da presenca: se a troca ja
            // aconteceu no servidor e a resposta se perdeu, a credencial antiga nao serve mais para a presenca
            current = renewIfDue(current);
            if (current == null) {
                return;
            }
            try {
                MachineChannel.Presence p = channel.heartbeat(current.credential(), appVersion);
                MachineVault.Link next = current.withPaused(p.paused());
                try {
                    next = next.withExpiry(p.credentialExpiresAt());
                } catch (IllegalArgumentException e) {
                    // validade fora do formato: mantem a conhecida
                }
                store(next, !next.equals(current));
            } catch (Refused e) {
                failed(e.problem());
                return;
            }
        }
        // com a presenca aceita, o inventario de Python e publicado quando for a hora (fora da trava: a descoberta demora)
        boolean dueNow;
        synchronized (this) {
            dueNow = pythons != null && !clock.instant().isBefore(inventoryNotBefore);
        }
        if (dueNow) {
            try {
                publishInventory();
            } catch (Refused e) {
                // fica para a proxima; a presenca nao depende do inventario
            }
        }
    }

    /**
     * Renova a credencial se estiver na janela, ou conclui uma troca interrompida. Devolve o vinculo a usar agora (o novo,
     * se a troca concluiu; o atual, se nao era hora ou se a falha foi passageira) ou {@code null} se o vinculo acabou.
     * <ol>
     * <li>o segredo novo e gerado aqui e GRAVADO (protegido) antes de qualquer pedido;</li>
     * <li>a troca e pedida com a credencial atual; a resposta traz so o identificador e a validade da nova;</li>
     * <li>a credencial nova e gravada; so entao o segredo pendente e apagado.</li>
     * </ol>
     * Caiu no meio: na proxima vez, a troca e repetida com o MESMO segredo, e o servidor devolve a mesma credencial.
     */
    private MachineVault.Link renewIfDue(MachineVault.Link current) {
        Optional<String> pending = vault.pending(current.credentialId());
        java.time.Instant now = clock.instant();
        if (pending.isEmpty() && !due(current, now)) {
            return current;
        }
        synchronized (this) {
            if (now.isBefore(renewNotBefore)) {
                return current;
            }
        }
        String secret;
        if (pending.isPresent()) {
            secret = pending.get();
        } else {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            secret = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            try {
                vault.savePending(current.credentialId(), secret);
            } catch (IOException e) {
                // sem conseguir guardar o segredo novo, a troca nem e pedida: a credencial atual continua valendo
                retryLater(now);
                return current;
            }
        }
        MachineChannel.Redeemed r;
        try {
            r = channel.renew(current.credential(), secret,
                    new MachineChannel.Redeemed(current.organizationId(), current.agentId(), current.workerId(), current.credentialId(), current.credentialExpiresAt()));
        } catch (Refused e) {
            switch (e.problem()) {
                case LINK_ENDED, NOT_ENABLED -> {
                    // vencida, revogada ou a maquina nao esta mais habilitada: nunca volta a valer
                    ended();
                    return null;
                }
                case RENEWAL_NOT_DUE -> {
                    // o servidor diz que ainda nao e hora: o segredo gerado nao sera usado
                    try {
                        vault.clearPending();
                    } catch (IOException ignored) {
                        // fica para a proxima; o arquivo so vale para esta credencial
                    }
                    retryLater(now);
                    return current;
                }
                default -> {
                    retryLater(now);
                    return current;
                }
            }
        }
        MachineVault.Link renewed;
        try {
            renewed = current.renewed(r.credentialId(), r.credentialExpiresAt(), secret);
            vault.save(renewed);
        } catch (IOException | IllegalArgumentException e) {
            // a troca aconteceu no servidor, mas nao foi gravada aqui: o segredo pendente continua guardado e a troca e
            // repetida depois (o servidor devolve a mesma credencial)
            retryLater(now);
            failed(Problem.STORAGE);
            return null;
        }
        try {
            vault.clearPending();
        } catch (IOException ignored) {
            // o arquivo pendente e de outra credencial agora: nao e mais lido
        }
        synchronized (this) {
            link = renewed;
            renewNotBefore = java.time.Instant.MIN;
            renewWaitSeconds = RENEW_RETRY_FIRST_SECONDS;
        }
        changed();
        return renewed;
    }

    private static boolean due(MachineVault.Link link, java.time.Instant now) {
        try {
            return !java.time.OffsetDateTime.parse(link.credentialExpiresAt()).toInstant().isAfter(now.plus(java.time.Duration.ofDays(RENEW_WITHIN_DAYS)));
        } catch (java.time.DateTimeException e) {
            return false;
        }
    }

    private synchronized void retryLater(java.time.Instant now) {
        renewNotBefore = now.plusSeconds(renewWaitSeconds);
        renewWaitSeconds = Math.min(renewWaitSeconds * 2, RENEW_RETRY_MAX_SECONDS);
    }

    /** Inicia a presenca periodica (uma agora, depois a cada minuto) enquanto o aplicativo estiver aberto. */
    public synchronized void start() {
        if (beats != null) {
            return;
        }
        beats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "presenca-remota");
            t.setDaemon(true);
            return t;
        });
        beats.scheduleWithFixedDelay(() -> {
            try {
                beat();
            } catch (RuntimeException | LinkageError e) {
                // a presenca nunca derruba o aplicativo
            }
        }, 0, BEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** Fechar o aplicativo NAO desabilita a maquina: so a presenca para, e a Platform a vera sem contato. */
    @Override
    public synchronized void close() {
        if (beats != null) {
            beats.shutdownNow();
            beats = null;
        }
    }

    private synchronized MachineVault.Link usable() {
        return ended ? null : link;
    }

    private synchronized void forgetPending() {
        pendingCode = null;
        pendingSecret = null;
    }

    private void store(MachineVault.Link next, boolean persist) {
        if (persist) {
            try {
                vault.save(next);
            } catch (IOException e) {
                // o recebimento vale no servidor; o que ficou no disco e so o ultimo conhecido
            }
        }
        boolean notify;
        synchronized (this) {
            notify = !next.equals(link) || !inContact;
            link = next;
            inContact = true;
        }
        if (notify) {
            changed();
        }
    }

    /** O vinculo acabou de vez (a propria renovacao foi recusada por credencial vencida, revogada ou maquina nao habilitada). */
    private void ended() {
        boolean notify;
        synchronized (this) {
            notify = !ended || inContact;
            ended = true;
            inContact = false;
        }
        if (notify) {
            changed();
        }
    }

    private void failed(Problem problem) {
        boolean notify;
        synchronized (this) {
            boolean wasEnded = ended;
            boolean wasInContact = inContact;
            // com uma troca de credencial interrompida, a recusa da credencial antiga nao e o fim do vinculo: a troca pode
            // ter acontecido no servidor. Quem decide e a repeticao da troca, na proxima presenca
            boolean renewing = link != null && vault.pending(link.credentialId()).isPresent();
            if ((problem == Problem.LINK_ENDED && !renewing) || problem == Problem.NOT_ENABLED) {
                ended = true;
            }
            // falha so deste computador (gravar): o contato com a Platform nao e o problema, mas o estado nao foi confirmado
            inContact = false;
            notify = wasEnded != ended || wasInContact;
        }
        if (notify) {
            changed();
        }
    }

    private void changed() {
        for (Runnable l : listeners) {
            l.run();
        }
    }
}
