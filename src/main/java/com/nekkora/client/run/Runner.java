package com.nekkora.client.run;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;
import com.nekkora.client.pack.LocalPackages;
import com.nekkora.client.pack.PackageContext;

/**
 * A execucao local, como a tela a usa: recebe caminhos como texto, devolve resumos e, quando algo e recusado, UMA frase
 * em portugues ({@link Refused}). Por baixo, o executor de sempre ({@link RunController}).
 * <p>
 * E SOMENTE LOCAL. Este aplicativo nao tem vinculo de maquina com a Platform: nada daqui registra execucao la, envia
 * eventos ou registros de monitoramento, aceita gatilho remoto ou entra em fila de envio. O pacote {@code run} nao fala
 * com a rede. Cada execucao guarda, desde o inicio e sem poder mudar, o contexto (organizacao, ambiente, pacote) em que
 * nasceu; nada a reatribui depois.
 * <p>
 * Nada comeca sozinho: {@link #start} exige o pacote guardado e integro, a aprovacao explicita daquele conteudo exato,
 * um Python escolhido pela pessoa e compativel (as dependencias sao conferidas, NUNCA instaladas) e uma pasta de saida.
 * As chamadas bloqueiam (disco e processos): a tela as faz fora da fila de eventos.
 */
public final class Runner implements AutoCloseable {

