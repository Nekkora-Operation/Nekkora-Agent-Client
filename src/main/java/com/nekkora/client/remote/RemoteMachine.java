package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.file.Path;
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
 * Com quem executa ligado ({@link #setWork}), o aplicativo declara que executa, recebe a tentativa, baixa o pacote e a conduz
 * ate o desfecho ({@link #pull}). Esta camada nao cria processos nem abre pacotes: so fala com a Platform e guarda o diario.
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
    /** De quanto em quanto tempo a maquina pergunta a Platform o que ha para ela (ponto de partida de laboratorio). */
    static final long PULL_SECONDS = 15;
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
    /** O diario da entrega: a tentativa recebida e ainda nao resolvida com a Platform. */
    private final AttemptJournal journal;
    /** Quem executa neste computador (nulo = este aplicativo nao executa o que a Platform solicitar). */
    private volatile Work work;

    /** {@code appVersion}: a versao do build, ou vazio na execucao de desenvolvimento. */
    public RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion) {
        this(vault, channel, appVersion, java.time.Clock.systemUTC());
    }

    /** {@code clock}: o relogio que decide a janela de renovacao (os testes adiantam o tempo por ele). */
    RemoteMachine(MachineVault vault, MachineChannel channel, Optional<String> appVersion, java.time.Clock clock) {
        this.clock = clock;
        this.vault = vault;
        this.journal = vault.journal();
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

    /**
     * Quem EXECUTA neste computador (entregue por quem monta o aplicativo: esta camada nao cria processos, nao abre pacotes
     * e nao conhece o motor de execucao). Sem ele, o aplicativo nao declara que executa e a Platform nao lhe entrega nada.
     */
    public interface Work {
        /** Ha uma execucao em andamento neste computador (de qualquer tipo). */
        boolean busy();

        /** O caminho da pasta de trabalho da tentativa (ainda nao criada). Vazio: nao ha onde trabalhar. */
        Optional<String> dir(String attemptId);

        /**
         * Confere o pacote baixado, o Python e as dependencias, e INICIA. Devolve o identificador da execucao neste computador.
         * Nao inicia: {@link WorkRefused}, com o motivo.
         */
        String start(Job job) throws WorkRefused;

        /** O que a execucao e agora. Vazio: este computador nao a conhece (mais). */
        Optional<Progress> progress(String run);

        /** Pede que a execucao pare. */
        void cancel(String run);

        /** Apaga o codigo da tentativa (o ZIP e a copia extraida). O que a automacao gravou na saida fica. */
        void discard(String attemptId);
    }

    /** {@code pythonLocalId}: a identidade LOCAL do Python escolhido (com o caminho); nunca sai deste computador. */
    public record Job(String attemptId, String organizationId, String workspaceId, String automationId, String automationVersionId, String zip,
                      String sha256, String fingerprint, List<String> dependencies, String pythonLocalId, String dir) {
    }

    /** {@code state}: RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT ou UNKNOWN (terminou sem que o fim fosse observado). */
    public record Progress(String state, Integer exitCode) {
    }

    /** {@code reason}: um motivo de recusa do contrato da maquina (PACKAGE_INVALID, ENVIRONMENT_GONE, DEPENDENCIES_MISSING...). */
    public static final class WorkRefused extends Exception {
        private static final long serialVersionUID = 1L;
        private final String reason;

        public WorkRefused(String reason) {
            super(reason, null, false, false);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    /** O unico tipo de pacote que este aplicativo executa. */
    static final String RUNNABLE_RULE = "nekkora-agent/external-python-fingerprint/1";

    /** {@code true} se este aplicativo executa o que a Platform solicitar (declara a capacidade na presenca). */
    public boolean executes() {
        return work != null;
    }

    /** Liga a execucao do que a Platform solicitar (quem monta o aplicativo entrega quem executa). */
    public void setWork(Work work) {
        this.work = work;
    }

    /**
     * Uma consulta de entrega. Nunca lanca. Com uma tentativa no diario, segue com ela (mesmo com o recebimento pausado); sem
     * tentativa no diario, so pergunta a Platform se este aplicativo executa ({@link #setWork}).
     * <ol>
     * <li>a tentativa entregue e GRAVADA no diario (token protegido) antes de qualquer confirmacao; se nao der para gravar,
     * nada e confirmado e a Platform a entrega de novo, com token e epoca novos;</li>
     * <li>o recebimento e confirmado com o token e a epoca da entrega; repetir (resposta perdida, aplicativo fechado) nao
     * muda nada;</li>
     * <li>PREPARING: o pacote da tentativa e baixado (so chega inteiro e com o SHA-256 declarado) e o Python escolhido e
     * localizado NESTE computador; se ele nao existe mais, a tentativa e recusada - nenhum outro e escolhido;</li>
     * <li>STARTING: a Platform reconfere as condicoes da solicitacao; se algo caiu (ou o cancelamento foi pedido), NADA e
     * iniciado;</li>
     * <li>quem executa confere o pacote, o Python e as dependencias e inicia; a intencao de iniciar e gravada ANTES e o
     * identificador da execucao logo depois: se o aplicativo fechar entre os dois, a tentativa termina sem prova do resultado
     * e nada e lancado de novo;</li>
     * <li>RUNNING: a cada consulta o estado e repetido (renova o prazo) e o pedido de cancelamento e obedecido;</li>
     * <li>o desfecho e informado; so depois de a Platform aceitar, o diario e apagado e o codigo da tentativa e removido.</li>
     * </ol>
     * Token ou epoca que nao valem mais, tentativa que a Platform ja encerrou ou nao tem: o que estiver em execucao e parado, o
     * diario e apagado e nada e alterado la. Falha de rede: fica tudo como esta, para a proxima consulta - uma execucao em
     * andamento NAO e interrompida por falta de rede.
     */
    public void pull() {
        synchronized (operation) {
            MachineVault.Link current = usable();
            if (current == null) {
                return;
            }
            AttemptJournal.Entry pending = journal.load(current.agentId()).orElse(null);
            if (pending == null && work == null) {
                return;
            }
            try {
                deliver(current, pending);
            } catch (Refused e) {
                if (e.problem() == Problem.LINK_ENDED || e.problem() == Problem.NOT_ENABLED) {
                    failed(e.problem());
                }
            }
        }
    }

    private void deliver(MachineVault.Link current, AttemptJournal.Entry e) throws Refused {
        String credential = current.credential();
        if (e == null) {
            MachineChannel.Offer o = channel.next(credential).offer();
            if (o == null) {
                return;
            }
            try {
                e = AttemptJournal.Entry.received(current.agentId(), o);
                journal.save(e);
            } catch (IOException | IllegalArgumentException x) {
                // nao gravou: nao confirma. A tentativa continua oferecida no servidor
                return;
            }
        }
        Work w = work;
        try {
            if (!e.acked()) {
                if (!channel.ack(credential, e.attemptId(), e.token(), e.epoch()).proceed()) {
                    // a Platform ja a encerrou (cancelada ou recusada antes de chegar aqui)
                    forget(e, w);
                    return;
                }
                e = e.withAck();
                keep(e);
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.STARTING) {
                // o aplicativo fechou entre pedir o inicio e gravar a execucao: nao ha prova de nada, e nada e lancado de novo
                e = e.reporting("ENDED_UNKNOWN", null, null);
                keep(e);
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.RECEIVED) {
                e = begin(current, e, w);
                if (e == null || e.report() == null) {
                    // acabou aqui, ou esta em execucao (o estado acabou de ser informado): o resto fica para a proxima consulta
                    return;
                }
            }
            if (e.report() == null && e.phase() == AttemptJournal.Phase.RUNNING) {
                Progress p = w == null ? null : w.progress(e.run()).orElse(null);
                if (p != null && "RUNNING".equals(p.state())) {
                    // repetir o estado renova o prazo; o pedido de cancelamento vem na resposta
                    if (channel.state(credential, e.attemptId(), e.token(), e.epoch(), "RUNNING", null, null).cancelRequested()) {
                        w.cancel(e.run());
                    }
                    return;
                }
                String end = p == null || "UNKNOWN".equals(p.state()) ? "ENDED_UNKNOWN" : p.state();
                boolean coded = p != null && p.exitCode() != null && (end.equals("SUCCEEDED") || end.equals("FAILED") || end.equals("TIMED_OUT"));
                e = e.reporting(end, null, coded ? p.exitCode() : null);
                keep(e);
            }
            channel.state(credential, e.attemptId(), e.token(), e.epoch(), e.report(), e.reason(), e.exitCode());
            forget(e, w);
        } catch (Refused x) {
            if (x.problem() == Problem.ATTEMPT_STALE || x.problem() == Problem.ATTEMPT_GONE || x.problem() == Problem.ATTEMPT_TRANSITION) {
                // esta entrega nao vale mais: o que ela iniciou aqui para, e nada e alterado na Platform
                if (w != null && e.run() != null) {
                    w.cancel(e.run());
                }
                forget(e, w);
                return;
            }
            throw x;
        }
    }

    /**
     * Da tentativa recebida ate a execucao iniciada. Devolve a entrada como ficou (em execucao, ou com a recusa a informar), ou
     * {@code null} se a tentativa acabou aqui (a Platform mandou nao seguir).
     */
    private AttemptJournal.Entry begin(MachineVault.Link current, AttemptJournal.Entry e, Work w) throws Refused {
        String credential = current.credential();
        if (w == null || !RUNNABLE_RULE.equals(e.packageFingerprintRule()) || e.workspaceId() == null || e.pythonEnvironmentId() == null) {
            return refuse(e, "CLIENT_INCOMPATIBLE");
        }
        if (w.busy()) {
            return refuse(e, "BUSY");
        }
        if (!channel.state(credential, e.attemptId(), e.token(), e.epoch(), "PREPARING", null, null).proceed()) {
            forget(e, w);
            return null;
        }
        Path dir;
        try {
            dir = w.dir(e.attemptId()).map(Path::of).orElse(null);
        } catch (java.nio.file.InvalidPathException x) {
            dir = null;
        }
        if (dir == null) {
            return refuse(e, "DISK_PRESSURE");
        }
        Path zip = dir.resolve("pacote.zip");
        String sha;
        try {
            w.discard(e.attemptId());
            java.nio.file.Files.createDirectories(dir);
            sha = channel.download(credential, e.attemptId(), e.token(), e.epoch(), zip);
        } catch (IOException x) {
            return refuse(e, "DISK_PRESSURE");
        } catch (Refused x) {
            if (x.problem() == Problem.PACKAGE_UNAVAILABLE || x.problem() == Problem.STORAGE) {
                return refuse(e, x.problem() == Problem.STORAGE ? "DISK_PRESSURE" : "PACKAGE_INVALID");
            }
            throw x;
        }
        String python = localPython(e.pythonEnvironmentId());
        if (python == null) {
            // o Python escolhido nao esta mais neste computador: nenhum outro e escolhido
            return refuse(e, "ENVIRONMENT_GONE");
        }
        // imediatamente antes de iniciar, a Platform reconfere; cancelamento pedido ou condicao perdida: nada e iniciado
        if (!channel.state(credential, e.attemptId(), e.token(), e.epoch(), "STARTING", null, null).proceed()) {
            forget(e, w);
            return null;
        }
        e = e.starting();
        try {
            journal.save(e);
        } catch (IOException x) {
            // sem conseguir gravar a intencao, nao inicia: um reinicio nao saberia que algo foi lancado
            return refuse(e.reporting(null, null, null), "DISK_PRESSURE");
        }
        String run;
        try {
            run = w.start(new Job(e.attemptId(), current.organizationId(), e.workspaceId(), e.automationId(), e.automationVersionId(),
                    zip.toString(), sha, e.packageFingerprint(), e.dependencies(), python, dir.toString()));
        } catch (WorkRefused x) {
            return refuse(e, x.reason());
        } catch (RuntimeException x) {
            // quem executa falhou sem dizer se lancou: sem prova de nada
            e = e.reporting("ENDED_UNKNOWN", null, null);
            keep(e);
            return e;
        }
        e = e.running(run);
        keep(e);
        if (channel.state(credential, e.attemptId(), e.token(), e.epoch(), "RUNNING", null, null).cancelRequested()) {
            w.cancel(run);
        }
        return e;
    }

    private AttemptJournal.Entry refuse(AttemptJournal.Entry e, String reason) {
        AttemptJournal.Entry refused = e.reporting("REFUSED", reason, null);
        keep(refused);
        return refused;
    }

    /** A identidade local do Python cujo identificador opaco e o indicado, se ele ainda esta neste computador. */
    private String localPython(String environmentId) {
        PythonFinder finder = pythons;
        if (finder == null) {
            return null;
        }
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(vault.inventorySalt(), "HmacSHA256"));
            for (PythonSeen s : finder.find()) {
                if (s != null && s.localId() != null && environmentId.equals(
                        Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(s.localId().getBytes(java.nio.charset.StandardCharsets.UTF_8))))) {
                    return s.localId();
                }
            }
        } catch (IOException | java.security.GeneralSecurityException | RuntimeException | LinkageError x) {
            // sem conseguir conferir, o ambiente nao e dado como presente
        }
        return null;
    }

    private void keep(AttemptJournal.Entry e) {
        try {
            journal.save(e);
        } catch (IOException x) {
            // o que ja estava gravado continua valendo: a etapa e repetida (sem efeito no servidor) na proxima abertura
        }
    }

    /** A tentativa esta resolvida com a Platform: o diario e apagado e o codigo dela e removido deste computador. */
    private void forget(AttemptJournal.Entry e, Work w) {
        try {
            journal.clear();
        } catch (IOException x) {
            // fica para a proxima consulta: a Platform respondera que a tentativa ja terminou
        }
        if (w != null) {
            try {
                w.discard(e.attemptId());
            } catch (RuntimeException x) {
                // a limpeza do codigo e tentada de novo na proxima tentativa nesta pasta
            }
        }
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
                MachineChannel.Presence p = channel.heartbeat(current.credential(), appVersion, work != null, journal.load(current.agentId()).isPresent() || (work != null && work.busy()));
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
        beats.scheduleWithFixedDelay(() -> {
            try {
                pull();
            } catch (RuntimeException | LinkageError e) {
                // a consulta de entrega nunca derruba o aplicativo
            }
        }, 5, PULL_SECONDS, TimeUnit.SECONDS);
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
