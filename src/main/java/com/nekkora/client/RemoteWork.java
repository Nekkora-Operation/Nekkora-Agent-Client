package com.nekkora.client;

import java.util.Optional;
import java.util.UUID;

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

    RemoteWork(Runner runner) {
        this.runner = runner;
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
                    job.dependencies(), job.pythonLocalId(), job.dir()), log == null ? null : log::line);
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

    @Override
    public java.util.List<RemoteMachine.Held> held(String exceptAttemptId) {
        return runner.heldManaged(exceptAttemptId).stream()
                .map(h -> new RemoteMachine.Held(h.key(), h.reason(), h.dir(), h.files(), h.bytes(), h.sinceEpochSecond(), h.removeAtEpochSecond())).toList();
    }
}
