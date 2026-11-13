package com.nekkora.client.ui;

import com.nekkora.client.signin.SignInState.Notice;

/** Textos da tela de entrada. Curtos, em portugues, sem termos tecnicos, codigos ou enderecos. */
public final class SignInText {

    public static final String APP_NAME = "Nekkora Agent";
    public static final String SIGN_IN = "Entrar";
    public static final String OPENING = "Abrindo o navegador...";
    public static final String WAITING = "Aguardando login no navegador...";
    public static final String CANCEL = "Cancelar";
    public static final String ALREADY_RUNNING = "O Nekkora Agent já está aberto.";

    private SignInText() {
    }

    /** Mensagem do aviso, ou vazio quando nao ha o que dizer (inicio e cancelamento). */
    public static String of(Notice notice) {
        return switch (notice) {
            case NONE -> "";
            case EXPIRED -> "O tempo para entrar terminou. Tente novamente.";
            case BROWSER_FAILED -> "Não foi possível abrir o navegador. Confira se há um navegador padrão neste computador e tente novamente.";
            case RETURN_UNAVAILABLE -> "Não foi possível preparar o retorno do login neste computador. Tente novamente.";
            case NETWORK -> "Sem conexão com a Nekkora. Confira a internet e tente novamente.";
            case TIMEOUT -> "A Nekkora não respondeu a tempo. Tente novamente.";
            case TLS -> "A conexão segura com a Nekkora não pôde ser confirmada. Tente novamente mais tarde.";
            case SERVER -> "A Nekkora está indisponível no momento. Tente novamente em instantes.";
            case RATE_LIMITED -> "Muitas tentativas seguidas. Aguarde um pouco e tente novamente.";
            case INVALID_RETURN -> "Não foi possível confirmar o login. Inicie uma nova tentativa.";
            case SESSION_EXPIRED -> "Sua sessão terminou. Entre novamente.";
            case UNAVAILABLE -> "Entrar pelo navegador não está disponível nesta versão.";
        };
    }

    /** Avisos que nao sao falha: prazo da tentativa e fim da sessao. Os demais usam a cor de erro do tema. */
    public static boolean neutral(Notice notice) {
        return notice == Notice.NONE || notice == Notice.EXPIRED || notice == Notice.SESSION_EXPIRED;
    }

    public static String themeToggle(String current, String other) {
        return "Tema: " + current + ". Clique para usar " + other + ".";
    }
}
