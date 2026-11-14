package com.nekkora.client.signin;

/**
 * Estado da entrada, como a tela o mostra. {@code phase} diz o que esta acontecendo; {@code notice}, o que a ultima
 * tentativa (ou a sessao) deixou para dizer a pessoa. So {@link Phase#SIGNED_IN} abre a tela inicial.
 */
public record SignInState(Phase phase, Notice notice) {

    public enum Phase {
        /** Logo e Entrar habilitado. */
        IDLE,
        /** Entrar desabilitado; preparando a tentativa e abrindo o navegador. */
        OPENING_BROWSER,
        /** Navegador aberto; aguardando a pessoa concluir a entrada nele. */
        WAITING,
        /** A Platform confirmou a entrada. */
        SIGNED_IN
    }

    /** O que dizer na tela de entrada. Os textos ficam na interface ({@code SignInText}). */
    public enum Notice {
        NONE,
        /** A pessoa cancelou a tentativa no proprio aplicativo. Neutro: nao e falha. */
        CANCELLED,
        /** A pessoa recusou a entrada na pagina de confirmacao do navegador (retorno {@code access_denied} da tentativa). */
        DECLINED,
        /** A Platform respondeu, pelo contrato, que esta conta nao pode entrar neste aplicativo. */
        ACCESS_DENIED,
        /** O tempo da tentativa acabou. */
        EXPIRED,
        /** O navegador padrao nao abriu. */
        BROWSER_FAILED,
        /** O aplicativo nao conseguiu preparar o retorno local. */
        RETURN_UNAVAILABLE,
        /** Sem conexao com a Platform. */
        NETWORK,
        /** A Platform nao respondeu a tempo. */
        TIMEOUT,
        /** A conexao segura com a Platform foi recusada. */
        TLS,
        /** A Platform respondeu com erro. */
        SERVER,
        /** Muitas tentativas; aguardar. */
        RATE_LIMITED,
        /** O retorno do navegador nao pode ser confirmado. */
        INVALID_RETURN,
        /** A sessao acabou (prazo ou encerramento na conta). */
        SESSION_EXPIRED,
        /** A pessoa escolheu "Terminar sessao" no aplicativo. Neutro. */
        SIGNED_OUT,
        /** Entrar pelo navegador nao esta disponivel (aplicativo ou Platform sem o recurso). */
        UNAVAILABLE
    }

    public static final SignInState INITIAL = new SignInState(Phase.IDLE, Notice.NONE);

    public boolean busy() {
        return phase == Phase.OPENING_BROWSER || phase == Phase.WAITING;
    }
}
