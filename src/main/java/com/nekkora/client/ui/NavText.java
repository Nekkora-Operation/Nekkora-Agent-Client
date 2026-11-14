package com.nekkora.client.ui;

import com.nekkora.client.nav.Automation;
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

    public static final String WORKSPACE = "Ambiente";
    public static final String WORKSPACE_PLACEHOLDER = "Selecione um ambiente";
    public static final String WORKSPACES_LOADING = "Carregando ambientes...";
    public static final String WORKSPACES_NONE = "Nenhum ambiente cadastrado";
    public static final String WORKSPACES_NONE_HINT = "Crie um ambiente na Platform para continuar.";
    public static final String AUTOMATIONS_HINT = "Selecione um ambiente para ver as automações.";
    public static final String AUTOMATIONS_LOADING = "Carregando automações...";
    public static final String AUTOMATIONS_EMPTY = "Nenhuma automação neste ambiente.";
    public static final String AUTOMATIONS_TRUNCATED = "A lista está incompleta: há mais automações do que o aplicativo mostra de uma vez.";
    public static final String RUN = "Executar";
    public static final String EDIT = "Editar";
    public static final String EDIT_DENIED = "Você não tem permissão para editar automações neste ambiente.";
    public static final String NEW_AUTOMATION = "Nova automação";
    public static final String IMPORT_ZIP = "Importar (.zip)";
    public static final String NEEDS_WORKSPACE = "Selecione um ambiente primeiro.";
    /** Acao que ainda nao existe nesta versao. Nao e falta de permissao da pessoa, e o texto nao pode sugerir isso. */
    public static final String ACTION_UNAVAILABLE = "Indisponível nesta versão de teste.";
    /** A nota por escrito das acoes que ainda nao existem nesta versao ("A, B e C ainda nao estao disponiveis..."). */
    public static String unavailable(java.util.List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        String list = names.size() == 1 ? names.get(0) : String.join(", ", names.subList(0, names.size() - 1)) + " e " + names.get(names.size() - 1);
        return list + (names.size() == 1 ? " ainda não está disponível" : " ainda não estão disponíveis") + " nesta versão de teste.";
    }

    public static final String SAVE = "Salvar";
    public static final String CANCEL = "Cancelar";
    public static final String LOCAL_ONLY = "Somente neste computador (não está na Platform)";
    public static final String LOCAL_PRESENT = "Pacote disponível neste computador";
    public static final String LOCAL_ABSENT = "Pacote não está neste computador";
    public static final String RENAME_REMOTE_TITLE = "Renomear automação na Platform";
    public static final String RENAME_REMOTE_SCOPE = "O novo nome vale na Platform, para todas as pessoas com acesso. As versões publicadas, os pacotes e os "
            + "manifestos não mudam.";
    public static final String RENAME_REMOTE_DONE = "Automação renomeada na Platform.";
    public static final String RENAME_REMOTE_DENIED = "Você não tem permissão para renomear esta automação. Nada foi alterado.";
    public static final String RENAME_LOCAL_TITLE = "Renomear neste computador";
    public static final String RENAME_LOCAL_SCOPE = "Muda só o nome mostrado neste computador. O pacote, o manifesto e a impressão digital não mudam, e "
            + "nada é alterado na Platform. Em branco, volta o nome declarado no pacote.";
    public static final String RENAME_LOCAL_DONE = "Nome alterado só neste computador.";
    public static final String NAME_FIELD = "Nome";
    public static final String NAME_REQUIRED = "Informe um nome.";
    public static final String IMPORT_TITLE = "Importar pacote (.zip)";
    public static final String IMPORT_FILTER = "Arquivo ZIP (*.zip)";
    public static final String NEW_TITLE = "Nova automação";
    public static final String NEW_SCOPE = "Prepara um pacote local a partir de um arquivo .zip com o código Python. Nada é criado na Platform, o código não é "
            + "executado e nenhuma dependência é instalada.";
    public static final String NEW_CHOOSE = "Escolher o .zip do código";
    public static final String NEW_PREPARE = "Preparar pacote";
    public static final String WORKING = "Trabalhando...";

    public static String imported(String name, String version, boolean already) {
        return already ? "Este pacote já estava importado neste ambiente: " + name + "."
                : "Pacote importado: " + name + " (versão do pacote " + version + "). Ele fica só neste computador, neste ambiente; nada foi executado.";
    }

    public static String prepared(String name, String version) {
        return "Pacote preparado: " + name + " (versão do pacote " + version + "). Ele fica só neste computador; nada foi criado na Platform.";
    }

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
            case REJECTED -> "A Platform não aceitou o que foi enviado. Confira e tente novamente.";
            case CONFLICT -> "Não é possível alterar agora: a automação ou o ambiente não está ativo.";
            case DENIED -> "Acesso não permitido. Sua conta não tem permissão para ver estas informações.";
            case NOT_FOUND -> "Você não tem mais acesso a este item, ou ele não existe mais.";
            case INVALID -> "A Platform respondeu de um jeito inesperado. Tente novamente.";
            case SESSION_ENDED -> "Sua sessão terminou. Entre novamente.";
        };
    }

    /**
     * Linha da versao de uma automacao. O numero e o da versao PUBLICADA na Platform; a versao DECLARADA no pacote, quando
     * existe, aparece com o seu proprio nome. Sem versao publicada (so rascunho, ou nenhuma versao), a linha diz isso.
     */
    public static String version(Automation a) {
        Automation.Published p = a.published();
        if (p == null) {
            return "Sem versão publicada";
        }
        return "Versão publicada " + p.number() + (p.packageVersion() == null ? "" : " · versão do pacote " + p.packageVersion());
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