    /** Recusa de uma etapa, com a frase para a pessoa (inclui o codigo de suporte). */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * O pacote conferido na area local: o que vai ser executado e se aquele conteudo ja foi aprovado. {@code packageDir}: a
     * pasta, gerenciada pelo aplicativo, em que o pacote conferido esta guardado - e a ORIGEM da execucao. O processo nao
     * roda nela: ao iniciar, o executor cria uma copia conferida propria daquela execucao ({@link Status#runDir()}), que
     * antes do inicio ainda nao existe. {@code declaredName}: o nome que o pacote declara.
     */
    public record Checked(String fingerprint, String shortFingerprint, String name, String packageVersion, String entry, String pythonRequirement,
                          List<String> dependencies, boolean approved, String packageDir, String declaredName) {
    }

    /** O interpretador conferido contra o pacote: versao encontrada; compativel e com as dependencias exatas instaladas. */
    public record Python(String path, String version) {
    }

    /**
     * Retrato de uma execucao (a ativa ou a ultima). {@code organizationId} e {@code workspaceId} sao os do INICIO.
     * {@code detail}: por que terminou assim, quando ha o que dizer. {@code outputFolder}: onde o script gravou.
     */
    public record Status(String id, UUID organizationId, UUID workspaceId, String fingerprint, String name, boolean running, boolean finished,
                         boolean succeeded, String state, String detail, long elapsedMillis, String outputFolder, boolean cancelRequested, String runDir) {
        /**
         * {@code runDir}: a pasta de onde o processo Python desta execucao roda (a copia conferida do pacote, criada no
         * inicio); vazio se a execucao foi recusada antes de a copia existir.
         */
        public Status {
            runDir = runDir == null ? "" : runDir;
        }
    }

    /**
     * Um ambiente Python que a pessoa pode escolher, com a situacao CONTRA o pacote conferido. {@code id}: identidade do
     * ambiente (preserva a escolha entre buscas). {@code folderName}: {@code .venv}/{@code venv} no ambiente do projeto.
     * {@code version}: vazio quando nao foi possivel verificar. {@code pending}: os requisitos declarados nao atendidos,
     * cada um com o que foi encontrado.
     */
    public record PythonOption(String id, PythonEnvironments.Kind kind, String folderName, String path, String version, PythonEnvironments.State state,
                               List<String> pending) {
        public boolean usable() {
            return state == PythonEnvironments.State.COMPATIBLE;
        }
    }

    /**
     * As instalacoes encontradas neste computador. {@code requirement}: a faixa de Python que o pacote declara.
     * {@code failedSources}: fontes que nao responderam (busca parcial). {@code unanswered}: candidatos que nao
     * responderam como Python e por isso nao sao opcao.
     */
    public record PythonList(List<PythonOption> options, String requirement, List<String> failedSources, int unanswered, boolean cancelled) {
    }

    /** Os ambientes da pasta indicada. {@code problems}: o que nao virou ambiente ({@code nome} + motivo). */
    public record ProjectPythons(String folder, List<PythonOption> options, List<PythonEnvironments.Problem> problems, boolean cancelled) {
    }

    /** Por que uma execucao gerenciada nao comecou, em termos que quem a pediu entende. */
    public enum ManagedRefusal {
        /** Ja ha uma execucao em andamento neste computador (de qualquer tipo). */
        BUSY,
        /** O ZIP nao confere (SHA-256 ou impressao), nao e um pacote valido, ou mudou. */
        PACKAGE_INVALID,
        /** O pacote e de um tipo que este aplicativo nao executa (so pacote Python externo). */
        PACKAGE_UNSUPPORTED,
        /** O Python indicado nao existe mais neste computador, ou nao responde. */
        ENVIRONMENT_GONE,
        /** O Python indicado nao atende a versao que o pacote exige. */
        ENVIRONMENT_INCOMPATIBLE,
        /** Falta, no Python indicado, alguma dependencia (do pacote ou das indicadas por quem pediu), na versao exata. */
        DEPENDENCIES_MISSING,
        /** Nao ha sessao interativa utilizavel (tela bloqueada, sessao de servico, sem area de trabalho). */
        SESSION_UNAVAILABLE,
        /** Este computador nao conseguiu preparar as pastas ou gravar o registro. */
        STORAGE
    }

    public static final class ManagedRefused extends Exception {
        private static final long serialVersionUID = 1L;
        private final ManagedRefusal why;

        public ManagedRefused(ManagedRefusal why) {
            super(why.name(), null, false, false);
            this.why = why;
        }

        public ManagedRefusal why() {
            return why;
        }
    }

    /**
     * O que uma execucao gerenciada e AGORA. {@code state}: RUNNING, SUCCEEDED, FAILED, CANCELLED, TIMED_OUT ou UNKNOWN (terminou
     * sem que o fim fosse observado, ou nao ha registro). {@code exitCode}: so quando o processo terminou e o codigo foi lido.
     */
    public record ManagedStatus(String id, String state, Integer exitCode) {
        public boolean running() {
            return "RUNNING".equals(state);
        }
    }

    /** O que quem pede uma execucao gerenciada entrega. {@code dir}: a pasta dela, de {@link #managedDir}. */
    public record ManagedJob(UUID organizationId, UUID workspaceId, UUID automationId, UUID automationVersionId, Path zip, String sha256, String fingerprint,
                             List<String> dependencies, String pythonId, Path dir) {

        /** O mesmo, com os caminhos em texto (para quem monta o aplicativo, que nao toca em disco). */
        public static ManagedJob of(UUID organizationId, UUID workspaceId, UUID automationId, UUID automationVersionId, String zip, String sha256,
                                    String fingerprint, List<String> dependencies, String pythonId, String dir) {
            return new ManagedJob(organizationId, workspaceId, automationId, automationVersionId, Path.of(zip), sha256, fingerprint, dependencies, pythonId,
                    Path.of(dir));
        }
    }

    private volatile Path managedRoot;
    private final LocalPackages packages;
    private final RunController controller;
    private final PythonEnvironments environments;
    private final java.util.Map<String, PythonEnvironments.Environment> known = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private volatile String lastOutputRoot;

    /**
     * @param dataRoot a pasta de dados do Client (nunca a do Nekkora Agent administrativo)
     * @param headless se nao ha area de trabalho (a sessao interativa e exigida para executar)
     */
    public Runner(Path dataRoot, LocalPackages packages, BooleanSupplier headless) {
        this(packages, new RunController(new RunStore(dataRoot.resolve("execucoes"), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(dataRoot), AtomicWriter.DEFAULT),
                SessionProbe.system(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win"), System.getenv(), headless),
                Clock.systemUTC(), dataRoot));
    }

    public Runner(LocalPackages packages, RunController controller) {
        this(packages, controller, new PythonEnvironments(PythonSources.system(controller.probeDir()), controller.probeDir()));
    }

    public Runner(LocalPackages packages, RunController controller, PythonEnvironments environments) {
        this.packages = packages;
        this.controller = controller;
        this.environments = environments;
        controller.addListener(s -> {
            // a tela da execucao local so acompanha o que a pessoa iniciou aqui
            if (controller.managed(s.record().id())) return;
            Status status = status(s);
            listeners.forEach(l -> l.accept(status));
        });
    }

    // ------------------------------------------------------------------------------- execucao gerenciada

    /**
     * Liga as execucoes gerenciadas (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03): as que nao nascem de uma aprovacao da pessoa
     * neste aplicativo. {@code root} e uma pasta dentro da pasta de dados; os registros ficam em {@code root/execucoes} e o
     * trabalho de cada uma em {@code root/tentativas/<id>}. Chamado uma vez, antes de {@link #reconcile}.
     */
    public void enableManaged(Path root) {
        Path base = root.toAbsolutePath().normalize();
        controller.enableManaged(RunStore.managed(base.resolve("execucoes"), AtomicWriter.DEFAULT), base.resolve("tentativas"));
        managedRoot = base.resolve("tentativas");
    }

    /** A pasta de trabalho de uma execucao gerenciada ({@code key}: um UUID de quem pede). Nao a cria. Vazio: fora do formato ou desligado. */
    public Optional<Path> managedDir(String key) {
        Path root = managedRoot;
        if (root == null || key == null || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) return Optional.empty();
        return Optional.of(root.resolve(key));
    }

    /** O caminho de {@link #managedDir}, em texto. */
    public Optional<String> managedFolder(String key) {
        return managedDir(key).map(Path::toString);
    }

    /** {@code true} se ha uma execucao em andamento neste computador, local ou gerenciada. */
    public boolean busy() {
        return controller.isRunning();
    }

    /**
     * Confere o pacote, o Python e as dependencias e INICIA uma execucao gerenciada. Em ordem: nada em andamento; ZIP
     * conferido (SHA-256 e impressao) e extraido em {@code dir/pacote}; o Python indicado ainda existe; as dependencias
     * indicadas por quem pede estao nele, na versao exata; e entao as travas do motor (pacote reconferido, sessao, versao do
     * Python e dependencias do pacote, saida em {@code dir/saida}). As aprovacoes locais nao sao consultadas nem criadas. Nada
     * e instalado. Devolve o identificador da execucao. Bloqueia (processos, com prazo): fora da linha de eventos.
     */
    public String startManaged(ManagedJob job) throws ManagedRefused {
        if (managedRoot == null || job.dir() == null || !job.dir().toAbsolutePath().normalize().startsWith(managedRoot)) {
            throw new ManagedRefused(ManagedRefusal.STORAGE);
        }
        if (controller.isRunning()) throw new ManagedRefused(ManagedRefusal.BUSY);
        ExecutionPackage pkg;
        try {
            pkg = com.nekkora.client.pack.DownloadedPackages.unpackVerified(job.zip(), job.sha256(), job.fingerprint(), job.dir().resolve("pacote"),
                    () -> false);
        } catch (com.nekkora.client.pack.ImportProblem e) {
            throw new ManagedRefused(e.detail().filter(com.nekkora.client.pack.ErrorCode.EXEC_PACKAGE_UNSUPPORTED.code()::equals).isPresent() ? ManagedRefusal.PACKAGE_UNSUPPORTED
                    : e.code() == com.nekkora.client.pack.ImportProblem.Code.DOWNLOAD_STORE_FAILED ? ManagedRefusal.STORAGE : ManagedRefusal.PACKAGE_INVALID);
        }
        Path python = installedPath(job.pythonId()).orElseThrow(() -> new ManagedRefused(ManagedRefusal.ENVIRONMENT_GONE));
        requireListed(python, job.dependencies());
        try {
            Path out = Files.createDirectories(job.dir().resolve("saida"));
            RunContext run = new RunContext(job.organizationId(), job.workspaceId(), pkg.fingerprint(), pkg.name(), job.automationId(),
                    job.automationVersionId());
            return controller.startManaged(new RunController.Request(pkg.dir(), run, python, out, null)).record().id().value();
        } catch (ExecRefused e) {
            throw new ManagedRefused(switch (e.code()) {
                case EXEC_ALREADY_RUNNING -> ManagedRefusal.BUSY;
                case EXEC_SESSION_UNAVAILABLE, EXEC_SESSION_LOCKED, EXEC_UNSUPPORTED_OS -> ManagedRefusal.SESSION_UNAVAILABLE;
                case EXEC_PYTHON_INVALID -> ManagedRefusal.ENVIRONMENT_GONE;
                case EXT_ENVIRONMENT_INCOMPATIBLE -> ManagedRefusal.ENVIRONMENT_INCOMPATIBLE;
                case EXEC_OUTPUT_INVALID, EXEC_STORAGE_FAILED, EXEC_LAUNCH_FAILED -> ManagedRefusal.STORAGE;
                default -> ManagedRefusal.PACKAGE_INVALID;
            });
        } catch (java.io.IOException | IllegalArgumentException e) {
            throw new ManagedRefused(ManagedRefusal.STORAGE);
        }
    }

    /** O estado de uma execucao gerenciada, pelo identificador de {@link #startManaged}. Vazio: nao e (ou nao e mais) conhecida. */
    public Optional<ManagedStatus> managedStatus(String id) {
        if (!ExecutionId.valid(id)) return Optional.empty();
        ExecutionId eid = new ExecutionId(id);
        if (!controller.managed(eid)) return Optional.empty();
        Optional<RunController.Snapshot> active = controller.running().filter(s -> s.record().id().equals(eid));
        if (active.isPresent()) return Optional.of(new ManagedStatus(id, "RUNNING", null));
        return controller.record(eid).map(r -> new ManagedStatus(id, switch (r.state()) {
            case READY, STARTING, RUNNING -> "RUNNING";
            case SUCCEEDED -> "SUCCEEDED";
            case FAILED -> "FAILED";
            case CANCELLED -> "CANCELLED";
            case TIMED_OUT -> "TIMED_OUT";
            case BLOCKED, OUTCOME_UNKNOWN -> "UNKNOWN";
        }, r.exitCode()));
    }

    /** Pede o cancelamento de uma execucao gerenciada em andamento. {@code false}: nao esta em andamento. */
    public boolean cancelManaged(String id) {
        if (!ExecutionId.valid(id)) return false;
        ExecutionId eid = new ExecutionId(id);
        if (!controller.managed(eid)) return false;
        RunController.CancelResult r = controller.cancel(eid, ExecutionRecord.CancelSource.MANAGED_REQUEST);
        return r == RunController.CancelResult.REQUESTED || r == RunController.CancelResult.ALREADY_REQUESTED;
    }

    /** Um arquivo que uma execucao gerenciada gravou na pasta de saida: o nome relativo a ela (com {@code /}), o caminho e o tamanho. */
    public record ManagedOutput(String name, String file, long size) {
    }

    /**
     * O que a execucao gerenciada gravou na pasta de saida DELA, em ordem estavel (pelo nome relativo). So arquivos comuns
     * dentro da pasta: ligacoes nao sao seguidas nem listadas, e nada fora da pasta e alcancado. O arquivo de sinal do
     * aplicativo fica de fora. No maximo 10.000 entradas sao percorridas.
     */
    public List<ManagedOutput> managedOutputs(String key, String id) {
        Path dir = managedDir(key).orElse(null);
        if (dir == null || !ExecutionId.valid(id)) return List.of();
        Path out = dir.resolve("saida").resolve(id);
        if (!Files.isDirectory(out, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<ManagedOutput> found = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> walk = Files.walk(out, 16)) {
            for (Path p : walk.limit(10_000).toList()) {
                if (!Files.isRegularFile(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                String name = out.relativize(p).toString().replace('\\', '/');
                if (name.equals(ExternalSignal.SIGNAL_FILE)) continue;
                found.add(new ManagedOutput(name, p.toString(), Files.size(p)));
            }
        } catch (java.io.IOException | RuntimeException e) {
            // o que ja foi lido vale; o resto fica de fora
        }
        found.sort(java.util.Comparator.comparing(ManagedOutput::name));
        return List.copyOf(found);
    }

    /** Apaga o CODIGO de uma execucao gerenciada (o ZIP e a copia extraida). O que a automacao gravou na saida fica. */
    public void discardManaged(String key) {
        managedDir(key).ifPresent(dir -> {
            try {
                Files.deleteIfExists(dir.resolve("pacote.zip"));
            } catch (java.io.IOException e) {
                // tentado de novo na proxima vez
            }
            RunStore.deleteTree(dir.resolve("pacote"));
        });
    }

    /** O executavel de uma instalacao de {@link #installedPythons}, pelo identificador dela, se ainda existe. */
    private static Optional<Path> installedPath(String id) {
        String prefix = "computador:";
        if (id == null || !id.startsWith(prefix) || id.length() == prefix.length()) return Optional.empty();
        try {
            Path p = Path.of(id.substring(prefix.length()));
            return p.isAbsolute() && Files.isRegularFile(p) ? Optional.of(p) : Optional.empty();
        } catch (java.nio.file.InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** As dependencias indicadas por quem pede ({@code nome==versao}) estao no Python, na versao exata. Nada e instalado. */
    private void requireListed(Path python, List<String> dependencies) throws ManagedRefused {
        java.util.Map<String, String> wanted = new java.util.LinkedHashMap<>();
        for (String d : dependencies == null ? List.<String>of() : dependencies) {
            if (!ExecutionPackage.validDependency(d)) throw new ManagedRefused(ManagedRefusal.DEPENDENCIES_MISSING);
            int at = d.indexOf("==");
            String name = d.substring(0, at);
            int extras = name.indexOf('[');
            wanted.put(ExecutionPackage.normalized(extras < 0 ? name : name.substring(0, extras)), d.substring(at + 2).strip());
        }
        if (wanted.isEmpty()) return;
        PythonProbe.Info info;
        try {
            info = PythonProbe.run(python, List.copyOf(wanted.keySet()), controller.probeDir(), PythonProbe.DEFAULT_TIMEOUT, PythonProbe.Cancel.NEVER);
        } catch (PythonProbe.Failed | RuntimeException e) {
            throw new ManagedRefused(ManagedRefusal.ENVIRONMENT_GONE);
        }
        if (info.installed() == null) throw new ManagedRefused(ManagedRefusal.DEPENDENCIES_MISSING);
        for (java.util.Map.Entry<String, String> w : wanted.entrySet()) {
            String found = info.installed().get(w.getKey());
            if (found == null || !found.strip().equals(w.getValue())) throw new ManagedRefused(ManagedRefusal.DEPENDENCIES_MISSING);
        }
    }

    /** Uma vez, ao abrir o aplicativo: fecha os registros de execucoes que ficaram abertas de uma sessao anterior. */
    public void reconcile() {
        controller.reconcile();
    }

    /** Avisos de andamento, numa thread do executor (a tela repassa para a fila de eventos). */
    public void addListener(Consumer<Status> listener) {
        listeners.add(listener);
    }

    public void removeListener(Consumer<Status> listener) {
        listeners.remove(listener);
    }

    /** Confere de novo o pacote guardado (arquivo por arquivo) no contexto pedido. De outro contexto, nao existe. */
    public Checked check(UUID organization, UUID workspace, String fingerprint) throws Refused {
        try {
            PackageContext context = new PackageContext(organization, workspace);
            LocalPackages.Item item = packages.find(context, fingerprint).orElseThrow(() -> new Refused("Este pacote não está neste computador, neste ambiente. Abra Editar e escolha o pacote desta máquina."));
            ExecutionPackage pkg = packages.inspect(context, fingerprint);
            return new Checked(pkg.fingerprint(), pkg.shortFingerprint(), item.displayName(), item.packageVersion(), item.entry(), item.pythonRequirement(),
                    item.dependencies(), controller.approvals().approved(pkg), pkg.dir().toString(), item.declaredName());
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        } catch (IllegalArgumentException e) {
            throw new Refused("Este pacote não está neste computador, neste ambiente. Abra Editar e escolha o pacote desta máquina.");
        }
    }

    /**
     * Confere o interpretador escolhido pela pessoa contra o pacote: e um Python de verdade, de versao que o pacote
     * aceita, com as dependencias exatas ja instaladas. Nada e instalado nem alterado no ambiente dela.
     */
    public Python python(UUID organization, UUID workspace, String fingerprint, String pythonPath) throws Refused {
        try {
            ExecutionPackage pkg = packages.inspect(new PackageContext(organization, workspace), fingerprint);
            PythonRuntime.Probe probe = PythonRuntime.probe(PythonRuntime.validate(path(pythonPath, "Escolha o interpretador Python.")), controller.probeDir());
            PythonRuntime.requireCompatible(probe, pkg);
            PythonRuntime.requireDependencies(probe.python(), pkg);
            return new Python(probe.python().toString(), probe.pythonVersion());
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /**
     * As instalacoes de Python deste computador, cada uma conferida contra o pacote (versao e dependencias declaradas).
     * Descoberta LIMITADA as fontes do sistema; nada e instalado nem alterado. Bloqueia: fora da linha de eventos.
     */
    /** As instalacoes de Python deste computador (so identidade local, tipo e versao), sem conferir pacote nenhum. */
    public List<PythonEnvironments.Installed> installedPythons() {
        return environments.installed(PythonProbe.Cancel.NEVER);
    }

    public PythonList pythons(UUID organization, UUID workspace, String fingerprint, PythonProbe.Cancel cancel) throws Refused {
        ExecutionPackage pkg = inspected(organization, workspace, fingerprint);
        PythonEnvironments.Discovery d = environments.discover(pkg, cancel);
        return new PythonList(remember(d.environments()), pkg.pythonRequirement(), d.failedSources(), d.unanswered(), d.cancelled());
    }

    /**
     * O ambiente virtual cuja RAIZ e a pasta que a pessoa escolheu (com o nome que tiver), conferido contra o pacote. A
     * pasta do codigo e a pasta de saida nao mudam com isto. Nada e procurado em subpastas nem em outras pastas; nada e
     * criado. Sem ambiente na resposta, {@code problems} diz por que - e nada do que ja estava escolhido muda.
     */
    public ProjectPythons virtualEnvironment(UUID organization, UUID workspace, String fingerprint, String folder, PythonProbe.Cancel cancel) throws Refused {
        ExecutionPackage pkg = inspected(organization, workspace, fingerprint);
        Path dir = path(folder, "Selecione a pasta do ambiente virtual.");
        PythonEnvironments.Project p = environments.virtual(dir, pkg, cancel);
        return new ProjectPythons(String.valueOf(p.folder()), remember(p.environments()), p.problems(), p.cancelled());
    }

    /**
     * Confere de novo UM ambiente ja listado contra o pacote como ele esta AGORA. Se o interpretador sumiu ou nao
     * responde, volta "nao foi possivel verificar": nenhum outro ambiente e posto no lugar. Vazio: cancelado.
     */
    public Optional<PythonOption> recheckPython(UUID organization, UUID workspace, String fingerprint, String id, PythonProbe.Cancel cancel) throws Refused {
        ExecutionPackage pkg = inspected(organization, workspace, fingerprint);
        PythonEnvironments.Environment before = known.get(id);
        if (before == null) {
            throw new Refused("Este ambiente Python não está mais na lista. Use Buscar novamente.");
        }
        PythonEnvironments.Environment now = environments.recheck(before, pkg, cancel);
        if (now == null) {
            return Optional.empty();
        }
        known.put(now.id(), now);
        return Optional.of(option(now));
    }

    private ExecutionPackage inspected(UUID organization, UUID workspace, String fingerprint) throws Refused {
        try {
            return packages.inspect(new PackageContext(organization, workspace), fingerprint);
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        } catch (IllegalArgumentException e) {
            throw new Refused("Este pacote não está neste computador, neste ambiente. Abra Editar e escolha o pacote desta máquina.");
        }
    }

    private List<PythonOption> remember(List<PythonEnvironments.Environment> list) {
        List<PythonOption> out = new java.util.ArrayList<>();
        for (PythonEnvironments.Environment e : list) {
            known.put(e.id(), e);
            out.add(option(e));
        }
        return List.copyOf(out);
    }

    private static PythonOption option(PythonEnvironments.Environment e) {
        List<String> pending = new java.util.ArrayList<>();
        for (PythonEnvironments.Pending p : e.pending()) {
            pending.add(p.requirement() + (p.installed() == null ? " (não instalado)" : " (instalada a versão " + p.installed() + ")"));
        }
        return new PythonOption(e.id(), e.kind(), e.folderName(), e.python().toString(), e.version() == null ? "" : e.version(), e.state(), List.copyOf(pending));
    }

    /**
     * O endereco {@code file:} de uma PASTA que existe, para a tela pedir ao sistema que a mostre no gerenciador de arquivos.
     * Abrir a pasta nao executa nada dela. Vazio se o caminho nao e uma pasta.
     */
    public Optional<String> folderUri(String folder) {
        try {
            Path p = folder == null || folder.isBlank() ? null : Path.of(folder.trim()).toAbsolutePath();
            return p != null && Files.isDirectory(p) ? Optional.of(p.toUri().toString()) : Optional.empty();
        } catch (InvalidPathException | SecurityException e) {
            return Optional.empty();
        }
    }

    /** A pasta de saida precisa existir (a execucao cria nela uma subpasta propria). */
    public String output(String folder) throws Refused {
        Path p = path(folder, "Escolha a pasta de saída.");
        if (!Files.isDirectory(p)) {
            throw new Refused("A pasta de saída não existe. Escolha uma pasta existente.");
        }
        return p.toString();
    }

    /**
     * Aprovacao EXPLICITA da pessoa para ESTE conteudo exato (a impressao digital conferida agora). Aprovar nao inicia
     * nada. Qualquer mudanca nos arquivos muda a impressao digital e exige nova aprovacao.
     */
    public void approve(UUID organization, UUID workspace, String fingerprint) throws Refused {
        try {
            controller.approvals().approve(packages.inspect(new PackageContext(organization, workspace), fingerprint), Clock.systemUTC().instant());
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /**
     * Inicia a execucao local. O contexto (organizacao, ambiente, pacote e, se houver, a automacao e a versao da Platform
     * a que a linha correspondia) e gravado no registro e nao muda mais.
     */
    public Status start(UUID organization, UUID workspace, String fingerprint, UUID automationId, UUID automationVersionId, String pythonPath,
                        String outputFolder) throws Refused {
        try {
            PackageContext context = new PackageContext(organization, workspace);
            LocalPackages.Item item = packages.find(context, fingerprint).orElseThrow(() -> new Refused("Este pacote não está neste computador, neste ambiente. Abra Editar e escolha o pacote desta máquina."));
            RunContext run = new RunContext(organization, workspace, item.fingerprint(), item.displayName(), automationId, automationVersionId);
            Path out = Path.of(output(outputFolder));
            lastOutputRoot = out.toString();
            return status(controller.start(new RunController.Request(item.dir(), run, path(pythonPath, "Escolha o interpretador Python."), out, null)));
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /**
     * A execucao em andamento, se houver, de QUALQUER tipo (a gerenciada tambem ocupa o computador): e o que as salvaguardas
     * de sair, terminar sessao e trocar de contexto consultam.
     */
    public Optional<Status> running() {
        return controller.running().map(this::status);
    }

    /** A execucao ativa ou a ultima desta sessao do aplicativo. */
    public Optional<Status> current() {
        return controller.current().filter(s -> !controller.managed(s.record().id())).map(this::status);
    }

    /** Pede o cancelamento da execucao em andamento (o processo e os que ele abriu). Nao bloqueia. */
    public boolean cancel() {
        // o botao da tela local so cancela o que a pessoa iniciou aqui; a gerenciada e cancelada por quem a pediu
        if (controller.running().filter(s -> controller.managed(s.record().id())).isPresent()) return false;
        return controller.cancel();
    }

    /**
     * Encerramento do aplicativo: cancela a execucao em andamento (registrada como cancelada pelo encerramento), espera
     * ate 15 s e grava os registros. O que o script ja fez no sistema nao e desfeito.
     */
    @Override
    public void close() {
        controller.close();
    }

    private Status status(RunController.Snapshot s) {
        ExecutionRecord r = s.record();
        boolean finished = r.state().terminal();
        String detail = r.code() == null ? "" : r.code().message();
        if (r.cancellation() != null && !finished) {
            detail = "Cancelamento pedido; aguardando o processo terminar.";
        }
        String out = lastOutputRoot == null ? "" : Path.of(lastOutputRoot).resolve(r.id().value()).toString();
        return new Status(r.id().value(), r.context().organizationId(), r.context().workspaceId(), r.context().fingerprint(), r.context().localName(), s.running(),
                finished, r.state() == ExecutionState.SUCCEEDED, r.state().label(), detail, s.elapsedMillis(), out, r.cancellation() != null,
                controller.runCopy(r.id()).map(Path::toString).orElse(""));
    }

    private static Path path(String text, String whenMissing) throws Refused {
        if (text == null || text.isBlank()) {
            throw new Refused(whenMissing);
        }
        try {
            return Path.of(text.trim());
        } catch (InvalidPathException e) {
            throw new Refused(whenMissing);
        }
    }
}
