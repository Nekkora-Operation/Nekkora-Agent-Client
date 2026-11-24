package com.nekkora.client;

import java.util.Optional;
import java.util.UUID;

import com.nekkora.client.env.ManagedEnvironments;
import com.nekkora.client.remote.RemoteMachine;

import com.nekkora.client.run.Runner;

/**
 * A ponte entre o canal da maquina e o motor de execucao (PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03). Fica no ponto de
 * montagem do aplicativo de proposito: a camada {@code remote} nao conhece o motor nem os pacotes, e o motor nao conhece a
 * rede nem a Platform. Aqui so se traduz: o que o canal recebeu vira uma execucao GERENCIADA do motor (a mesma vaga da
 * execucao local, registro e pastas proprios, aprovacoes locais nem consultadas nem criadas), e o que o motor diz vira o
 * motivo ou o estado do contrato da maquina.
 *
 * <p>Nada de uma execucao iniciada pela pessoa neste aplicativo passa por esta classe.
 */
final class RemoteWork implements RemoteMachine.Work {

    private final Runner runner;
    /** AMBIENTE-PYTHON-001: o ambiente gerenciado por execucao (nulo: este aplicativo nao o oferece). */
    private final ManagedEnvironments environments;
    /** Grava o limite do cache do pip escolhido na tela (MiB). */
    private final java.util.function.IntPredicate saveCacheLimit;

    RemoteWork(Runner runner) {
        this(runner, null, mib -> false);
    }

    RemoteWork(Runner runner, ManagedEnvironments environments, java.util.function.IntPredicate saveCacheLimit) {
        this.runner = runner;
        this.environments = environments;
        this.saveCacheLimit = saveCacheLimit;
        if (environments != null) {
            // a pasta de ambiente de uma tentativa em preparo nunca e apagada pela limpeza do motor
            runner.setEnvironmentBusy(environments::active);
        }
    }

    @Override
    public boolean busy() {
        return runner.busy();
    }

    @Override
    public Optional<String> dir(String attemptId) {
        return runner.managedFolder(attemptId);
    }

