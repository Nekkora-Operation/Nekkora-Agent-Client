package com.nekkora.client.nav;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.nekkora.client.nav.Directory.Failed;
import com.nekkora.client.nav.Directory.Failure;

/**
 * O que a pessoa esta vendo depois de entrar: quem ela e, as organizacoes dela, a organizacao escolhida, os ambientes
 * dessa organizacao, o ambiente escolhido e as automacoes dele. Sem interface grafica e sem rede: as consultas vao pela
 * porta {@link Directory}, numa thread de fundo, e o resultado so vale se ainda for o da vez.
 * <ul>
 * <li><b>resposta atrasada nao aparece</b>: cada consulta leva um numero; recarregar, voltar, trocar de organizacao ou
 * de ambiente, ou fechar, muda o numero, e a resposta antiga e descartada;</li>
 * <li><b>trocar de contexto apaga o que dependia dele</b>: voltar ou escolher outra organizacao limpa ambientes,
 * ambiente e automacoes; trocar de ambiente limpa as automacoes. Nada do contexto anterior fica disponivel para uma
 * acao;</li>
 * <li><b>falha nao e lista vazia</b>: o estado guarda a classificacao da falha ({@link Loadable});</li>
 * <li><b>so entra no que a Platform listou agora</b>: {@link #select} e {@link #selectWorkspace} recusam o que nao
 * estiver na lista carregada, e toda escolha e explicita (nada e escolhido sozinho, mesmo havendo um so);</li>
 * <li><b>sessao encerrada</b>: quem trata e a propria sessao (a tela volta para a entrada); aqui a consulta so para.</li>
 * </ul>
 */
public final class Navigation {

    /**
     * Retrato imutavel do que a tela mostra. {@code selected} nulo: seletor de organizacoes. {@code workspace} nulo:
     * nenhum ambiente escolhido (e {@code automations} fica {@code IDLE}).
     */
    public record State(Loadable<Profile> profile, Loadable<List<Organization>> organizations, Organization selected,
                        Loadable<List<Workspace>> workspaces, Workspace workspace, Loadable<Automation.Listing> automations) {

        /** O contexto completo (organizacao e ambiente) em que uma acao pode acontecer, ou nulo se falta escolher algo. */
        public Context context() {
            return selected != null && workspace != null ? new Context(selected, workspace) : null;
        }
    }

    /** Organizacao e ambiente escolhidos, juntos: uma acao leva o contexto em que nasceu. */
    public record Context(Organization organization, Workspace workspace) {
    }

    private final Directory directory;
    private final Executor background;
    private final Executor notifier;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private State state = new State(Loadable.idle(), Loadable.idle(), null, Loadable.idle(), null, Loadable.idle());
    private long profileTurn;
    private long organizationsTurn;
    private long workspacesTurn;
    private long automationsTurn;
    private boolean closed;

    /**
     * @param background onde as consultas bloqueantes rodam
     * @param notifier   onde os ouvintes sao chamados (a fila de eventos da interface, no aplicativo)
     */
    public Navigation(Directory directory, Executor background, Executor notifier) {
        this.directory = Objects.requireNonNull(directory);
        this.background = Objects.requireNonNull(background);
        this.notifier = Objects.requireNonNull(notifier);
    }

