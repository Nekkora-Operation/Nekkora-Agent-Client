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
 * <li><b>atualizar nao troca nada de lugar</b>: {@link #refresh} consulta de novo as organizacoes e, havendo organizacao
 * escolhida, os ambientes e as automacoes do ambiente escolhido; o que esta na tela continua ate TUDO chegar, e entao e
 * trocado de uma vez. A escolha e mantida pelos identificadores enquanto a Platform ainda a listar; se nao listar mais, a
 * escolha e o que dependia dela saem (nada e escolhido no lugar). Se qualquer consulta falhar, nada muda e o estado diz
 * que os dados sao os de antes. Trocar de contexto, recarregar ou fechar descarta a atualizacao em curso;</li>
 * <li><b>sessao encerrada</b>: quem trata e a propria sessao (a tela volta para a entrada); aqui a consulta so para.</li>
 * </ul>
 */
public final class Navigation {

    /**
     * Retrato imutavel do que a tela mostra. {@code selected} nulo: seletor de organizacoes. {@code workspace} nulo:
     * nenhum ambiente escolhido (e {@code automations} fica {@code IDLE}).
     */
    public record State(Loadable<Profile> profile, Loadable<List<Organization>> organizations, Organization selected,
                        Loadable<List<Workspace>> workspaces, Workspace workspace, Loadable<Automation.Listing> automations, Refresh refresh) {

        /** Estado sem atualizacao em curso nem resultado de atualizacao: o de qualquer mudanca de contexto. */
        public State(Loadable<Profile> profile, Loadable<List<Organization>> organizations, Organization selected,
                     Loadable<List<Workspace>> workspaces, Workspace workspace, Loadable<Automation.Listing> automations) {
            this(profile, organizations, selected, workspaces, workspace, automations, Refresh.NONE);
        }

        State with(Refresh next) {
            return new State(profile, organizations, selected, workspaces, workspace, automations, next);
        }

        State withProfile(Loadable<Profile> next) {
            return new State(next, organizations, selected, workspaces, workspace, automations, refresh);
        }

        /** O contexto completo (organizacao e ambiente) em que uma acao pode acontecer, ou nulo se falta escolher algo. */
        public Context context() {
            return selected != null && workspace != null ? new Context(selected, workspace) : null;
        }
    }

    /**
     * A ultima atualizacao pedida pela pessoa ({@link #refresh}). {@code failure} so em {@link Phase#FAILED};
     * {@code outcome} e {@code lost} (o nome do que saiu do alcance) so em {@link Phase#DONE}.
     */
    public record Refresh(Phase phase, Failure failure, Outcome outcome, String lost) {

        public enum Phase {
            /** Nenhuma atualizacao pedida neste contexto. */
            NONE,
            /** Em curso: o que esta na tela e o de antes. */
            RUNNING,
            /** Tudo o que a tela mostra foi consultado agora. */
            DONE,
            /** Alguma consulta falhou: NADA foi trocado; o que esta na tela e o de antes. */
            FAILED
        }

        public enum Outcome {
            UPDATED,
            /** A Platform e do contrato anterior: a lista nao traz organizacoes novas sem entrar de novo. */
            UPDATED_WITHOUT_NEW_ORGANIZATIONS,
            /** A organizacao escolhida saiu do alcance: a tela voltou para o seletor de organizacoes. */
            ORGANIZATION_LOST,
            /** O ambiente escolhido saiu do alcance: nenhum ambiente esta escolhido. */
            WORKSPACE_LOST
        }

        public static final Refresh NONE = new Refresh(Phase.NONE, null, null, null);
        static final Refresh RUNNING = new Refresh(Phase.RUNNING, null, null, null);

        public boolean running() {
            return phase == Phase.RUNNING;
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
    private long refreshTurn;
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
            set(state.withProfile(Loadable.loading()));
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
                    set(state.withProfile(result));
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
            refreshTurn++;
            // recarregar invalida a escolha e tudo o que dependia dela: o que valia era a lista anterior
            set(new State(state.profile(), Loadable.loading(), null, Loadable.idle(), null, Loadable.idle()));
        }
        run(() -> {
            Loadable<List<Organization>> result;
            try {
                result = Loadable.ready(directory.organizations().items());
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
            refreshTurn++;
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
            refreshTurn++;
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
            refreshTurn++;
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
            refreshTurn++;
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
            refreshTurn++;
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
            refreshTurn++;
        }
    }

    // ------------------------------------------------------------------ atualizar

    /** O que uma atualizacao consultou, antes de entrar no estado. Nulo = nao foi consultado (nao havia escolha). */
    private record Fresh(Directory.Organizations organizations, List<Workspace> workspaces, Automation.Listing automations,
                         boolean organizationGone, boolean workspaceGone) {
    }

    /**
     * "Atualizar": consulta de novo o que a tela mostra, sem novo login e sem mexer na sessao. No seletor, as
     * organizacoes. Com organizacao escolhida, tambem os ambientes dela e, com ambiente escolhido e ainda listado, as
     * automacoes (com versoes e capacidades) desse ambiente.
     * <p>
     * Devolve {@code false}, sem consultar nada, se ja ha uma atualizacao em curso (cliques repetidos nao acumulam) ou se
     * a navegacao esta fechada. Se a lista que a tela mostra ainda nao carregou (primeira carga em curso ou falha), o
     * pedido vira a carga normal dela.
     */
    public boolean refresh() {
        long mine;
        Organization organization;
        Workspace workspace;
        synchronized (lock) {
            if (closed || state.refresh().running()) {
                return false;
            }
            if (!state.organizations().isReady()) {
                if (state.selected() == null) {
                    organization = null;
                    workspace = null;
                    mine = -1;
                } else {
                    return false;
                }
            } else if (state.selected() != null && !state.workspaces().isReady()) {
                organization = state.selected();
                workspace = null;
                mine = -2;
            } else {
                organization = state.selected();
                workspace = state.workspace();
                mine = ++refreshTurn;
                // consultas isoladas em curso (tentar novamente) nao podem cair por cima do resultado da atualizacao
                workspacesTurn++;
                automationsTurn++;
                Loadable<Automation.Listing> automations = state.automations().status() == Loadable.Status.LOADING ? Loadable.idle() : state.automations();
                set(new State(state.profile(), state.organizations(), organization, state.workspaces(), workspace, automations, Refresh.RUNNING));
            }
        }
        if (mine == -1) {
            loadOrganizations();
            return true;
        }
        if (mine == -2) {
            loadWorkspaces();
            return true;
        }
        run(() -> {
            Fresh fresh = null;
            Failure failure = null;
            try {
                fresh = consult(organization, workspace);
            } catch (Failed f) {
                failure = f.failure();
            } catch (RuntimeException e) {
                failure = Failure.INVALID;
            }
            synchronized (lock) {
                // trocou de contexto, recarregou ou fechou enquanto consultava: a resposta nao entra em lugar nenhum
                if (closed || refreshTurn != mine) {
                    return;
                }
                if (fresh == null) {
                    set(state.with(new Refresh(Refresh.Phase.FAILED, failure, null, null)));
                } else {
                    apply(fresh, organization, workspace);
                }
            }
        });
        return true;
    }

    /**
     * As consultas de uma atualizacao, em ordem, TODAS antes de qualquer troca. So o que a Platform confirma tira o
     * acesso: a organizacao que nao esta mais na lista dela, ou o {@code NOT_FOUND} dela para a organizacao ou o
     * ambiente. Qualquer outra falha (rede, limite, servidor) sai como falha da atualizacao inteira.
     */
    private Fresh consult(Organization organization, Workspace workspace) throws Failed {
        Directory.Organizations organizations = Objects.requireNonNull(directory.organizations());
        if (organization == null) {
            return new Fresh(organizations, null, null, false, false);
        }
        if (organizations.items().stream().noneMatch(o -> o.id().equals(organization.id()))) {
            return new Fresh(organizations, null, null, true, false);
        }
        List<Workspace> workspaces;
        try {
            workspaces = List.copyOf(directory.workspaces(organization.id()));
        } catch (Failed f) {
            if (f.failure() == Failure.NOT_FOUND) {
                return new Fresh(organizations, null, null, true, false);
            }
            throw f;
        }
        if (workspace == null) {
            return new Fresh(organizations, workspaces, null, false, false);
        }
        if (workspaces.stream().noneMatch(w -> w.id().equals(workspace.id()))) {
            return new Fresh(organizations, workspaces, null, false, true);
        }
        try {
            Automation.Listing automations = Objects.requireNonNull(directory.automations(organization.id(), workspace.id()));
            return new Fresh(organizations, workspaces, automations, false, false);
        } catch (Failed f) {
            if (f.failure() == Failure.NOT_FOUND) {
                return new Fresh(organizations, workspaces, null, false, true);
            }
            throw f;
        }
    }

    /** Com a trava: troca, de uma vez, o que a tela mostra pelo que foi consultado, mantendo a escolha pelos identificadores. */
    private void apply(Fresh fresh, Organization organization, Workspace workspace) {
        Loadable<List<Organization>> organizations = Loadable.ready(fresh.organizations().items());
        if (organization == null) {
            Refresh.Outcome outcome = fresh.organizations().current() ? Refresh.Outcome.UPDATED : Refresh.Outcome.UPDATED_WITHOUT_NEW_ORGANIZATIONS;
            set(new State(state.profile(), organizations, null, Loadable.idle(), null, Loadable.idle(), new Refresh(Refresh.Phase.DONE, null, outcome, null)));
            return;
        }
        if (fresh.organizationGone()) {
            // sem a organizacao, nada dela pode continuar na tela
            set(new State(state.profile(), organizations, null, Loadable.idle(), null, Loadable.idle(),
                    new Refresh(Refresh.Phase.DONE, null, Refresh.Outcome.ORGANIZATION_LOST, organization.name())));
            return;
        }
        Organization same = fresh.organizations().items().stream().filter(o -> o.id().equals(organization.id())).findFirst().orElseThrow();
        Loadable<List<Workspace>> workspaces = Loadable.ready(fresh.workspaces());
        if (workspace == null) {
            set(new State(state.profile(), organizations, same, workspaces, null, Loadable.idle(), new Refresh(Refresh.Phase.DONE, null, Refresh.Outcome.UPDATED, null)));
            return;
        }
        if (fresh.workspaceGone()) {
            set(new State(state.profile(), organizations, same, workspaces, null, Loadable.idle(),
                    new Refresh(Refresh.Phase.DONE, null, Refresh.Outcome.WORKSPACE_LOST, workspace.name())));
            return;
        }
        Workspace sameWorkspace = fresh.workspaces().stream().filter(w -> w.id().equals(workspace.id())).findFirst().orElseThrow();
        set(new State(state.profile(), organizations, same, workspaces, sameWorkspace, Loadable.ready(fresh.automations()),
                new Refresh(Refresh.Phase.DONE, null, Refresh.Outcome.UPDATED, null)));
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
