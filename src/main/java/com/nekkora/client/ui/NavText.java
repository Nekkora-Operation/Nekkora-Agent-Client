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
    public static final String EDIT_DENIED = "Você não tem permissão para renomear automações neste ambiente. O nome cadastrado na Platform não pode "
            + "ser alterado por esta conta.";
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
    public static final String IMPORT_FILTER = "Arquivo ZIP (*.zip)";
    public static final String WORKING = "Trabalhando...";

    public static final String INTEGRATION_LOCAL_ONLY = "Somente local — integração com a Platform não configurada";
    public static final String INTEGRATION_LOCAL_ONLY_HINT = "Este aplicativo não está vinculado a uma máquina na Platform. As execuções daqui são só deste computador: "
            + "sem gatilhos da Platform e sem envio de registros de monitoramento.";
    public static final String RUN_DENIED = "Você não tem permissão para executar automações neste ambiente.";
    public static final String RUN_NO_PACKAGE = "O pacote desta automação não está neste computador. Abra Editar e, em Pacote nesta máquina, escolha um "
            + "pacote local ou baixe uma versão publicada da Platform. Nada é baixado sozinho.";
    public static final String RUN_TITLE = "Executar neste computador";
    public static final String RUN_SCOPE = "Execução local. Nada desta execução é enviado à Platform — nem estado, registros, saída ou término — mesmo com a "
            + "sua conta conectada, e nem depois, quando a rede voltar. O histórico fica só neste computador. Nada começa até você clicar em Iniciar "
            + "execução.";
    public static final String RUN_CHECKING = "Conferindo o pacote...";
    public static final String CHOOSE = "Escolher...";
    public static final String CLOSE = "Fechar";
    public static final String RUN_APPROVE = "Aprovar este pacote";
    public static final String RUN_APPROVAL_TEXT = "Ao aprovar, ESTE conteúdo exato poderá ser executado por você neste computador; qualquer mudança nos arquivos exige "
            + "nova aprovação. Aprovar não inicia nada.";
    public static final String RUN_APPROVED = "Este conteúdo exato foi aprovado por você neste computador.";
    public static final String RUN_START = "Iniciar execução";
    public static final String RUN_START_NEEDS = "Falta conferir o pacote, escolher o Python e a pasta de saída, ou aprovar o pacote.";
    public static final String RUN_CANCEL = "Cancelar execução";
    public static final String RUN_OUTPUT_HINT = "A execução cria uma subpasta própria aqui.";
    public static final String RUN_LOCAL_RECORD = "Registro só neste computador.";
    public static final String RUN_BUSY = "Já há uma execução local em andamento. Aguarde o fim dela, ou cancele-a, antes de iniciar outra.";
    public static final String RUNNING_BLOCKS_CONTEXT = "Há uma execução local em andamento. Conclua ou cancele a execução antes de trocar de organização ou de ambiente.";
    public static final String RUNNING_BLOCKS_SIGN_OUT = "Há uma execução local em andamento. Conclua ou cancele a execução antes de terminar a sessão.";
    public static final String EXIT_TITLE = "Sair do Nekkora Agent?";
    public static final String EXIT_WHILE_RUNNING = "Há uma execução local em andamento. Sair encerra o processo dela (e os que ele abriu) e a registra como "
            + "cancelada; o que já foi feito no sistema não é desfeito.";
    public static final String EXIT_STAY = "Voltar";
    public static final String EXIT_CONFIRM = "Encerrar execução e sair";

    /** "1 min 05 s". */
    public static String elapsed(long millis) {
        long s = Math.max(0, millis / 1000);
        return s < 60 ? s + " s" : (s / 60) + " min " + String.format("%02d", s % 60) + " s";
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
    // ---- Editar: pacote nesta maquina (AGENT-CLIENT-PACOTES-001 T02)
    public static final String EDIT_TITLE = "Editar automação";
    public static final String PKG_TITLE = "Pacote nesta máquina";
    public static final String PKG_NONE = "Nenhum pacote desta automação neste computador.";
    public static final String PKG_LOCAL_TITLE = "Usar projeto ou pacote local";
    public static final String PKG_LOCAL_HINT = "Escolha um pacote Nekkora (arquivo .zip com nekkora-external.json) ou a pasta de um pacote. Escolher não "
            + "executa nem instala nada.";
    public static final String PKG_PICK_ZIP = "Escolher arquivo .zip";
    public static final String PKG_PICK_FOLDER = "Escolher pasta";
    public static final String PKG_PICK_ZIP_TITLE = "Escolher o pacote (.zip)";
    public static final String PKG_PICK_FOLDER_TITLE = "Escolher a pasta do pacote";
    public static final String PKG_USE_SEPARATE = "Usar como projeto local separado";
    public static final String PKG_LOCAL_FAILED = "Não foi possível usar este arquivo ou esta pasta.";
    public static final String PKG_DOWNLOAD_TITLE = "Baixar da Platform";
    public static final String PKG_DOWNLOAD = "Baixar";
    public static final String PKG_DOWNLOAD_CANCEL = "Cancelar download";
    public static final String PKG_VERSION_FIELD = "Versão publicada";
    public static final String PKG_VERSION_PLACEHOLDER = "Selecione uma versão publicada";
    public static final String PKG_VERSIONS_LOADING = "Consultando as versões…";
    public static final String PKG_VERSIONS_FAILED = "Versões não consultadas";
    public static final String PKG_VERSIONS_RETRY = "Tentar novamente";
    public static final String PKG_VERSIONS_NONE = "Nenhuma versão publicada com pacote para baixar.";
    public static final String PKG_PROGRESS = "Progresso do download";
    public static final String PKG_DOWNLOAD_DENIED = "Sua conta não pode baixar pacotes neste ambiente: é preciso poder executar ou gerenciar a automação.";
    public static final String PKG_DOWNLOAD_UNSUPPORTED = "Esta versão da Platform não oferece o download de pacotes pelo aplicativo.";
    public static final String PKG_DOWNLOAD_CANCELLED = "Download cancelado. O que havia chegado foi descartado e nada foi instalado.";
    public static final String PKG_DOWNLOAD_INTERRUPTED = "O download foi interrompido. O que havia chegado foi descartado e nada foi instalado. Tente novamente.";
    public static final String PKG_DOWNLOAD_UNAVAILABLE = "O pacote desta versão não está disponível na Platform no momento (versão não publicada ou arquivo "
            + "indisponível). Nada foi baixado.";
    public static final String PKG_DOWNLOAD_FORBIDDEN = "A Platform não permitiu o download: sua conta não pode baixar o pacote desta automação.";
    public static final String PKG_DOWNLOAD_GONE = "Esta versão não está mais ao seu alcance. Nada foi baixado.";

    /** O que este computador tem para a automacao: nome e versao DECLARADOS no pacote, origem e, se veio da Platform, a versao de la. */
    public static String packageStatus(com.nekkora.client.pack.Workshop.LocalItem item, boolean isPublished, Integer publishedNumber) {
        String origin = item.originLabel().isEmpty() ? "" : " · origem: " + item.originLabel();
        String source = item.sourceLabel() == null || item.sourceLabel().isBlank() ? "" : " (" + item.sourceLabel() + ")";
        String version = item.versionNumber() != null ? " · versão " + item.versionNumber() + " da Platform"
                : isPublished && publishedNumber != null ? " · mesmo conteúdo da versão publicada " + publishedNumber : " · não é a versão publicada mais recente";
        return "Pacote disponível nesta máquina: " + item.declaredName() + ", versão do pacote " + item.packageVersion() + origin + source + version + ".";
    }

    public static String packageMatched(com.nekkora.client.pack.Workshop.UseLocalResult r, Integer publishedNumber) {
        return "Pacote conferido e disponível nesta máquina: é o mesmo conteúdo da versão publicada" + (publishedNumber == null ? "" : " " + publishedNumber)
                + ". Origem: " + r.origin() + " · " + r.pathLabel() + " · versão do pacote " + r.packageVersion() + ". Nada foi executado.";
    }

    public static String packageDiffers(com.nekkora.client.pack.Workshop.UseLocalResult r, Integer publishedNumber) {
        return "Projeto local diferente da versão publicada. O conteúdo de " + r.pathLabel() + " (" + r.declaredName() + ", versão do pacote "
                + r.packageVersion() + ") não é o da versão publicada" + (publishedNumber == null ? "" : " " + publishedNumber)
                + " e não será apresentado como ela. Escolha: usar como projeto local separado, ou selecionar o pacote correto.";
    }

    public static String packageSeparate(com.nekkora.client.pack.Workshop.UseLocalResult r) {
        return "Guardado como projeto local separado (" + r.declaredName() + ", versão do pacote " + r.packageVersion()
                + "), sem ligação com esta automação. Ele aparece na lista como item só deste computador. Para publicá-lo, envie o pacote pela Platform.";
    }

    public static String packageDownloaded(int versionNumber) {
        return "Versão " + versionNumber + " baixada e conferida. Pacote disponível nesta máquina. Nada foi executado nem instalado.";
    }

    /** Uma versao no seletor de download: numero na Platform, versao declarada no pacote e tamanho. */
    public static String versionChoice(Automation.Version v) {
        return "Versão " + v.number() + (v.packageVersion() == null ? "" : " · pacote " + v.packageVersion()) + " · " + size(v.sizeBytes());
    }

    public static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f KB", bytes / 1024.0);
        }
        return String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    public static String progress(long received, long total) {
        return total <= 0 ? size(received) : size(received) + " de " + size(total);
    }

    /** Por que o download nao terminou. Em todos os casos nada foi instalado. */
    public static String downloadFailed(Failure failure) {
        return switch (failure) {
            case CANCELLED -> PKG_DOWNLOAD_CANCELLED;
            case NETWORK, TIMEOUT -> PKG_DOWNLOAD_INTERRUPTED;
            case CONFLICT -> PKG_DOWNLOAD_UNAVAILABLE;
            case DENIED -> PKG_DOWNLOAD_FORBIDDEN;
            case NOT_FOUND -> PKG_DOWNLOAD_GONE;
            default -> of(failure) + " Nada foi instalado.";
        };
    }

    // ---- gerar pacote novo (AGENT-CLIENT-PACOTES-001 T01)
    public static final String GENERATE_NEW = "Gerar pacote novo";
    public static final String GENERATE_TITLE = "Gerar pacote novo";
    public static final String GENERATE_SUPPORT = "Gere um pacote .zip do seu projeto. Para cadastrar ou publicar a automação, envie o pacote pela Platform.";
    public static final String GENERATE_FOLDER = "Pasta do projeto";
    public static final String GENERATE_BROWSE = "Procurar pasta";
    public static final String GENERATE_BROWSE_TITLE = "Escolher a pasta do projeto";
    public static final String GENERATE_ENTRY = "Arquivo de entrada";
    public static final String GENERATE_NAME = "Nome da automação";
    public static final String GENERATE_VERSION = "Versão declarada no pacote (x.y.z)";
    public static final String GENERATE_PYTHON = "Python exigido (por exemplo >=3.10,<3.14)";
    public static final String GENERATE_DEPENDENCIES = "Dependências, uma por linha (nome==versão)";
    public static final String GENERATE_DEPENDENCIES_CONFIRM = "Conferi as dependências acima";
    public static final String GENERATE_COMPOSITION = "Arquivos do pacote e detalhes técnicos";
    public static final String GENERATE_ADVANCED_SHOW = "Mostrar opções avançadas";
    public static final String GENERATE_ADVANCED_HIDE = "Ocultar opções avançadas";
    public static final String GENERATE_BUTTON = "Gerar pacote";
    public static final String GENERATE_NEEDS = "Escolha a pasta, o arquivo de entrada e o nome.";
    public static final String GENERATE_MANIFEST_FOUND = "Esta pasta já tem um manifesto de pacote (nekkora-external.json): os dados dele foram aproveitados e a "
            + "versão não foi alterada.";
    public static final String GENERATE_FOLDER_FAILED = "Não foi possível ler esta pasta.";
    public static final String GENERATE_FAILED = "Não foi possível gerar o pacote.";
    public static final String GENERATE_SAVE_TITLE = "Salvar o pacote como";
    public static final String GENERATE_REPLACE = "Substituir";
    public static final String GENERATE_NOT_REPLACED = "O arquivo que já existia não foi alterado. Escolha outro nome ou confirme a substituição.";
    public static final String GENERATED = "Pacote gerado. Envie este arquivo pela Platform para cadastrar ou publicar a automação.";
    public static final String GENERATE_REVEAL = "Abrir pasta do arquivo";

    public static String generateReplace(String path) {
        return "Já existe um arquivo com este nome:\n" + path + "\n\nSubstituir?";
    }

    /** Nome sugerido no "Salvar como": nome e versao do pacote, so com caracteres seguros para nome de arquivo. */
    public static String generateFileName(String name, String version) {
        String base = (name == null || name.isBlank() ? "pacote" : name.trim()).replaceAll("[^A-Za-z0-9._-]", "-");
        String v = version == null || version.isBlank() ? "" : "-" + version.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return base + v + ".zip";
    }

    /**
     * O resumo do que sera gerado. A versao e a DECLARADA no pacote: gerar nao a reserva nem a publica na Platform, e o
     * nome nao e conferido la.
     */
    public static String generateSummary(com.nekkora.client.pack.Workshop.PackageSummary s) {
        String deps = s.dependencies().isEmpty() ? "nenhuma declarada" : String.join(", ", s.dependencies());
        return "Resumo: nome " + s.name() + " · entrada " + s.entry() + " · versão declarada no pacote " + s.version()
                + " (não reservada nem publicada na Platform) · Python " + (s.python() == null || s.python().isBlank() ? "não informado" : s.python())
                + " · dependências: " + deps + " · " + s.included().size() + (s.included().size() == 1 ? " arquivo incluído" : " arquivos incluídos") + ", "
                + s.excluded().size() + " fora (veja em opções avançadas).";
    }

    // ---- atualizar (AGENT-CLIENT-NAVEGACAO-002)
    public static final String REFRESH = "Atualizar";
    public static final String REFRESHING = "Atualizando…";
    public static final String REFRESH_TIP = "Consultar de novo, na Platform, os dados desta tela";
    public static final String REFRESHED = "Dados atualizados.";
    public static final String REFRESH_FAILED = "Não foi possível atualizar. Tente novamente.";
    public static final String REFRESH_STALE = "Os dados na tela são os de antes e não foram atualizados.";
    public static final String REFRESH_OLD_PLATFORM = "Lista consultada, mas esta versão da Platform só mostra organizações novas depois de entrar de novo.";

    public static String lostOrganization(String name) {
        return "Você não tem mais acesso à organização \u201c" + name + "\u201d. Ela saiu da lista.";
    }

    public static String lostWorkspace(String name) {
        return "Você não tem mais acesso ao ambiente \u201c" + name + "\u201d. Selecione outro ambiente.";
    }

    /** A orientacao especifica de uma atualizacao que falhou (vazia quando nao ha o que acrescentar). */
    public static String refreshGuidance(Failure failure) {
        return switch (failure) {
            case NETWORK -> "Verifique sua conexão.";
            case TIMEOUT -> "A Platform não respondeu a tempo.";
            case TLS -> "Não foi possível estabelecer uma conexão segura com a Platform.";
            case SERVER -> "A Platform encontrou um problema; aguarde alguns instantes.";
            case RATE_LIMITED -> "Muitas consultas seguidas: aguarde um pouco.";
            case DENIED -> "Sua conta não tem permissão para esta consulta.";
            case INVALID -> "A Platform respondeu de um jeito inesperado.";
            case SESSION_ENDED -> "Sua sessão terminou. Entre novamente.";
            case NOT_FOUND, REJECTED, CONFLICT, CANCELLED, LOCAL -> "";
        };
    }

    /** Atualizacao que falhou: o que houve, a orientacao e o aviso de que a tela mostra os dados de antes. */
    public static String refreshFailed(Failure failure) {
        String guidance = refreshGuidance(failure);
        return REFRESH_FAILED + (guidance.isEmpty() ? "" : " " + guidance) + " " + REFRESH_STALE;
    }

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
            case CANCELLED -> "Operação cancelada.";
            case LOCAL -> "Não foi possível gravar neste computador. Confira o espaço em disco e tente novamente.";
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
