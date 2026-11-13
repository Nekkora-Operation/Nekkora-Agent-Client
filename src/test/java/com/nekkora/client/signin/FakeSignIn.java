package com.nekkora.client.signin;

import java.util.ArrayList;
import java.util.List;

/**
 * Entrada SIMULADA, so para os testes (fica em src/test e nao entra no aplicativo): registra cada tentativa aberta e
 * deixa o teste decidir o que "o navegador" e "a Platform" respondem, e quando.
 */
public final class FakeSignIn implements SignInAttempts {

    public static final class FakeAttempt implements Attempt {
        public final Listener listener;
        public int cancelled;

        FakeAttempt(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void cancel() {
            cancelled++;
        }
    }

    public static final class FakeSession implements Session {
        public int ended;
        public int checks;
        private Runnable onEnded;

        @Override
        public void end() {
            ended++;
        }

        @Override
        public void onEnded(Runnable callback) {
            onEnded = callback;
        }

        @Override
        public void check() {
            checks++;
        }

        /** A Platform deixou de reconhecer a sessao (prazo ou revogacao). */
        public void expire() {
            if (onEnded != null) {
                onEnded.run();
            }
        }
    }

    public final List<FakeAttempt> attempts = new ArrayList<>();
    public RuntimeException failOnBegin;

    @Override
    public Attempt begin(Listener listener) {
        if (failOnBegin != null) {
            throw failOnBegin;
        }
        FakeAttempt a = new FakeAttempt(listener);
        attempts.add(a);
        return a;
    }

    public FakeAttempt last() {
        return attempts.get(attempts.size() - 1);
    }
}
