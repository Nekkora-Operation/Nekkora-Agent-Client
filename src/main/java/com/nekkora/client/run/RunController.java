package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.JsonReader;
import com.nekkora.client.pack.StrictJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A execucao local de um pacote Python externo aprovado (sem Swing: a interface so observa). Regras:
 * <ul>
 *   <li>UMA execucao ativa por vez; pedido concorrente e recusado ({@code AGT-EXE-014}), sempre;</li>
 *   <li>nada comeca sozinho: so {@link #start} com um pedido explicito; antes de lancar, confere o pacote (hashes de novo)
 *       e a impressao digital do pedido, a aprovacao da MESMA impressao, a sessao interativa, o Python (versao e
 *       dependencias na versao exata; nada e instalado) e a pasta de saida;</li>
 *   <li>roda da copia conferida, com argumentos em lista (sem shell) e ambiente minimo; a saida do processo e drenada e
 *       descartada (so a contagem de bytes fica): nenhum valor da automacao chega ao registro;</li>
 *   <li>fim: o estado vem do codigo de saida e do sinal opcional do script ({@link Launch#classify}); cancelamento e
 *       prazo encerram so a arvore possuida ({@link ProcessOwner});</li>
 *   <li>bloqueio da tela e suspensao do Windows (salto do relogio) durante a execucao sao registrados; depois de uma
 *       suspensao, sem sinal do script, o resultado e {@code OUTCOME_UNKNOWN};</li>
 *   <li>cada registro leva o {@link RunContext} do INICIO (organizacao, ambiente, pacote, automacao), que nunca muda;</li>
 *   <li>reinicio do aplicativo: {@link #reconcile} adota o processo que ainda e o mesmo (PID + inicio + executavel), para
 *       que a vaga unica continue ocupada e o cancelamento funcione, ou fecha o registro sem inventar resultado; nada e
 *       relancado e o fim de um processo nao observado e sempre {@code OUTCOME_UNKNOWN};</li>
 *   <li>depois do lancamento, falha ao gravar o {@code estado.json} nunca deixa o processo sem dono nem muda o desfecho:
 *       o acompanhamento segue, o disco fica no ultimo estado gravado, a gravacao e tentada de novo e a tela e avisada
 *       ({@link Snapshot#recordSaved}).</li>
 * </ul>
 * Nada aqui fala com a rede nem com a Platform; os registros sao locais e nao ha fila de envio.
 *
 * <p>Linhas: {@link #start} bloqueia durante as conferencias (sonda do Python: ate 25 s por chamada) e deve ser chamado
 * fora da linha de eventos da interface. Os {@link Listener} sao chamados numa linha propria ({@code
 * nekkora-client-execucao}) ou na linha que chamou {@link #start}: a interface repassa para a linha de eventos.
 *
 * <p>Portado de {@code com.nekkora.agent.exec.ExecutionController} (Nekkora-Agent 9b1e7e1), so para o pacote Python
 * externo e sem os ganchos da Platform.
 */
public final class RunController implements AutoCloseable {

    /**
     * Pedido tipado (nenhuma linha de comando livre).
     *
     * @param packageDir pasta do pacote guardado ({@code LocalPackages.Item#dir})
     * @param context a quem a execucao pertence; a impressao digital dele e a que a pessoa escolheu e aprovou
     * @param python o interpretador escolhido pela pessoa
     * @param outputRoot pasta de saida escolhida (existente; fora do pacote e da pasta de dados do aplicativo)
     * @param timeout prazo ({@link #DEFAULT_TIMEOUT} se {@code null})
     */
    public record Request(Path packageDir, RunContext context, Path python, Path outputRoot, Duration timeout) {
        public Request {
            Objects.requireNonNull(packageDir);
            Objects.requireNonNull(context);
            Objects.requireNonNull(python);
            Objects.requireNonNull(outputRoot);
            timeout = timeout == null ? DEFAULT_TIMEOUT : timeout;
        }
    }

    /**
     * O que a interface ve da execucao ativa ou da ultima. {@code recordSaved} falso: o estado mostrado ainda NAO esta no
     * {@code estado.json} (a gravacao e tentada de novo e a tela avisa).
     */
    public record Snapshot(ExecutionRecord record, long elapsedMillis, boolean running, boolean recordSaved) {
        public Snapshot(ExecutionRecord record, long elapsedMillis, boolean running) {
            this(record, elapsedMillis, running, true);
        }

        /** A quem a execucao pertence (o contexto do inicio). */
        public RunContext context() {
            return record.context();
        }
    }

    public interface Listener {
        void changed(Snapshot s);
    }

    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(60);
    /** Por quanto tempo se insiste em gravar o estado FINAL antes de sinalizar a perda (depois, so em nova ocasiao). */
    public static final Duration FINAL_SAVE_GRACE = Duration.ofSeconds(10);
    /** Quanto {@link #close} espera a execucao ativa terminar depois de pedir o cancelamento. */
    public static final Duration CLOSE_WAIT = Duration.ofSeconds(15);
    /** Arquivo da pasta da execucao com o caminho da saida (local; fora do registro). */
    static final String OUTPUT_REF = "saida.ref";
    /**
     * Posse do processo lancado (PID + inicio + resumo do executavel), gravada num arquivo NOVO logo depois do lancamento
     * e antes de gravar RUNNING. Se a gravacao de RUNNING falhar e o aplicativo cair, a reconciliacao ainda encontra o
     * processo.
     */
    static final String PROCESS_REF = "processo.json";
    /** Salto do relogio de parede entre duas verificacoes acima disto = a maquina parou (suspensao ou hibernacao). */
    static final Duration SUSPENSION_GAP = Duration.ofSeconds(20);

    private final RunStore store;
    /** O armazem e a raiz das execucoes gerenciadas (nulos = este controlador so executa o que a pessoa aprova aqui). */
    private volatile RunStore managedStore;
    private volatile Path managedRoot;
    /** As execucoes gerenciadas iniciadas nesta abertura (antes de a pasta delas existir no armazem). */
    private final java.util.Set<ExecutionId> managedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final ApprovalLedger approvals;
    private final SessionProbe session;
    private final Clock clock;
    private final Path dataDir;
    private final Duration tick;
    private final Duration sessionEvery;
    private final Duration finalGrace;
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Object lock = new Object();
    private Active active;
    private volatile ExecutionRecord last;
    /** Estados de execucoes JA terminadas (ou reconciliadas) que ainda nao chegaram ao disco; tentados de novo. */
    private final Map<ExecutionId, ExecutionRecord> unsaved = new java.util.concurrent.ConcurrentHashMap<>();

    /** Execucao ativa (so uma). */
    private final class Active {
        volatile ExecutionRecord record;
        final Process process; // null quando adotada depois de reinicio (sem fluxo nem codigo de saida)
        final Path output;
        final long startNanos;
        final Duration timeout;
        final AtomicBoolean cancel = new AtomicBoolean();
        /** O pedido de cancelamento (so o primeiro vale); a linha de acompanhamento o aplica ao registro (escritor unico). */
        volatile ExecutionRecord.Cancellation cancelRequest;
        final AtomicLong bytes = new AtomicLong();
        final List<Thread> drains = new ArrayList<>();
        /** Gravacoes do {@code record} que falharam desde a ultima que deu certo (0 = o disco tem o {@code record}). */
        volatile int saveFailures;

        Active(ExecutionRecord r, Process p, Path output, Duration timeout) {
            this.record = r;
            this.process = p;
            this.output = output;
            this.timeout = timeout;
            this.startNanos = System.nanoTime();
        }
    }

    /**
     * @param dataDir a pasta de dados do aplicativo: a sonda do Python e gravada em {@code <dataDir>/sonda} e a pasta de
     *     saida nunca pode ficar dentro dela (nem conte-la)
     */
    public RunController(RunStore store, ApprovalLedger approvals, SessionProbe session, Clock clock, Path dataDir) {
        this(store, approvals, session, clock, dataDir, Duration.ofMillis(500), Duration.ofSeconds(5));
    }

    RunController(RunStore store, ApprovalLedger approvals, SessionProbe session, Clock clock, Path dataDir, Duration tick, Duration sessionEvery) {
        this(store, approvals, session, clock, dataDir, tick, sessionEvery, FINAL_SAVE_GRACE);
    }

    RunController(RunStore store, ApprovalLedger approvals, SessionProbe session, Clock clock, Path dataDir, Duration tick, Duration sessionEvery,
            Duration finalGrace) {
        this.store = store;
        this.approvals = approvals;
        this.session = session;
        this.clock = clock;
        this.dataDir = dataDir;
        this.tick = tick;
        this.sessionEvery = sessionEvery;
        this.finalGrace = finalGrace;
    }

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public ApprovalLedger approvals() {
        return approvals;
    }

    public SessionProbe session() {
        return session;
    }

    public RunStore store() {
        return store;
    }

    /**
     * Liga as execucoes GERENCIADAS: as que nao nascem de uma aprovacao da pessoa neste aplicativo. Quem as pede responde pela
     * autorizacao e entrega o pacote ja conferido e a pasta de saida, as duas dentro de {@code root}. Elas usam o MESMO
     * controlador (uma execucao por vez no computador, qualquer que seja o tipo) e um armazem PROPRIO: nunca entram no
     * historico das execucoes locais, e as aprovacoes locais nao valem para elas nem sao consultadas.
     *
     * @param managed armazem criado por {@link RunStore#managed}
     * @param root a pasta, dentro da pasta de dados, onde ficam o pacote e a saida dessas execucoes
     */
    public void enableManaged(RunStore managed, Path root) {
        if (!ExecutionRecord.MANAGED_ORIGIN.equals(managed.origin())) throw new IllegalArgumentException("armazem de outra origem");
        this.managedRoot = root.toAbsolutePath().normalize();
        this.managedStore = managed;
    }

    /** {@code true} se a execucao e gerenciada (esta no armazem proprio, ou foi iniciada assim nesta abertura). */
    public boolean managed(ExecutionId id) {
        RunStore m = managedStore;
        return m != null && (managedIds.contains(id) || Files.isDirectory(m.dir(id), java.nio.file.LinkOption.NOFOLLOW_LINKS));
    }

    /** O registro gravado de uma execucao (local ou gerenciada), se existe. */
    public Optional<ExecutionRecord> record(ExecutionId id) {
        ExecutionRecord pending = unsaved.get(id);
        return pending != null ? Optional.of(pending) : storeOf(id).record(id);
    }

    /**
     * Apaga a copia EXECUTADA do pacote de uma execucao gerenciada ja terminada (o registro e os eventos ficam). Nao toca em
     * execucao local, nem em execucao ainda em andamento.
     */
    public void discardManagedCopy(ExecutionId id) {
        if (!managed(id) || isActive(id)) return;
        Optional<ExecutionRecord> r = record(id);
        if (r.isEmpty() || !r.get().state().terminal()) return;
        RunStore.deleteTree(managedStore.dir(id).resolve("pacote"));
    }

    private RunStore storeOf(ExecutionId id) {
        return managed(id) ? managedStore : store;
    }

    private List<ExecutionRecord> allRecords() {
        List<ExecutionRecord> all = new java.util.ArrayList<>(store.all());
        RunStore m = managedStore;
        if (m != null) all.addAll(m.all());
        return all;
    }

    private void pruneAll() {
        store.prune();
        RunStore m = managedStore;
        if (m != null) m.prune();
    }

    /** A pasta em que a sonda do Python e gravada (para conferir o Python antes do inicio, com {@link PythonRuntime#probe}). */
    public Path probeDir() {
        return dataDir.resolve("sonda");
    }

    public boolean isRunning() {
        synchronized (lock) {
            return active != null;
        }
    }

    /**
     * A execucao ativa, se houver, com o contexto dela ({@link Snapshot#context}). E o que a interface consulta antes de
     * sair, de trocar de organizacao ou ambiente e de encerrar a sessao da pessoa.
     */
    public Optional<Snapshot> running() {
        synchronized (lock) {
            return active == null ? Optional.empty() : Optional.of(new Snapshot(active.record, elapsed(active), true, active.saveFailures == 0));
        }
    }

    /**
     * A pasta de onde o processo desta execucao roda: a copia conferida do pacote, criada no inicio ({@link Launch#snapshot}).
     * Vazio se ela nao existe (execucao recusada antes da copia, ou registro ja removido pela retencao).
     */
    public Optional<Path> runCopy(ExecutionId id) {
        try {
            Path copy = storeOf(id).dir(id).resolve("pacote");
            return Files.isDirectory(copy) ? Optional.of(copy) : Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A execucao ativa, ou a ultima terminada desde que o aplicativo abriu. */
    public Optional<Snapshot> current() {
        Optional<Snapshot> now = running();
        if (now.isPresent()) return now;
        ExecutionRecord l = last;
        return l == null ? Optional.empty() : Optional.of(endSnapshot(l));
    }

    private Snapshot endSnapshot(ExecutionRecord l) {
        return new Snapshot(l, l.started() == null || l.ended() == null ? 0 : Duration.between(l.started(), l.ended()).toMillis(), false,
                !unsaved.containsKey(l.id()));
    }

    // ------------------------------------------------------------------------------------------- inicio

    /**
     * Confere tudo e lanca (fora da linha de eventos). Recusa sem lancar: {@link ExecRefused} (o registro fica como
     * {@code BLOCKED}, exceto quando outra execucao ja esta ativa: ai nenhum registro novo e criado).
     */
    public Snapshot start(Request req) throws ExecRefused {
        return launch(req, false, null);
    }

    /**
     * Confere tudo e lanca uma execucao GERENCIADA (ver {@link #enableManaged}). Difere de {@link #start} em tres pontos, e so
     * neles: (1) a aprovacao local NAO e consultada - quem pede responde pela autorizacao; (2) a pasta de saida TEM de estar
     * dentro da raiz gerenciada (e fora do pacote); (3) o registro vai para o armazem proprio. Todo o resto e igual: pacote
     * reconferido, impressao igual a pedida, sessao utilizavel, Python compativel com as dependencias do pacote, copia
     * executada, posse do processo, prazo, cancelamento e reconciliacao. Com outra execucao ativa (de qualquer tipo): recusa,
     * sem registro.
     */
    public Snapshot startManaged(Request req) throws ExecRefused {
        return startManaged(req, null);
    }

    /**
     * O mesmo, entregando a saida do processo, linha a linha, a {@code sink} (nulo: a saida e lida e desprezada). So a
     * execucao gerenciada tem quem receba a saida: a que a pessoa inicia aqui continua sem entregar linha a ninguem.
     */
    public Snapshot startManaged(Request req, OutputLines.Sink sink) throws ExecRefused {
        if (managedStore == null) throw new ExecRefused(ErrorCode.EXEC_STORAGE_FAILED);
        return launch(req, true, sink);
    }

    private Snapshot launch(Request req, boolean managed, OutputLines.Sink sink) throws ExecRefused {
        synchronized (lock) {
            if (active != null) throw new ExecRefused(ErrorCode.EXEC_ALREADY_RUNNING);
            flushUnsaved();
            ExecutionId id = ExecutionId.random();
            if (managed) managedIds.add(id);
            // o contexto entra no registro AGORA e nao muda mais
            ExecutionRecord r = ExecutionRecord.ready(id, req.context(), clock.instant());
            ExecutionPackage pkg;
            try {
                pkg = ExecutionPackage.inspect(req.packageDir());
            } catch (ExecRefused e) {
                throw blocked(r, e.code());
            }
            // a impressao digital conferida AGORA precisa ser a que a pessoa escolheu e aprovou
            if (!pkg.fingerprint().equals(req.context().fingerprint())) throw blocked(r, ErrorCode.EXEC_PACKAGE_CHANGED);
            // a aprovacao local so vale (e so e consultada) para a execucao que a pessoa inicia aqui
            if (!managed && !approvals.approved(pkg)) throw blocked(r, ErrorCode.EXEC_NOT_APPROVED);
            SessionProbe.Session s = session.probe();
            if (!s.usable()) throw blocked(r, s.problem());
            PythonRuntime.Probe probe;
            try {
                probe = PythonRuntime.probe(req.python(), probeDir());
                PythonRuntime.requireCompatible(probe, pkg);
                PythonRuntime.requireDependencies(probe.python(), pkg);
            } catch (ExecRefused e) {
                throw blocked(r, e.code());
            }
            Path outRoot = req.outputRoot().toAbsolutePath().normalize();
            boolean outputOk = managed
                    ? Files.isDirectory(outRoot) && !inside(outRoot, pkg.dir()) && !outRoot.equals(managedRoot) && inside(outRoot, managedRoot)
                    : Files.isDirectory(outRoot) && !inside(outRoot, pkg.dir()) && !inside(outRoot, dataDir) && !inside(dataDir, outRoot);
            if (!outputOk) {
                throw blocked(r, ErrorCode.EXEC_OUTPUT_INVALID);
            }
            // a partir daqui: STARTING (registro gravado antes de lancar: uma queda deixa rastro)
            r = r.moveTo(ExecutionState.STARTING, null, clock.instant());
            storeOf(r.id()).save(r);
            storeOf(id).event(id, clock.instant(), "STARTING", Map.of("kind", pkg.kind().name(), "session", s.id()));
            Path copy;
            Path output = outRoot.resolve(id.value());
            try {
                copy = Launch.snapshot(pkg, storeOf(id).dir(id));
                Files.createDirectories(output);
                // so para reconciliar depois de um reinicio; nunca vai ao registro
                Files.writeString(storeOf(id).dir(id).resolve(OUTPUT_REF), output.toString(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (ExecRefused e) {
                throw finish(r, ExecutionState.BLOCKED, e.code());
            } catch (IOException e) {
                throw finish(r, ExecutionState.BLOCKED, ErrorCode.EXEC_OUTPUT_INVALID);
            }
            // com quem receba a saida, o Python a entrega linha a linha (so a execucao gerenciada tem quem receba)
            Launch.Spec spec = Launch.spec(pkg, probe.python(), copy, sink != null);
            Process p;
            try {
                ProcessBuilder pb = new ProcessBuilder(spec.command()).directory(spec.workDir().toFile());
                PythonRuntime.minimalEnvironment(pb.environment());
                pb.environment().put(ExternalSignal.OUTPUT_ENV, output.toString());
                pb.environment().put(ExternalSignal.SIGNAL_ENV, output.resolve(ExternalSignal.SIGNAL_FILE).toString());
                p = pb.start();
                p.getOutputStream().close();
            } catch (IOException | RuntimeException e) {
                throw finish(r, ExecutionState.FAILED, ErrorCode.EXEC_LAUNCH_FAILED);
            }
            // daqui em diante o processo EXISTE. Nada pode escapar de start() sem que ele tenha dono: uma falha ao gravar
            // RUNNING nao encerra nem relanca nada: o acompanhamento comeca igual e a gravacao e tentada de novo
            ProcessOwner owner = ProcessOwner.of(p, spec.executable().toString());
            writeProcessRef(id, owner);
            r = r.withOwner(owner).moveTo(ExecutionState.RUNNING, null, clock.instant());
            Active a = new Active(r, p, output, req.timeout());
            storeOf(id).event(id, clock.instant(), "RUNNING", Map.of("pid", owner.pid()));
            persist(a);
            drain(a, p.getInputStream(), "saida", false, sink);
            drain(a, p.getErrorStream(), "erros", true, sink);
            active = a;
            Thread t = new Thread(() -> watch(a), "nekkora-client-execucao");
            t.setDaemon(true);
            t.start();
            Snapshot snap = new Snapshot(r, 0, true, a.saveFailures == 0);
            notifyListeners(snap);
            return snap;
        }
    }

    /** Resposta a um pedido de cancelamento por identificador. Aceitar o pedido nao e confirmar o cancelamento. */
    public enum CancelResult {
        /** Pedido registrado agora; a confirmacao ({@code CANCELLED}) ou nao ({@code NOT_EFFECTIVE}) vem pelo registro. */
        REQUESTED,
        /** Idempotente: ja havia um pedido para esta execucao; nada muda. */
        ALREADY_REQUESTED,
        /** A execucao ja terminou; o estado final e o do registro. */
        ALREADY_ENDED,
        /** A execucao existe mas nao esta em andamento agora (registro aberto a espera de reconciliacao). */
        NOT_ACTIVE,
        /** Nao ha execucao com este identificador no historico (nunca existiu ou foi removida pela retencao). */
        UNKNOWN_EXECUTION;

        public boolean accepted() {
            return this == REQUESTED || this == ALREADY_REQUESTED;
        }
    }

    /** Pede o cancelamento da execucao ativa, pela janela (a arvore possuida e encerrada). Falso se nao ha execucao ativa. */
    public boolean cancel() {
        ExecutionId id;
        synchronized (lock) {
            if (active == null) return false;
            id = active.record.id();
        }
        return cancel(id, ExecutionRecord.CancelSource.LOCAL_UI).accepted();
    }

    /**
     * Pede o cancelamento DESTA execucao. Nao bloqueia. So a execucao ativa pode ser cancelada; um segundo pedido e
     * idempotente (o primeiro instante e a primeira origem ficam). O pedido entra no registro como {@code PENDING}; a
     * confirmacao ({@code CONFIRMED}, estado {@code CANCELLED}) vem depois de encerrada a arvore possuida, ou
     * {@code NOT_EFFECTIVE} se o processo terminou antes por conta propria (o estado e entao o do desfecho).
     */
    public CancelResult cancel(ExecutionId id, ExecutionRecord.CancelSource source) {
        synchronized (lock) {
            if (active != null && active.record.id().equals(id)) {
                if (active.cancelRequest != null) return CancelResult.ALREADY_REQUESTED;
                active.cancelRequest = new ExecutionRecord.Cancellation(clock.instant(), source, ExecutionRecord.CancelOutcome.PENDING);
                active.cancel.set(true);
                return CancelResult.REQUESTED;
            }
        }
        Optional<ExecutionRecord> r = storeOf(id).record(id);
        if (r.isEmpty()) return CancelResult.UNKNOWN_EXECUTION;
        return r.get().state().terminal() ? CancelResult.ALREADY_ENDED : CancelResult.NOT_ACTIVE;
    }

    private ExecRefused blocked(ExecutionRecord r, ErrorCode why) {
        ExecutionRecord b = r.moveTo(ExecutionState.BLOCKED, why, clock.instant());
        try {
            storeOf(b.id()).save(b);
            storeOf(b.id()).event(b.id(), clock.instant(), "BLOCKED", Map.of("code", why.code()));
        } catch (ExecRefused ignored) {
            // a recusa vale mesmo sem registro em disco
        }
        last = b;
        notifyListeners(new Snapshot(b, 0, false));
        return new ExecRefused(why);
    }

    private ExecRefused finish(ExecutionRecord r, ExecutionState to, ErrorCode why) {
        ExecutionRecord f = r.moveTo(to, why, clock.instant());
        try {
            storeOf(f.id()).save(f);
            storeOf(f.id()).event(f.id(), clock.instant(), to.name(), Map.of("code", why.code()));
        } catch (ExecRefused ignored) {
            // idem
        }
        last = f;
        notifyListeners(new Snapshot(f, 0, false));
        return new ExecRefused(why);
    }

    /**
     * Le o fluxo ate o fim: o processo nunca trava por saida cheia. Sem {@code sink}, so conta os bytes (nada e guardado); com
     * ele, cada linha e entregue a quem pediu a execucao gerenciada.
     */
    private void drain(Active a, InputStream in, String name, boolean error, OutputLines.Sink sink) {
        Thread t = new Thread(() -> OutputLines.pump(in, error, sink, a.bytes, clock), "nekkora-client-" + name);
        t.setDaemon(true);
        t.start();
        a.drains.add(t);
    }

    // ------------------------------------------------------------------------------------------- acompanhamento

    private void watch(Active a) {
        long lastWall = clock.millis();
        long lastSession = System.nanoTime();
        boolean locked = false;
        ExecutionState forced = null;
        ErrorCode forcedCode = null;
        while (true) {
            boolean done = a.process != null ? waitFor(a.process, tick) : !aliveAdopted(a) || pause(tick);
            long wall = clock.millis();
            if (wall - lastWall > SUSPENSION_GAP.toMillis() + tick.toMillis()) {
                a.record = a.record.withSuspension();
                storeOf(a.record.id()).event(a.record.id(), clock.instant(), "SUSPENSION_DETECTED", Map.of("gap_ms", wall - lastWall));
                persist(a);
            }
            lastWall = wall;
            if (a.saveFailures > 0 && !done) persist(a); // o disco ficou para tras; nova tentativa a cada volta
            if (done) break;
            if (System.nanoTime() - lastSession >= sessionEvery.toNanos()) {
                lastSession = System.nanoTime();
                boolean now = session.probe().locked();
                if (now != locked) {
                    locked = now;
                    if (now) a.record = a.record.withLock();
                    storeOf(a.record.id()).event(a.record.id(), clock.instant(), now ? "SESSION_LOCKED" : "SESSION_UNLOCKED", Map.of());
                    persist(a);
                }
            }
            if (a.cancel.get()) {
                // o pedido fica gravado (PENDING) ANTES de encerrar a arvore; a confirmacao so vem depois do encerramento
                applyCancelRequest(a);
                forced = ExecutionState.CANCELLED;
                forcedCode = ErrorCode.EXEC_CANCELLED;
                break;
            }
            if (elapsed(a) > a.timeout.toMillis()) {
                forced = ExecutionState.TIMED_OUT;
                forcedCode = ErrorCode.EXEC_TIMEOUT;
                break;
            }
            notifyListeners(new Snapshot(a.record, elapsed(a), true, a.saveFailures == 0));
        }
        if (forced != null) {
            int killed = a.record.owner() == null ? 0 : a.record.owner().terminate(Duration.ofSeconds(10));
            if (a.process != null) a.process.destroyForcibly();
            storeOf(a.record.id()).event(a.record.id(), clock.instant(), "TERMINATED", Map.of("processes", killed));
        }
        for (Thread t : a.drains) {
            try {
                t.join(3000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Integer exit = null;
        if (a.process != null) {
            waitFor(a.process, Duration.ofSeconds(5));
            exit = a.process.isAlive() ? null : a.process.exitValue();
        }
        ExternalSignal.Signal signal = Launch.reported(a.output);
        ExecutionState state;
        ErrorCode code;
        if (forced != null) {
            state = forced;
            code = forcedCode;
        } else if (a.process == null) {
            // adotada depois de reinicio: sem codigo de saida, o fim nao foi observado; nada e presumido
            state = ExecutionState.OUTCOME_UNKNOWN;
            code = ErrorCode.EXEC_AGENT_RESTARTED;
        } else {
            Launch.Outcome o = Launch.classify(signal, exit == null ? -1 : exit);
            state = o.state();
            code = o.code();
            // suspensao no meio e nenhum sinal do script: nao se presume nada
            if (a.record.suspensions() > 0 && signal == ExternalSignal.Signal.NONE) {
                state = ExecutionState.OUTCOME_UNKNOWN;
                code = ErrorCode.EXEC_SUSPENDED;
            }
        }
        // o desfecho ja esta decidido (sinal, codigo de saida, prazo, cancelamento): a gravacao so o registra, nunca o muda
        ExecutionRecord end = a.record.withExit(exit, a.bytes.get()).moveTo(state, code, clock.instant());
        if (end.cancellation() == null && a.cancelRequest != null) end = end.withCancellation(a.cancelRequest); // pedido chegou na ultima volta
        if (end.cancellation() != null) {
            // pedido e confirmacao separados: CANCELLED = a arvore foi encerrada aqui; senao o processo terminou antes
            ExecutionRecord.CancelOutcome o = state == ExecutionState.CANCELLED ? ExecutionRecord.CancelOutcome.CONFIRMED
                    : ExecutionRecord.CancelOutcome.NOT_EFFECTIVE;
            end = end.withCancellation(end.cancellation().resolved(o));
            storeOf(end.id()).event(end.id(), clock.instant(), "CANCEL_" + o.name(), Map.of("source", end.cancellation().source().name()));
        }
        a.record = end;
        persist(a);
        boolean saved = a.saveFailures == 0;
        if (!saved) unsaved.put(end.id(), end);
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("exit", exit == null ? "none" : exit);
        f.put("signal", signal.name());
        f.put("output_bytes", a.bytes.get());
        if (code != null) f.put("code", code.code());
        storeOf(end.id()).event(end.id(), clock.instant(), state.name(), f);
        synchronized (lock) {
            active = null;
            last = end;
        }
        pruneAll();
        notifyListeners(endSnapshot(end));
        if (!saved) insistOnFinal(end);
    }

    /**
     * O estado FINAL nao foi gravado. O processo ja acabou (a vaga de execucao foi liberada); insiste-se por
     * {@link #finalGrace}. Sem sucesso, a perda e SINALIZADA (evento e tela) e o estado continua guardado em memoria para a
     * proxima ocasiao (novo inicio, encerramento do aplicativo). Reinicio antes disso: o disco tem um estado nao terminal,
     * que a reconciliacao fecha como {@code OUTCOME_UNKNOWN}: nada e presumido nem relancado.
     */
    private void insistOnFinal(ExecutionRecord end) {
        long deadline = System.nanoTime() + finalGrace.toNanos();
        while (System.nanoTime() < deadline) {
            pause(tick);
            if (saveUnsaved(end.id())) return;
        }
        if (!unsaved.containsKey(end.id())) return;
        storeOf(end.id()).event(end.id(), clock.instant(), "RECORD_SAVE_FAILED", Map.of("state", end.state().name(), "code", ErrorCode.EXEC_STORAGE_FAILED.code()));
        notifyListeners(endSnapshot(end));
    }

    /**
     * Grava o estado em memoria da execucao ativa. Nunca lanca: uma falha so deixa o disco PARA TRAS (marcado em
     * {@link Active#saveFailures} e mostrado na tela); o processo, o acompanhamento e o desfecho nao mudam. A primeira
     * falha e a recuperacao viram evento (uma linha por mudanca, nao por tentativa).
     */
    private void persist(Active a) {
        ExecutionRecord r = a.record;
        try {
            storeOf(r.id()).save(r);
        } catch (ExecRefused e) {
            if (a.saveFailures++ == 0) {
                storeOf(r.id()).event(r.id(), clock.instant(), "RECORD_SAVE_DELAYED", Map.of("state", r.state().name(), "code", e.code().code()));
            }
            return;
        }
        if (a.saveFailures > 0) {
            a.saveFailures = 0;
            storeOf(r.id()).event(r.id(), clock.instant(), "RECORD_SAVE_RECOVERED", Map.of("state", r.state().name()));
        }
    }

    /** Grava um estado de execucao que nao esta ativa (reconciliacao); na falha, guarda para nova tentativa. */
    private void saveOrKeep(ExecutionRecord r) {
        try {
            storeOf(r.id()).save(r);
        } catch (ExecRefused e) {
            unsaved.put(r.id(), r);
            storeOf(r.id()).event(r.id(), clock.instant(), "RECORD_SAVE_DELAYED", Map.of("state", r.state().name(), "code", e.code().code()));
        }
    }

    /** Uma nova tentativa para um estado guardado; verdadeiro quando nada mais falta gravar para essa execucao. */
    private boolean saveUnsaved(ExecutionId id) {
        ExecutionRecord r = unsaved.get(id);
        if (r == null) return true;
        try {
            storeOf(r.id()).save(r);
        } catch (ExecRefused e) {
            return false;
        }
        if (unsaved.remove(id, r)) {
            storeOf(id).event(id, clock.instant(), "RECORD_SAVE_RECOVERED", Map.of("state", r.state().name()));
            ExecutionRecord l = last;
            if (l != null && l.id().equals(id)) notifyListeners(endSnapshot(l));
        }
        return true;
    }

    private void flushUnsaved() {
        for (ExecutionId id : List.copyOf(unsaved.keySet())) saveUnsaved(id);
    }

    /** Posse do processo num arquivo novo, sincronizado no disco; falha aqui so reduz o rastro (o evento RUNNING tem o PID). */
    private void writeProcessRef(ExecutionId id, ProcessOwner o) {
        String json = "{\"pid\": " + o.pid() + ", \"start\": " + o.startMillis() + ", \"executableDigest\": " + StrictJson.quote(o.executableDigest()) + "}\n";
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(storeOf(id).dir(id).resolve(PROCESS_REF), java.nio.file.StandardOpenOption.CREATE_NEW,
                java.nio.file.StandardOpenOption.WRITE)) {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            while (buf.hasRemaining()) ch.write(buf);
            ch.force(true);
        } catch (IOException | RuntimeException e) {
            // segue: o processo continua acompanhado enquanto o aplicativo estiver aberto
        }
    }

    /** A posse gravada no lancamento, so se o arquivo for pequeno, completo e com os tres campos validos. */
    private Optional<ProcessOwner> processRef(ExecutionId id) {
        try {
            Path f = storeOf(id).dir(id).resolve(PROCESS_REF);
            if (!Files.isRegularFile(f, java.nio.file.LinkOption.NOFOLLOW_LINKS) || Files.size(f) > 1024) return Optional.empty();
            Map<String, Object> m = JsonReader.object(Files.readString(f, java.nio.charset.StandardCharsets.UTF_8));
            if (m.size() == 3 && m.get("pid") instanceof Long pid && pid > 0 && m.get("start") instanceof Long start && start >= 0
                    && m.get("executableDigest") instanceof String exe && exe.matches("sha256:[0-9a-f]{64}")) {
                return Optional.of(new ProcessOwner(pid, start, exe));
            }
        } catch (IOException | JsonReader.Invalid | RuntimeException e) {
            // ilegivel ou pela metade: sem prova de posse
        }
        return Optional.empty();
    }

    /** Espera um intervalo (execucao adotada: nao ha {@code Process} para esperar); devolve sempre falso. */
    private static boolean pause(Duration d) {
        try {
            Thread.sleep(d.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return false;
    }

    private boolean aliveAdopted(Active a) {
        return a.record.owner() != null && a.record.owner().alive();
    }

    private static boolean waitFor(Process p, Duration d) {
        try {
            return p.waitFor(d.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return !p.isAlive();
        }
    }

    private long elapsed(Active a) {
        return (System.nanoTime() - a.startNanos) / 1_000_000;
    }

    private void notifyListeners(Snapshot s) {
        for (Listener l : listeners) {
            try {
                l.changed(s);
            } catch (RuntimeException e) {
                // a interface nao derruba o acompanhamento
            }
        }
    }

    // ------------------------------------------------------------------------------------------- reinicio

    /**
     * Depois de um reinicio do aplicativo (chamar UMA vez, na abertura): cada registro nao terminado e fechado SEM
     * inventar resultado e SEM relancar nada. O processo que ainda e o mesmo (PID + inicio + executavel) e ADOTADO: a vaga
     * unica continua ocupada, {@link #running} o mostra com o contexto original e o cancelamento funciona; como o codigo de
     * saida nao e mais observavel, o fim dele e {@code OUTCOME_UNKNOWN} ({@code AGT-EXE-025}). Devolve quantos registros
     * foram tocados.
     *
     * <p>Um registro em {@code STARTING} com a posse gravada no lancamento ({@link #PROCESS_REF}) e de um processo que FOI
     * lancado (a gravacao de RUNNING nao chegou ao disco): e tratado como RUNNING com essa posse.
     */
    public int reconcile() {
        flushUnsaved();
        int n = 0;
        for (ExecutionRecord r : allRecords()) {
            if (r.state().terminal() || unsaved.containsKey(r.id()) || isActive(r.id())) continue;
            n++;
            storeOf(r.id()).event(r.id(), clock.instant(), "RECONCILE", Map.of("state", r.state().name()));
            if (r.state() == ExecutionState.STARTING) {
                Optional<ProcessOwner> launched = processRef(r.id());
                if (launched.isPresent()) {
                    ProcessOwner o = launched.get();
                    r = r.withOwner(o).moveTo(ExecutionState.RUNNING, null, o.startMillis() > 0 ? Instant.ofEpochMilli(o.startMillis()) : clock.instant());
                }
            }
            if (r.state() == ExecutionState.RUNNING && r.owner() != null && r.owner().alive()) {
                synchronized (lock) {
                    if (active == null) {
                        ExecutionRecord adopted = r.adoptedAfterRestart();
                        Active a = new Active(adopted, null, outputOf(r), DEFAULT_TIMEOUT);
                        persist(a);
                        active = a;
                        Thread t = new Thread(() -> watch(a), "nekkora-client-execucao");
                        t.setDaemon(true);
                        t.start();
                        storeOf(r.id()).event(r.id(), clock.instant(), "ADOPTED", Map.of("pid", r.owner().pid()));
                        continue;
                    }
                }
            }
            ExecutionState to = r.state() == ExecutionState.READY ? ExecutionState.BLOCKED : ExecutionState.OUTCOME_UNKNOWN;
            ExecutionRecord closed = r.moveTo(to, ErrorCode.EXEC_AGENT_RESTARTED, clock.instant());
            saveOrKeep(closed);
            storeOf(r.id()).event(r.id(), clock.instant(), to.name(), Map.of("code", ErrorCode.EXEC_AGENT_RESTARTED.code()));
            last = closed;
        }
        return n;
    }

    private boolean isActive(ExecutionId id) {
        synchronized (lock) {
            return active != null && active.record.id().equals(id);
        }
    }

    /** A pasta de saida anotada no inicio. */
    private Path outputOf(ExecutionRecord r) {
        return storeOf(r.id()).output(r.id()).orElseGet(() -> storeOf(r.id()).dir(r.id()).resolve("sem-saida"));
    }

    /** Grava o pedido de cancelamento como PENDING (uma vez). */
    private void applyCancelRequest(Active a) {
        ExecutionRecord.Cancellation c = a.cancelRequest;
        if (c == null || a.record.cancellation() != null) return;
        a.record = a.record.withCancellation(c);
        storeOf(a.record.id()).event(a.record.id(), clock.instant(), "CANCEL_REQUESTED", Map.of("source", c.source().name()));
        persist(a);
        notifyListeners(new Snapshot(a.record, elapsed(a), true, a.saveFailures == 0));
    }

    static boolean inside(Path child, Path parent) {
        try {
            Path c = child.toAbsolutePath().normalize();
            Path p = parent.toAbsolutePath().normalize();
            return c.startsWith(p);
        } catch (RuntimeException e) {
            return true;
        }
    }

    /**
     * Encerramento do aplicativo: a execucao ativa e cancelada com a origem {@code APPLICATION_SHUTDOWN} (a arvore possuida
     * e encerrada) e espera-se ate {@link #CLOSE_WAIT} (15 s) o registro ser fechado; estados ainda nao gravados tem uma
     * ultima tentativa. Bloqueia: chamar fora da linha de eventos. Sem execucao ativa, volta logo.
     */
    @Override
    public void close() {
        Active a;
        synchronized (lock) {
            a = active;
        }
        if (a != null) {
            cancel(a.record.id(), ExecutionRecord.CancelSource.APPLICATION_SHUTDOWN);
            long deadline = System.nanoTime() + CLOSE_WAIT.toNanos();
            while (System.nanoTime() < deadline && isRunning()) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        flushUnsaved();
    }
}
