package com.nekkora.client.signin;

import com.nekkora.client.signin.SignInState.Notice;

/**
 * Entrada indisponivel: a fundacao (T01) ainda nao tem a integracao com a Platform. "Entrar" diz isso com clareza; nao
 * ha login simulado no aplicativo. A integracao real substitui esta classe.
 */
public final class UnavailableSignIn implements SignInAttempts {

    @Override
    public Attempt begin(Listener listener) {
        listener.failed(Notice.UNAVAILABLE);
        return () -> {
        };
    }
}
