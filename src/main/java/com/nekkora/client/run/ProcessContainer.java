package com.nekkora.client.run;

import java.util.UUID;

/**
 * AMBIENTE-PYTHON-001 - o grupo de processos do Windows (Job Object) para um processo AUXILIAR do aplicativo que nao e a
 * execucao de uma automacao: a criacao do ambiente temporario e o {@code pip} do ambiente gerenciado (camada {@code env}).
 * Um processo posto no grupo leva todos os descendentes junto, e {@link #terminate} encerra todos de uma vez: e assim que o
 * cancelamento do preparo para o {@code pip} e o que ele tiver aberto.
 *
 * <p>So uma fachada de {@link WindowsProcessGroup}: nenhuma funcao nova do sistema e chamada, e o nome do grupo e fixo e
 * proprio ({@code NekkoraClient-ambiente-<aleatorio>}), distinto do grupo de qualquer execucao. Fora do Windows, ou se o sistema
 * recusar, nao ha grupo: {@link #terminate} entao so encerra o processo e os descendentes que o Java enxerga.
 */
public final class ProcessContainer implements AutoCloseable {

    private final WindowsProcessGroup.Group group;
    private volatile ProcessHandle main;

    private ProcessContainer(WindowsProcessGroup.Group group) {
        this.group = group;
    }

    /** Um grupo novo, vazio. Nunca nulo: sem o grupo do Windows, a contencao cai para a arvore de processos do Java. */
    public static ProcessContainer create() {
        return new ProcessContainer(WindowsProcessGroup.create("NekkoraClient-ambiente-" + UUID.randomUUID()));
    }

    /** Ha o grupo do Windows (a contencao vale para todos os descendentes). */
    public boolean grouped() {
        return group != null;
    }

    /** Poe o processo recem-lancado no grupo. {@code false}: nao entrou (sem grupo, ou o sistema recusou). */
    public boolean add(Process process) {
        main = process.toHandle();
        return group != null && group.assign(process.pid());
    }

    /** Encerra TODOS os processos do grupo (e, por garantia, os descendentes vistos pelo Java e o processo principal). */
    public void terminate() {
        if (group != null) {
            group.terminate();
        }
        ProcessHandle h = main;
        if (h != null) {
            try {
                h.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (RuntimeException e) {
                // o grupo ja encerrou o que havia
            }
            h.destroyForcibly();
        }
    }

    /** Quantos processos do grupo estao vivos agora; -1 sem grupo ou sem resposta do sistema. */
    public int alive() {
        return group == null ? -1 : group.active();
    }

    @Override
    public void close() {
        if (group != null) {
            group.close();
        }
    }
}
