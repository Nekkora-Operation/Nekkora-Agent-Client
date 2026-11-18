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
    public record View(Status status, boolean inContact, Optional<String> credentialExpiresAt) {
    }

    static final Pattern CODE = Pattern.compile("^nkp1\\.[0-9a-f-]{36}\\.[0-9a-f-]{36}\\.[A-Za-z0-9_-]{43}$");
    static final long BEAT_SECONDS = 60;

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

    /** {@code appVersion}: a versao do build, ou vazio na execucao de desenvolvimento. */
    public RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion) {
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
            return new View(Status.ENDED, false, Optional.empty());
        }
        if (link == null) {
            return new View(vault.available() ? Status.NOT_ENABLED : Status.UNAVAILABLE, false, Optional.empty());
        }
        return new View(link.paused() ? Status.PAUSED : Status.RECEIVING, inContact, Optional.of(link.credentialExpiresAt()));
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
            }
        }
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

    private void failed(Problem problem) {
        boolean notify;
        synchronized (this) {
            boolean wasEnded = ended;
            boolean wasInContact = inContact;
            if (problem == Problem.LINK_ENDED || problem == Problem.NOT_ENABLED) {
                ended = true;
            }
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