    public void addListener(Consumer<State> listener) {
        listeners.add(listener);
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Primeira carga, logo depois da entrada: a pessoa e as organizacoes dela. */
    public void start() {
        loadProfile();
        loadOrganizations();
    }

    /** "Tentar novamente" do nome e das iniciais. */
    public void loadProfile() {
        long mine;
        synchronized (lock) {
            if (closed || state.profile().status() == Loadable.Status.LOADING) {
                return;
            }
            mine = ++profileTurn;
            set(new State(Loadable.loading(), state.organizations(), state.selected(), state.workspaces(), state.workspace(), state.automations()));
        }
        run(() -> {
            Loadable<Profile> result;
            try {
                result = Loadable.ready(Objects.requireNonNull(directory.profile()));
            } catch (Failed f) {
                result = Loadable.failed(f.failure());
            } catch (RuntimeException e) {
                result = Loadable.failed(Failure.INVALID);
            }
            synchronized (lock) {
                if (!closed && profileTurn == mine) {
                    set(new State(result, state.organizations(), state.selected(), state.workspaces(), state.workspace(), state.automations()));
                }
            }
        });
    }

    /** Carrega (ou recarrega) as organizacoes. Sem efeito enquanto uma carga esta em curso: cliques repetidos nao acumulam. */
    public void loadOrganizations() {
        long mine;
        synchronized (lock) {
            if (closed || state.organizations().status() == Loadable.Status.LOADING) {
                return;
            }
            mine = ++organizationsTurn;
            workspacesTurn++;
            automationsTurn++;
            // recarregar invalida a escolha e tudo o que dependia dela: o que valia era a lista anterior
            set(new State(state.profile(), Loadable.loading(), null, Loadable.idle(), null, Loadable.idle()));
        }
        run(() -> {
            Loadable<List<Organization>> result;
            try {
                result = Loadable.ready(List.copyOf(directory.organizations()));
            } catch (Failed f) {
                result = Loadable.failed(f.failure());
            } catch (RuntimeException e) {
                result = Loadable.failed(Failure.INVALID);
            }
            synchronized (lock) {
                if (!closed && organizationsTurn == mine) {
                    set(new State(state.profile(), result, null, Loadable.idle(), null, Loadable.idle()));
                }
            }
        });
    }

    /**
     * Escolha explicita de uma organizacao da lista carregada; comeca a carregar os ambientes dela. Devolve
     * {@code false}, sem mudar nada, se a lista nao estiver pronta ou se a organizacao nao for uma das listadas
     * (comparacao pelo identificador).
     */
    public boolean select(Organization organization) {
        synchronized (lock) {
            if (closed || organization == null || !state.organizations().isReady()) {
                return false;
            }
            Organization listed = state.organizations().value().stream().filter(o -> o.id().equals(organization.id())).findFirst().orElse(null);
            if (listed == null) {
                return false;
            }
            workspacesTurn++;
            automationsTurn++;
            set(new State(state.profile(), state.organizations(), listed, Loadable.idle(), null, Loadable.idle()));
        }
        loadWorkspaces();
        return true;
    }

    /** Volta ao seletor de organizacoes. A lista de organizacoes continua; NADA da organizacao anterior fica escolhido. */
    public void back() {
        synchronized (lock) {
            if (closed || state.selected() == null) {
                return;
            }
            workspacesTurn++;
            automationsTurn++;
            set(new State(state.profile(), state.organizations(), null, Loadable.idle(), null, Loadable.idle()));
        }
    }

    /** Carrega (ou recarrega) os ambientes da organizacao escolhida. Recarregar desfaz a escolha de ambiente. */
    public void loadWorkspaces() {
        long mine;
        Organization organization;
        synchronized (lock) {
            if (closed || state.selected() == null || state.workspaces().status() == Loadable.Status.LOADING) {
                return;
            }
            organization = state.selected();
            mine = ++workspacesTurn;
            automationsTurn++;
            set(new State(state.profile(), state.organizations(), organization, Loadable.loading(), null, Loadable.idle()));
        }
        run(() -> {
            Loadable<List<Workspace>> result;
            try {
                result = Loadable.ready(List.copyOf(directory.workspaces(organization.id())));
            } catch (Failed f) {
                result = Loadable.failed(f.failure());
            } catch (RuntimeException e) {
                result = Loadable.failed(Failure.INVALID);
            }
            synchronized (lock) {
                // so vale se a organizacao ainda e a mesma E esta carga ainda e a da vez
                if (!closed && workspacesTurn == mine && state.selected() != null && state.selected().id().equals(organization.id())) {
                    set(new State(state.profile(), state.organizations(), organization, result, null, Loadable.idle()));
                }
            }
        });
    }

    /**
     * Escolha explicita de um ambiente da lista carregada (ou {@code null} para voltar a "Selecione um ambiente").
     * Qualquer troca apaga as automacoes do ambiente anterior antes de consultar as do novo.
     */
    public boolean selectWorkspace(Workspace workspace) {
        synchronized (lock) {
            if (closed || state.selected() == null || !state.workspaces().isReady()) {
                return false;
            }
            Workspace listed = null;
            if (workspace != null) {
                listed = state.workspaces().value().stream().filter(w -> w.id().equals(workspace.id())).findFirst().orElse(null);
                if (listed == null) {
                    return false;
                }
            }
            automationsTurn++;
            set(new State(state.profile(), state.organizations(), state.selected(), state.workspaces(), listed, Loadable.idle()));
            if (listed == null) {
                return true;
            }
        }
        loadAutomations();
        return true;
    }

    /** Carrega (ou recarrega) as automacoes do ambiente escolhido. */
    public void loadAutomations() {
        long mine;
        Context context;
        synchronized (lock) {
            context = state.context();
            if (closed || context == null || state.automations().status() == Loadable.Status.LOADING) {
                return;
            }
            mine = ++automationsTurn;
            set(new State(state.profile(), state.organizations(), state.selected(), state.workspaces(), state.workspace(), Loadable.loading()));
        }
        run(() -> {
            Loadable<Automation.Listing> result;
            try {
                result = Loadable.ready(Objects.requireNonNull(directory.automations(context.organization().id(), context.workspace().id())));
            } catch (Failed f) {
                result = Loadable.failed(f.failure());
            } catch (RuntimeException e) {
                result = Loadable.failed(Failure.INVALID);
            }
            synchronized (lock) {
                Context now = state.context();
                if (!closed && automationsTurn == mine && now != null && now.organization().id().equals(context.organization().id())
                        && now.workspace().id().equals(context.workspace().id())) {
                    set(new State(state.profile(), state.organizations(), state.selected(), state.workspaces(), state.workspace(), result));
                }
            }
        });
    }

    /** Fim da sessao ou do aplicativo: nenhuma resposta que chegue depois muda algo, e nenhum ouvinte e chamado. */
    public void close() {
        synchronized (lock) {
            closed = true;
            profileTurn++;
            organizationsTurn++;
            workspacesTurn++;
            automationsTurn++;
        }
    }

    private void run(Runnable task) {
        try {
            background.execute(task);
        } catch (RuntimeException e) {
            // aplicativo fechando: a consulta nao roda; o estado "carregando" morre com a tela
        }
    }

    /** Chamado com a trava. A ordem dos avisos e a ordem das mudancas. */
    private void set(State next) {
        state = next;
        notifier.execute(() -> {
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            listeners.forEach(l -> l.accept(next));
        });
    }
}