    @Override
    public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
        try {
            RemoteMachine.LogSink log = job.log();
            return runner.startManaged(Runner.ManagedJob.of(UUID.fromString(job.organizationId()), UUID.fromString(job.workspaceId()),
                    UUID.fromString(job.automationId()), UUID.fromString(job.automationVersionId()), job.zip(), job.sha256(), job.fingerprint(),
                    job.dependencies(), job.pythonLocalId(), job.dir(), job.environmentPython()), log == null ? null : log::line);
        } catch (Runner.ManagedRefused e) {
            throw new RemoteMachine.WorkRefused(switch (e.why()) {
                case BUSY -> "BUSY";
                case PACKAGE_INVALID -> "PACKAGE_INVALID";
                // pacote de um tipo que este aplicativo nao executa: a limitacao e do aplicativo, nao do pacote
                case PACKAGE_UNSUPPORTED -> "CLIENT_INCOMPATIBLE";
                case ENVIRONMENT_GONE -> "ENVIRONMENT_GONE";
                case ENVIRONMENT_INCOMPATIBLE -> "ENVIRONMENT_INCOMPATIBLE";
                case DEPENDENCIES_MISSING -> "DEPENDENCIES_MISSING";
                case SESSION_UNAVAILABLE -> "SESSION_UNAVAILABLE";
                // nao foi possivel preparar as pastas ou gravar o registro neste computador
                case STORAGE -> "DISK_PRESSURE";
            });
        } catch (IllegalArgumentException e) {
            throw new RemoteMachine.WorkRefused("CLIENT_INCOMPATIBLE");
        }
    }

    @Override
    public Optional<RemoteMachine.Progress> progress(String run) {
        return runner.managedStatus(run).map(s -> new RemoteMachine.Progress(s.state(), s.exitCode()));
    }

    @Override
    public String processes(String run) {
        return runner.managedProcesses(run);
    }

    @Override
    public String localHold() {
        return runner.localHold();
    }

    @Override
    public RemoteMachine.Kept kept() {
        long[] n = runner.managedProtected();
        return new RemoteMachine.Kept((int) n[0], n[1], runner.managedSpaceLow());
    }

    @Override
    public void cancel(String run) {
        runner.cancelManaged(run);
    }

    @Override
    public java.util.List<RemoteMachine.ResultFile> results(String attemptId, String run) {
        return runner.managedOutputs(attemptId, run).stream().map(o -> new RemoteMachine.ResultFile(o.name(), o.file(), o.size())).toList();
    }

    @Override
    public void discard(String attemptId, String run) {
        runner.discardManaged(attemptId, run);
    }

    @Override
    public void discardOutputs(String attemptId) {
        runner.discardManagedOutputs(attemptId);
    }

    @Override
    public int sweep(String keepAttemptId) {
        return runner.sweepManaged(keepAttemptId);
    }

    @Override
    public void retentionDays(int days) {
        runner.setManagedKeep(java.time.Duration.ofDays(days));
    }

    @Override
    public RemoteMachine.Verified verify(String pythonLocalId, java.util.List<String> dependencies) {
        Runner.ManagedCheck c = runner.verifyManaged(pythonLocalId, dependencies);
        return new RemoteMachine.Verified(c.outcome().name(), c.missing());
    }

    @Override
    public void holdOutputs(String attemptId, String reason) {
        runner.holdManagedOutputs(attemptId, reason);
    }

    // ------------------------------------------------------------------ AMBIENTE-PYTHON-001

    @Override
    public boolean managesEnvironments() {
        return environments != null;
    }

    @Override
    public RemoteMachine.Resolved resolveEnvironment(String resolutionId, String pythonLocalId, java.util.List<String> dependencies,
                                                     java.util.function.BooleanSupplier cancel) {
        var base = environments == null ? null : runner.installedPython(pythonLocalId).orElse(null);
        if (base == null) {
            return new RemoteMachine.Resolved("ENVIRONMENT_GONE", null, null, java.util.List.of(), null);
        }
        ManagedEnvironments.Resolution r = environments.resolve(resolutionId, base, dependencies, cancel);
        return new RemoteMachine.Resolved(r.outcome(), r.pythonVersion(), r.pipVersion(), r.lock().stream().map(RemoteWork::item).toList(), failure(r.failure()));
    }

    @Override
    public RemoteMachine.Prepared prepareEnvironment(RemoteMachine.PrepareJob job, java.util.function.Consumer<String> stage,
                                                     java.util.function.BooleanSupplier cancel) {
        var base = environments == null ? null : runner.installedPython(job.pythonLocalId()).orElse(null);
        // a pasta da tentativa e sempre a que o motor deu para ela; nada fora dela e criado
        var dir = runner.managedDir(job.attemptId()).filter(d -> d.toString().equals(job.dir())).orElse(null);
        if (base == null || dir == null) {
            return new RemoteMachine.Prepared("FAILED", "ENVIRONMENT_SETUP_FAILED", null, null, java.util.List.of(),
                    new RemoteMachine.EnvFailure(null, "OTHER", "O Python escolhido não está mais neste computador."), 0, 0, "", null);
        }

        ManagedEnvironments.Preparation p = environments.prepare(dir, base,
                job.lock().stream().map(i -> new ManagedEnvironments.LockItem(i.name(), i.version(), i.sha256(), i.fileName())).toList(), job.dependencies(),
                job.checks(), stage::accept, cancel);
        return new RemoteMachine.Prepared(p.outcome(), p.refusal(), p.pythonVersion(), p.pipVersion(), p.installed().stream().map(RemoteWork::item).toList(),
                failure(p.failure()), p.createMs(), p.installMs(), p.log(), p.python() == null ? null : p.python().toString());
    }

    @Override
    public void environmentsIdle() {
        if (environments != null) {
            environments.sweep();
            environments.pruneCache();
        }
    }

    @Override
    public long[] environmentCache() {
        return environments == null ? null : new long[] {environments.cacheBytes(), environments.cacheLimitMiB()};
    }

    @Override
    public boolean environmentCacheLimit(int mib) {
        return environments != null && mib >= 0 && mib <= ManagedEnvironments.MAX_CACHE_MIB && saveCacheLimit.test(mib);
    }

    @Override
    public boolean clearEnvironmentCache() {
        return environments != null && environments.clearCache();
    }

    private static RemoteMachine.LockItem item(ManagedEnvironments.LockItem i) {
        return new RemoteMachine.LockItem(i.name(), i.version(), i.sha256(), i.fileName());
    }

    private static RemoteMachine.EnvFailure failure(ManagedEnvironments.Failure f) {
        return f == null ? null : new RemoteMachine.EnvFailure(f.requirement(), f.phase(), f.summary());
    }

    @Override
    public java.util.List<RemoteMachine.Held> held(String exceptAttemptId) {
        return runner.heldManaged(exceptAttemptId).stream()
                .map(h -> new RemoteMachine.Held(h.key(), h.reason(), h.dir(), h.files(), h.bytes(), h.sinceEpochSecond(), h.removeAtEpochSecond())).toList();
    }
}
