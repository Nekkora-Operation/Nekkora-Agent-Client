package com.nekkora.client.signin;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import com.nekkora.client.nav.Directory;
import com.nekkora.client.signin.SignInAttempts.Attempt;
import com.nekkora.client.signin.SignInAttempts.Session;
import com.nekkora.client.signin.SignInState.Notice;
import com.nekkora.client.signin.SignInState.Phase;

/**
 * Maquina de estados da entrada, sem interface grafica. Garante:
 * <ul>
 * <li><b>uma tentativa por vez</b>: {@link #start()} com uma tentativa em curso (ou ja dentro) nao faz nada, por mais
 * que se clique;</li>
 * <li><b>resposta atrasada nao entra</b>: cada tentativa tem um numero; aviso de tentativa cancelada, encerrada ou
 * substituida e descartado, e uma sessao que chegue assim e encerrada na hora;</li>
 * <li><b>so a confirmacao da Platform abre a tela inicial</b>: o unico caminho para {@link Phase#SIGNED_IN} e
 * {@code signedIn(session)};</li>
 * <li><b>fechar encerra</b>: {@link #close()} cancela a tentativa, encerra a sessao e torna o fluxo inerte.</li>
 * </ul>
 * Os ouvintes sao chamados pelo {@code notifier} (a fila de eventos da interface, no aplicativo).
 */
public final class SignInFlow {

    private final SignInAttempts attempts;
    private final Executor notifier;
    private final List<Consumer<SignInState>> listeners = new CopyOnWriteArrayList<>();

    private final Object lock = new Object();
    private SignInState state = SignInState.INITIAL;
    private long generation;
    private Attempt attempt;
    private Session session;
    private boolean closed;

    public SignInFlow(SignInAttempts attempts, Executor notifier) {
        this.attempts = Objects.requireNonNull(attempts);
        this.notifier = Objects.requireNonNull(notifier);
    }

    public void addListener(Consumer<SignInState> listener) {
        listeners.add(listener);
    }

    public SignInState state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Entrar. Sem efeito se ja ha tentativa em curso, se a pessoa ja entrou ou se o aplicativo esta fechando. */
    public void start() {
        long mine;
        synchronized (lock) {
            if (closed || state.phase() != Phase.IDLE) {
                return;
            }
            mine = ++generation;
            set(new SignInState(Phase.OPENING_BROWSER, Notice.NONE));
        }
        Attempt a;
        try {
            a = attempts.begin(new Guarded(mine));
        } catch (RuntimeException e) {
            finish(mine, Notice.UNAVAILABLE);
            return;
        }
        boolean stale;
        synchronized (lock) {
            stale = closed || generation != mine || !state.busy();
            if (!stale) {
                attempt = a;
            }
        }
        if (stale) {
            // cancelada, encerrada ou ja concluida enquanto begin() rodava: os recursos locais nao ficam abertos
            a.cancel();
        }
    }

    /** Cancelar durante a espera: volta ao inicio com o aviso neutro de cancelamento, nunca com erro. */
    public void cancel() {
        Attempt a;
        synchronized (lock) {
            if (closed || !state.busy()) {
                return;
            }
            generation++;
            a = attempt;
            attempt = null;
            set(new SignInState(Phase.IDLE, Notice.CANCELLED));
        }
        if (a != null) {
            a.cancel();
        }
    }

    /** Confere a sessao com a Platform (por exemplo, quando a janela volta a ficar ativa). */
    public void checkSession() {
        Session s;
        synchronized (lock) {
            s = state.phase() == Phase.SIGNED_IN ? session : null;
        }
        if (s != null) {
            s.check();
        }
    }

    /** As consultas da sessao atual, ou nulo se a pessoa nao entrou. */
    public Directory directory() {
        synchronized (lock) {
            return state.phase() == Phase.SIGNED_IN && session != null ? session.directory() : null;
        }
    }

    /**
     * "Terminar sessao": descarta o token agora, pede o encerramento a Platform (melhor esforco, sem esperar a resposta)
     * e volta para a entrada. O aplicativo afirma so o que e certo: a sessao acabou NESTE aplicativo.
     */
    public void signOut() {
        Session s;
        synchronized (lock) {
            if (closed || state.phase() != Phase.SIGNED_IN) {
                return;
            }
            generation++;
            s = session;
            session = null;
            set(new SignInState(Phase.IDLE, Notice.SIGNED_OUT));
        }
        if (s != null) {
            s.end();
        }
    }

    /** Encerramento do aplicativo: nada da tentativa ou da sessao sobrevive. */
    public void close() {
        Attempt a;
        Session s;
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            a = attempt;
            s = session;
            attempt = null;
            session = null;
        }
        if (a != null) {
            a.cancel();
        }
        if (s != null) {
            s.end();
        }
    }

    private void finish(long mine, Notice notice) {
        Attempt a;
        synchronized (lock) {
            if (closed || generation != mine || !state.busy()) {
                return;
            }
            a = attempt;
            attempt = null;
            set(new SignInState(Phase.IDLE, notice));
        }
        if (a != null) {
            a.cancel();
        }
    }

    private void sessionEnded(long mine) {
        Session s;
        synchronized (lock) {
            if (closed || generation != mine || state.phase() != Phase.SIGNED_IN) {
                return;
            }
            s = session;
            session = null;
            set(new SignInState(Phase.IDLE, Notice.SESSION_EXPIRED));
        }
        if (s != null) {
            s.end();
        }
    }

    /** Chamado com a trava. A ordem dos avisos e a ordem das mudancas. */
    private void set(SignInState next) {
        state = next;
        notifier.execute(() -> listeners.forEach(l -> l.accept(next)));
    }

    /** Avisos de UMA tentativa: fora da vez dela, nada muda. */
    private final class Guarded implements SignInAttempts.Listener {
        private final long mine;

        Guarded(long mine) {
            this.mine = mine;
        }

        @Override
        public void browserOpened() {
            synchronized (lock) {
                if (!closed && generation == mine && state.phase() == Phase.OPENING_BROWSER) {
                    set(new SignInState(Phase.WAITING, Notice.NONE));
                }
            }
        }

        @Override
        public void signedIn(Session s) {
            Objects.requireNonNull(s);
            Attempt a;
            boolean accepted;
            synchronized (lock) {
                accepted = !closed && generation == mine && state.busy();
                a = accepted ? attempt : null;
                if (accepted) {
                    attempt = null;
                    session = s;
                    set(new SignInState(Phase.SIGNED_IN, Notice.NONE));
                }
            }
            if (!accepted) {
                // resposta atrasada (tentativa cancelada, substituida ou aplicativo fechando): a sessao nao e usada
                s.end();
                return;
            }
            if (a != null) {
                a.cancel();
            }
            s.onEnded(() -> sessionEnded(mine));
        }

        @Override
        public void failed(Notice reason) {
            finish(mine, reason == null ? Notice.INVALID_RETURN : reason);
        }
    }
}
