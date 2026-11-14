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

    /** O pacote conferido na area local: o que vai ser executado e se aquele conteudo ja foi aprovado. */
    public record Checked(String fingerprint, String shortFingerprint, String name, String packageVersion, String entry, String pythonRequirement,
                          List<String> dependencies, boolean approved) {
    }

    /** O interpretador conferido contra o pacote: versao encontrada; compativel e com as dependencias exatas instaladas. */
    public record Python(String path, String version) {
    }

    /**
     * Retrato de uma execucao (a ativa ou a ultima). {@code organizationId} e {@code workspaceId} sao os do INICIO.
     * {@code detail}: por que terminou assim, quando ha o que dizer. {@code outputFolder}: onde o script gravou.
     */
    public record Status(String id, UUID organizationId, UUID workspaceId, String fingerprint, String name, boolean running, boolean finished,
                         boolean succeeded, String state, String detail, long elapsedMillis, String outputFolder, boolean cancelRequested) {
    }

    private final LocalPackages packages;
    private final RunController controller;
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
        this.packages = packages;
        this.controller = controller;
        controller.addListener(s -> {
            Status status = status(s);
            listeners.forEach(l -> l.accept(status));
        });
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
            LocalPackages.Item item = packages.find(context, fingerprint).orElseThrow(() -> new Refused("Este pacote não está neste computador, neste ambiente. Importe o .zip dele."));
            ExecutionPackage pkg = packages.inspect(context, fingerprint);
            return new Checked(pkg.fingerprint(), pkg.shortFingerprint(), item.displayName(), item.packageVersion(), item.entry(), item.pythonRequirement(),
                    item.dependencies(), controller.approvals().approved(pkg));
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        } catch (IllegalArgumentException e) {
            throw new Refused("Este pacote não está neste computador, neste ambiente. Importe o .zip dele.");
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
            LocalPackages.Item item = packages.find(context, fingerprint).orElseThrow(() -> new Refused("Este pacote não está neste computador, neste ambiente. Importe o .zip dele."));
            RunContext run = new RunContext(organization, workspace, item.fingerprint(), item.displayName(), automationId, automationVersionId);
            Path out = Path.of(output(outputFolder));
            lastOutputRoot = out.toString();
            return status(controller.start(new RunController.Request(item.dir(), run, path(pythonPath, "Escolha o interpretador Python."), out, null)));
        } catch (ExecRefused e) {
            throw new Refused(e.code().message());
        }
    }

    /** A execucao em andamento, se houver. E o que as salvaguardas de sair, terminar sessao e trocar de contexto consultam. */
    public Optional<Status> running() {
        return controller.running().map(this::status);
    }

    /** A execucao ativa ou a ultima desta sessao do aplicativo. */
    public Optional<Status> current() {
        return controller.current().map(this::status);
    }

    /** Pede o cancelamento da execucao em andamento (o processo e os que ele abriu). Nao bloqueia. */
    public boolean cancel() {
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
                finished, r.state() == ExecutionState.SUCCEEDED, r.state().label(), detail, s.elapsedMillis(), out, r.cancellation() != null);
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
