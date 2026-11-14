package com.nekkora.client.ui;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.signin.SignInState.Notice;

/** Textos da tela de entrada. Curtos, em portugues, sem termos tecnicos, codigos ou enderecos. */
public final class SignInText {

    public static final String APP_NAME = "Nekkora Agent";
    public static final String SIGN_IN = "Entrar";
    public static final String OPENING = "Abrindo o navegador...";
    public static final String WAITING = "Aguardando login no navegador...";
    public static final String CANCEL = "Cancelar";
    public static final String ALREADY_RUNNING = "O Nekkora Agent já está aberto.";
    public static final String BAD_CONFIGURATION = "A configuração deste aplicativo não é válida. Instale o Nekkora Agent novamente.";

    private SignInText() {
    }

    /**
     * Mensagem do aviso, ou vazio quando nao ha o que dizer. Uma frase para cada resultado que o aplicativo consegue
     * confirmar; nenhuma leva codigo, endereco, status ou detalhe tecnico.
     */
    public static String of(Notice notice) {
        return switch (notice) {
            case NONE -> "";
            case CANCELLED -> "Entrada cancelada.";
            case DECLINED -> "Entrada não autorizada. Você pode tentar novamente quando quiser.";
            case ACCESS_DENIED -> "Acesso não permitido. Sua conta não está autorizada a entrar neste aplicativo.";
            case SERVER -> "Não foi possível concluir a entrada. A Platform encontrou um problema. Tente novamente em instantes.";
            case NETWORK -> "Não foi possível conectar à Platform. Verifique sua conexão e tente novamente.";
            case EXPIRED -> "O tempo para entrar terminou. Clique em Entrar para iniciar uma nova tentativa.";
            case INVALID_RETURN -> "Não foi possível validar a entrada com segurança. Inicie uma nova tentativa.";
            case TLS -> "Não foi possível estabelecer uma conexão segura com a Platform. Tente novamente mais tarde.";
            case TIMEOUT -> "A Platform não respondeu a tempo. Tente novamente.";
            case RATE_LIMITED -> "Muitas tentativas seguidas. Aguarde um pouco e tente novamente.";
            case UNAVAILABLE -> "Entrar pelo navegador não está disponível no momento. Tente novamente mais tarde.";
            case BROWSER_FAILED -> "Não foi possível abrir o navegador. Confira se há um navegador padrão neste computador e tente novamente.";
            case RETURN_UNAVAILABLE -> "Não foi possível preparar o retorno do login neste computador. Tente novamente.";
            case SESSION_EXPIRED -> "Sua sessão terminou. Entre novamente.";
        };
    }

    /**
     * Avisos que nao sao falha do aplicativo nem da Platform: o que a propria pessoa decidiu (cancelar no aplicativo,
     * recusar no navegador) e o que so o tempo causou (prazo da tentativa, fim da sessao). Os demais usam a cor de erro.
     */
    public static boolean neutral(Notice notice) {
        return switch (notice) {
            case NONE, CANCELLED, DECLINED, EXPIRED, SESSION_EXPIRED -> true;
            default -> false;
        };
    }

    /** Como o estado da mensagem e identificado para leitores de tela (a cor nao e o unico sinal). */
    public static String kind(Notice notice) {
        return notice == Notice.NONE ? "" : neutral(notice) ? "Aviso da entrada" : "Erro na entrada";
    }

    /**
     * Linha da versao no rodape. O numero vem dos metadados do build; sem eles, a linha diz que e uma execucao de
     * desenvolvimento, e nunca um numero de release.
     */
    public static String version(BuildInfo build) {
        return build.version().map(v -> "Versão " + v).orElse("Versão de desenvolvimento");
    }

    /**
     * Linha de direitos no rodape, com o ano do build. So identifica a Nekkora: sem razao social, registro, link ou termo
     * de licenca, e sem efeito sobre as licencas dos componentes (THIRD-PARTY-NOTICES.md).
     */
    public static String rights(BuildInfo build) {
        return "© " + build.yearOrNow() + " Nekkora. Todos os direitos reservados.";
    }

    public static String themeToggle(String current, String other) {
        return "Tema: " + current + ". Clique para usar " + other + ".";
    }
}
