package com.nekkora.client.nav;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.nekkora.client.nav.Directory.Failed;
import com.nekkora.client.nav.Directory.Failure;

/**
 * O que a pessoa esta vendo depois de entrar: quem ela e, as organizacoes dela e a organizacao escolhida. Sem interface
 * grafica e sem rede: as consultas vao pela porta {@link Directory}, numa thread de fundo, e o resultado so vale se ainda
 * for o da vez.
 * <ul>
 * <li><b>resposta atrasada nao aparece</b>: cada consulta leva um numero; recarregar, voltar, escolher outra
 * organizacao ou fechar muda o numero, e a resposta antiga e descartada;</li>
 * <li><b>falha nao e lista vazia</b>: o estado guarda a classificacao da falha ({@link Loadable});</li>
 * <li><b>so entra em organizacao que a Platform listou agora</b>: {@link #select} recusa o que nao estiver na lista
 * carregada, e a escolha e sempre explicita (uma unica organizacao nao e escolhida sozinha);</li>
 * <li><b>sessao encerrada</b>: quem trata e a propria sessao (a tela volta para a entrada); aqui a consulta so para.</li>
 * </ul>
 */
public final class Navigation {

    /** Retrato imutavel do que a tela mostra. {@code selected} nulo: seletor de organizacoes. */
    public record State(Loadable<Profile> profile, Loadable<List<Organization>> organizations, Organization selected) {
    }

    private final Directory directory;
    private final Executor background;
    private final Executor notifier;
    private final List<Consumer<State>> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private State state = new State(Loadable.idle(), Loadable.idle(), null);
    private long profileTurn;
    private long organizationsTurn;
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
            set(new State(Loadable.loading(), state.organizations(), state.selected()));
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
                    set(new State(result, state.organizations(), state.selected()));
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
            // recarregar invalida a escolha: o que valia era a lista anterior
            set(new State(state.profile(), Loadable.loading(), null));
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
                    set(new State(state.profile(), result, null));
                }
            }
        });
    }

    /**
     * Escolha explicita de uma organizacao da lista carregada. Devolve {@code false}, sem mudar nada, se a lista nao
     * estiver pronta ou se a organizacao nao for uma das listadas (comparacao pelo identificador).
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
            set(new State(state.profile(), state.organizations(), listed));
            return true;
        }
    }

    /** Volta ao seletor de organizacoes. A lista ja carregada continua; nada da organizacao anterior fica escolhido. */
    public void back() {
        synchronized (lock) {
            if (closed || state.selected() == null) {
                return;
            }
            set(new State(state.profile(), state.organizations(), null));
        }
    }

    /** Fim da sessao ou do aplicativo: nenhuma resposta que chegue depois muda algo, e nenhum ouvinte e chamado. */
    public void close() {
        synchronized (lock) {
            closed = true;
            profileTurn++;
            organizationsTurn++;
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
