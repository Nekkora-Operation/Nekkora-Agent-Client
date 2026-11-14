package com.nekkora.client.ui;

import com.nekkora.client.nav.Directory.Failure;
import com.nekkora.client.nav.Organization;
import com.nekkora.client.nav.Profile;

/** Textos das telas depois da entrada. Curtos, em portugues, sem codigos, enderecos ou status. */
public final class NavText {

    public static final String ORGANIZATIONS_TITLE = "Selecione sua organização";
    public static final String ORGANIZATIONS_LOADING = "Carregando organizações...";
    public static final String ORGANIZATIONS_EMPTY = "Você ainda não participa de nenhuma organização.";
    public static final String RETRY = "Tentar novamente";
    public static final String AWAITING_ACTIVATION = "Aguardando ativação";
    public static final String AWAITING_ACTIVATION_HINT = "A Nekkora ainda não ativou esta organização. O aplicativo não ativa organizações.";
    public static final String BACK = "Voltar para as organizações";

    public static final String MENU = "Menu do usuário";
    public static final String MENU_SETTINGS = "Configurações";
    public static final String MENU_HELP = "Ajuda";
    public static final String MENU_UNAVAILABLE = "Indisponível nesta versão";
    public static final String MENU_SIGN_OUT = "Terminar sessão";
    public static final String MENU_EXIT = "Sair";
    public static final String NO_NAME = "Usuário";

    private NavText() {
    }

    /** Por que a consulta nao trouxe o resultado. Nunca e o texto de "lista vazia". */
    public static String of(Failure failure) {
        return switch (failure) {
            case NETWORK -> "Não foi possível conectar à Platform. Verifique sua conexão e tente novamente.";
            case TIMEOUT -> "A Platform não respondeu a tempo. Tente novamente.";
            case TLS -> "Não foi possível estabelecer uma conexão segura com a Platform. Tente novamente mais tarde.";
            case SERVER -> "A Platform encontrou um problema. Tente novamente em instantes.";
            case RATE_LIMITED -> "Muitas consultas seguidas. Aguarde um pouco e tente novamente.";
            case DENIED -> "Acesso não permitido. Sua conta não tem permissão para ver estas informações.";
            case NOT_FOUND -> "Você não tem mais acesso a este item, ou ele não existe mais.";
            case INVALID -> "A Platform respondeu de um jeito inesperado. Tente novamente.";
            case SESSION_ENDED -> "Sua sessão terminou. Entre novamente.";
        };
    }

    /** Como o leitor de tela anuncia um cartao: nome, identificador e, se for o caso, o estado. */
    public static String card(Organization o) {
        return o.name() + ", identificador " + o.label() + (o.awaitingActivation() ? ", " + AWAITING_ACTIVATION.toLowerCase(java.util.Locale.ROOT) : "");
    }

    /** O que identifica a pessoa no menu: o nome; sem nome, o e-mail; sem nada (perfil nao carregado), um rotulo neutro. */
    public static String person(Profile profile) {
        if (profile == null) {
            return NO_NAME;
        }
        return profile.hasName() ? profile.displayName().trim() : profile.email();
    }
}
