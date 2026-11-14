package com.nekkora.client.signin;

import com.nekkora.client.signin.SignInState.Notice;

/**
 * Porta da entrada pelo navegador. A implementacao real (retorno local, PKCE, troca com a Platform) fica em
 * {@code auth}; a interface e o fluxo ({@link SignInFlow}) nao sabem como ela funciona.
 */
public interface SignInAttempts {

    /**
     * Comeca UMA tentativa e devolve na hora. O andamento chega pelo {@code listener}, de qualquer thread; depois de
     * {@link Attempt#cancel()} nenhum aviso precisa chegar, e o que chegar e ignorado pelo fluxo.
     */
    Attempt begin(Listener listener);

    interface Attempt {
        /** Encerra a tentativa e libera os recursos locais. Idempotente. */
        void cancel();
    }

    interface Listener {
        /** O navegador abriu; a pessoa segue nele. */
        void browserOpened();

        /** A Platform confirmou a entrada. So aqui existe sessao. */
        void signedIn(Session session);

        /**
         * A tentativa terminou sem entrada, com o resultado ja classificado pelo que o contrato de fato mostrou (recusa da
         * pessoa no navegador: {@link Notice#DECLINED}).
         */
        void failed(Notice reason);
    }

    /** Sessao do Client, so em memoria. */
    interface Session {
        /** Encerra a sessao na Platform (melhor esforco) e descarta o token. Idempotente. */
        void end();

        /** Avisa quando a sessao deixar de valer (prazo ou revogacao). No maximo uma vez. */
        void onEnded(Runnable callback);

        /** Confere a sessao com a Platform agora, sem bloquear quem chama. */
        void check();
    }
}
