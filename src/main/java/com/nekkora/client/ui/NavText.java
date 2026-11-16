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

    public static final String INTEGRATION_LOCAL_ONLY = "Executar aqui é somente local";
    public static final String INTEGRATION_LOCAL_ONLY_HINT = "O que você executa por este aplicativo roda só neste computador: nenhum registro ou resultado "
            + "é enviado à Platform, com o computador habilitado para execução remota ou não.";
    public static final String RUN_DENIED = "Você não tem permissão para executar automações neste ambiente.";
    public static final String RUN_NO_PACKAGE = "O pacote desta automação não está neste computador. Abra Editar e, em Pacote nesta máquina, escolha um "
            + "pacote local ou baixe uma versão publicada da Platform. Nada é baixado sozinho.";
    public static final String RUN_TITLE = "Executar neste computador";
    public static final String RUN_SCOPE = "Execução local. Os registros ficam neste computador.";
    public static final String RUN_AUTOMATION = "Automação";
    public static final String RUN_CODE = "Local da automação";
    public static final String RUN_CODE_NOTE = "Pacote de origem, em pasta gerenciada pelo aplicativo. Local de execução ainda não preparado: a cópia é criada ao "
            + "iniciar.";
    public static final String RUN_CODE_INTEGRITY = "Alterações na pasta do pacote passam pela verificação de integridade antes de executar: um arquivo mudado muda a "
            + "impressão digital e exige nova aprovação.";
    public static final String RUN_OUTPUT = "Pasta de saída";
    public static final String RUN_APPROVAL = "Aprovação";
    public static final String RUN_OPEN_FOLDER = "Abrir pasta";
    public static final String RUN_OPEN_CODE = "Abrir a pasta da automação";
    public static final String RUN_OPEN_HINT = "Mostra a pasta no gerenciador de arquivos. Nada é executado. " + RUN_CODE_INTEGRITY;
    public static final String RUN_DETAILS = "Detalhes técnicos";
    public static final String RUN_DETAILS_SHOW = "Mostrar detalhes técnicos";
    public static final String RUN_DETAILS_HIDE = "Ocultar detalhes técnicos";
    public static final String RUN_LAST_TITLE = "Última execução";
    public static final String RUN_LAST_CODE = "Local de execução (a cópia que foi executada)";
    public static final String RUN_LAST_OUTPUT = "Saída desta execução";
    public static final String RUN_OPEN_LAST_CODE = "Abrir a pasta da cópia executada";
    public static final String RUN_OPEN_LAST_OUTPUT = "Abrir a pasta de saída desta execução";
    public static final String RUN_FOLDER_GONE = "Esta pasta não existe mais neste computador.";

    /** A linha da automacao: nome e versao. O resto fica nos detalhes tecnicos. */
    public static String runAutomation(com.nekkora.client.run.Runner.Checked c) {
        return c.name() + " · versão " + c.packageVersion();
    }

    /** O que ainda falta para iniciar, dito no botao desabilitado. */
    public static String runStartNeeds(boolean packageOk, boolean pythonOk, boolean outputOk, boolean approved) {
        java.util.List<String> missing = new java.util.ArrayList<>();
        if (!packageOk) {
            missing.add("conferir o pacote");
        }
        if (!pythonOk) {
            missing.add("escolher um ambiente Python compatível");
        }
        if (!outputOk) {
            missing.add("escolher a pasta de saída");
        }
        if (!approved) {
            missing.add("aprovar o pacote");
        }
        return missing.isEmpty() ? "Aguarde." : "Falta " + String.join(", ", missing) + ".";
    }

    /** Tudo o que e extenso e nao cabe na tela principal: identificacao completa do pacote, requisitos e o ambiente escolhido. */
    public static String runDetails(com.nekkora.client.run.Runner.Checked c, com.nekkora.client.run.Runner.PythonOption python, java.util.List<String> failedSources) {
        StringBuilder b = new StringBuilder();
        if (c == null) {
            return "";
        }
        b.append("Execução local: nada desta execução é enviado à Platform — nem estado, registros, saída ou término. Nada começa até você clicar em "
                + "Iniciar execução.\n\n");
        b.append("Nome: ").append(c.name()).append('\n');
        b.append("Nome declarado no pacote: ").append(c.declaredName()).append('\n');
        b.append("Versão declarada no pacote: ").append(c.packageVersion()).append('\n');
        b.append("Entrada: ").append(c.entry()).append('\n');
        b.append("Requisito de Python: ").append(c.pythonRequirement() == null ? com.nekkora.client.pack.Workshop.NO_PYTHON_REQUIREMENT
                : c.pythonRequirement() + " (" + com.nekkora.client.pack.Workshop.pythonMeaning(c.pythonRequirement()) + ")").append('\n');
        b.append("Dependências declaradas: ").append(c.dependencies().isEmpty() ? "nenhuma" : String.join(", ", c.dependencies())).append('\n');
        b.append("Impressão digital: ").append(c.fingerprint()).append('\n');
        b.append("Pacote de origem: ").append(c.packageDir()).append('\n');
        b.append(RUN_CODE_INTEGRITY).append('\n');
        b.append('\n');
        if (python == null) {
            b.append("Ambiente Python: nenhum escolhido\n");
        } else {
            b.append("Ambiente Python: ").append(pythonChoice(python)).append('\n');
            b.append("Interpretador: ").append(python.path()).append('\n');
            b.append("Situação: ").append(pythonReason(python, c.pythonRequirement())).append('\n');
        }
        if (!failedSources.isEmpty()) {
            b.append("Busca parcial: sem resposta de ").append(String.join(", ", failedSources)).append('\n');
        }
        return b.toString();
    }
    public static final String RUN_CHECKING = "Conferindo o pacote...";
    public static final String CHOOSE = "Escolher...";
    public static final String CLOSE = "Fechar";
    public static final String RUN_APPROVE = "Aprovar este pacote";
    public static final String RUN_APPROVAL_TEXT = "Pendente. Ao aprovar, este conteúdo exato poderá ser executado por você neste computador. Aprovar não inicia nada.";
    public static final String RUN_APPROVED = "Este conteúdo exato foi aprovado por você neste computador.";
    public static final String RUN_START = "Iniciar execução";
    public static final String RUN_START_NEEDS = "Falta conferir o pacote, escolher um ambiente Python compatível e a pasta de saída, ou aprovar o pacote.";

    // ---- ambiente Python da execucao (AGENT-CLIENT-PYTHON-001)
    public static final String PY_TITLE = "Ambiente Python";
    public static final String PY_BOX_HINT = "Escolha o Python que executará esta automação. Nada será instalado.";
    public static final String PY_LOCATE = "Localizar ambiente virtual…";
    public static final String PY_LOCATE_TITLE = "Selecionar a pasta do ambiente virtual";
    public static final String PY_LOCATE_HINT = "Abre um seletor de pasta. Escolha a pasta raiz do ambiente virtual (a que contém pyvenv.cfg), não o arquivo "
            + "principal da automação nem o python.exe. A pasta do código e a pasta de saída não mudam.";
    public static final String PY_PLACEHOLDER_SEARCHING = "Procurando ambientes Python…";
    public static final String PY_PLACEHOLDER_CHOOSE = "Escolha o ambiente Python";
    public static final String PY_PLACEHOLDER_NONE = "Nenhum ambiente Python encontrado";
    public static final String PY_SEARCH_AGAIN = "Buscar novamente";
    public static final String PY_SEARCH_AGAIN_HINT = "Procura de novo as instalações de Python deste computador e confere os ambientes. Não atualiza os dados da Platform.";
    public static final String PY_SEARCHING = "Procurando e verificando os ambientes Python deste computador…";
    public static final String PY_SEARCHING_FOLDER = "Verificando o ambiente virtual da pasta selecionada…";
    public static final String PY_CHECKING = "Verificando";
    public static final String PY_CHOOSE = "Escolha na caixa o ambiente Python que executará esta automação.";
    /** Informacao, nao falha: a pessoa apontou uma pasta sem ambiente virtual, e ha outro Python que serve. */
    public static final String PY_NOT_AN_ENVIRONMENT = "Nenhum ambiente virtual encontrado na pasta selecionada. Você pode continuar usando um Python compatível da lista.";
    public static final String PY_NOT_AN_ENVIRONMENT_ALONE = "Nenhum ambiente virtual encontrado na pasta selecionada. Selecione a pasta raiz do ambiente virtual "
            + "(a que contém pyvenv.cfg).";
    public static final String PY_KEPT = "A escolha anterior foi mantida.";
    public static final String PY_COMPATIBLE = "Compatível com o pacote: versão e dependências declaradas conferidas.";
    public static final String PY_UNVERIFIED = "Não foi possível verificar este ambiente Python. Tente novamente ou selecione outra opção.";
    public static final String PY_GONE = "O ambiente Python escolhido antes não foi encontrado nesta busca. Escolha outro; nenhum foi selecionado no lugar.";
    public static final String PY_SEARCH_FAILED = "Não foi possível procurar os ambientes Python. Use Buscar novamente.";
    public static final String PY_FOLDER_FAILED = "Não foi possível verificar esta pasta. Tente novamente ou selecione outra.";
    public static final String PY_NOT_A_FOLDER = "O caminho selecionado não é uma pasta. Selecione a pasta raiz do ambiente virtual.";

    /** A faixa de Python e as dependencias que o pacote DECLARA (o requisito real, como esta no pacote). */
    public static String pythonRequirement(String requirement, java.util.List<String> dependencies) {
        // sem requisito: o autor nao declarou restricao de Python (nao e promessa de que qualquer Python serve)
        String python = requirement == null || requirement.isBlank() ? "um Python (o pacote não declara restrição de versão)" : "Python " + requirement;
        // poucas dependencias cabem na frase; muitas ficam nos detalhes tecnicos (a tela principal nao repete listas longas)
        String deps = dependencies.isEmpty() ? "" : dependencies.size() <= 2 ? " com " + String.join(", ", dependencies)
                : " e de " + dependencies.size() + " dependências declaradas (veja em detalhes técnicos)";
        return python + deps;
    }

    public static String pythonNoneCompatible(String requirement, java.util.List<String> dependencies) {
        return "Nenhum ambiente Python compatível encontrado. Esta automação precisa de " + pythonRequirement(requirement, dependencies)
                + ". Localize um ambiente virtual que atenda ao pacote ou prepare uma instalação adequada.";
    }

    /** A situacao, em palavras. Dependencia em outra versao e "incompativel"; so ausencia e "ausente". */
    public static String pythonState(com.nekkora.client.run.Runner.PythonOption o) {
        return switch (o.state()) {
            case COMPATIBLE -> "Compatível";
            case VERSION_INCOMPATIBLE -> "Versão incompatível";
            case DEPENDENCIES_PENDING -> o.pending().stream().anyMatch(p -> p.contains("instalada a versão")) ? "Dependências incompatíveis" : "Dependências ausentes";
            case UNVERIFIED -> "Não foi possível verificar";
        };
    }

    /** O que e este ambiente: instalacao do computador, ambiente virtual do projeto ou ambiente virtual que estava no PATH. */
    public static String pythonKind(com.nekkora.client.run.Runner.PythonOption o) {
        String version = o.version().isEmpty() ? "Python" : "Python " + o.version();
        return switch (o.kind()) {
            case INSTALLED -> version + " — instalado neste computador";
            case PROJECT -> "Ambiente do projeto (" + o.folderName() + ") — " + version;
            case VIRTUAL -> "Ambiente virtual (" + o.folderName() + ") — " + version;
        };
    }

    /** A linha da lista: tipo, versao e situacao. */
    public static String pythonLine(com.nekkora.client.run.Runner.PythonOption o) {
        return pythonKind(o) + " — " + pythonState(o);
    }

    /**
     * O resumo curto do ambiente escolhido, para a tela principal: compativel, ou o motivo em uma frase. O caminho do
     * interpretador e a lista completa dos requisitos ficam nos detalhes tecnicos.
     */
    public static String pythonSummary(com.nekkora.client.run.Runner.PythonOption o, String requirement) {
        return switch (o.state()) {
            case COMPATIBLE -> PY_COMPATIBLE;
            case VERSION_INCOMPATIBLE -> "Versão incompatível: o pacote pede " + com.nekkora.client.pack.Workshop.pythonMeaning(requirement) + ".";
            case DEPENDENCIES_PENDING -> (o.pending().size() == 1 ? "Falta 1 requisito declarado neste ambiente: " : "Faltam " + o.pending().size()
                    + " requisitos declarados neste ambiente: ") + String.join("; ", o.pending().subList(0, Math.min(2, o.pending().size())))
                    + (o.pending().size() > 2 ? " e mais " + (o.pending().size() - 2) + " (veja em detalhes técnicos)" : "") + ". Nada é instalado pelo aplicativo.";
            case UNVERIFIED -> PY_UNVERIFIED;
        };
    }

    /** O motivo completo da situacao, para os detalhes tecnicos. */
    public static String pythonReason(com.nekkora.client.run.Runner.PythonOption o, String requirement) {
        return switch (o.state()) {
            case COMPATIBLE -> "compatível. Versão e dependências declaradas conferidas; o que o pacote não declara não é verificável";
            case VERSION_INCOMPATIBLE -> "versão incompatível com o requisito do pacote (" + requirement + ")";
            case DEPENDENCIES_PENDING -> "requisitos declarados não atendidos: " + String.join("; ", o.pending());
            case UNVERIFIED -> "não foi possível verificar";
        };
    }

    /** A opcao na caixa de selecao: versao e situacao; num ambiente virtual, o nome da pasta dele. */
    public static String pythonChoice(com.nekkora.client.run.Runner.PythonOption o) {
        return pythonName(o) + " — " + pythonState(o);
    }

    public static String pythonChecking(com.nekkora.client.run.Runner.PythonOption o) {
        return pythonName(o) + " — " + PY_CHECKING + "…";
    }

    private static String pythonName(com.nekkora.client.run.Runner.PythonOption o) {
        String version = o.version().isEmpty() ? "Python" : "Python " + o.version();
        return o.kind() == com.nekkora.client.run.PythonEnvironments.Kind.INSTALLED ? version : version + " (ambiente virtual " + o.folderName() + ")";
    }

    public static String pythonPartial(java.util.List<String> failedSources) {
        return failedSources.isEmpty() ? "" : " A busca foi parcial: não houve resposta de " + String.join(", ", failedSources) + ".";
    }

    public static String pythonProblem(String name, com.nekkora.client.run.PythonEnvironments.Issue issue) {
        return switch (issue) {
            case NOT_A_FOLDER -> PY_NOT_A_FOLDER;
            case NOT_AN_ENVIRONMENT -> PY_NOT_AN_ENVIRONMENT_ALONE;
            case INCOMPLETE -> "A pasta " + name + " tem pyvenv.cfg, mas falta o interpretador Python deste sistema dentro dela. O ambiente está incompleto.";
            case LINK_NOT_FOLLOWED -> "A pasta " + name + " é um link ou uma junção para outro lugar e não foi seguida. Selecione a pasta real do ambiente.";
            case NOT_VERIFIED -> "Não foi possível verificar o ambiente virtual da pasta " + name + ": o interpretador não respondeu como um ambiente desta pasta.";
        };
    }
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
    public static final String GENERATE_VERSION = "Versão declarada do pacote (x.y.z)";
    public static final String GENERATE_PYTHON = "Requisito de Python (opcional)";
    public static final String GENERATE_PYTHON_NONE = "Sem restrição de Python declarada. O ambiente ainda será conferido na execução.";
    public static final String GENERATE_PYTHON_EXAMPLES = "Exemplos: 3.12 (qualquer 3.12.x), 3.12.0 (só essa versão) ou >=3.10,<3.14. Em branco: sem restrição.";
    public static final String GENERATE_PYTHON_INVALID = "Requisito não entendido. Use, por exemplo, 3.12 (qualquer 3.12.x), 3.12.0 (só essa versão) ou "
            + ">=3.10,<3.14 — ou apague o campo para não declarar restrição.";
    public static final String GENERATE_PYTHON_FROM_MANIFEST = "Requisito lido do manifesto da pasta (nekkora-external.json). Você pode revisar ou apagar.";

    public static String generatePythonFrom(String source) {
        return "Requisito lido de " + source + ". Você pode revisar ou apagar.";
    }

    public static String generatePythonUnsupported(String declared) {
        return "O projeto declara um requisito de Python que o pacote não consegue representar (" + declared + "). O campo ficou em branco: informe um "
                + "requisito equivalente ou gere sem restrição declarada.";
    }

    /** A interpretacao do que foi digitado, antes de gerar. */
    public static String generatePythonReading(com.nekkora.client.pack.Workshop.PythonReading r) {
        return "Interpretação: " + r.meaning() + ". No manifesto: " + r.text();
    }

    /** O codigo de suporte, numa linha a parte da frase. */
    public static String supportCode(String code) {
        return "Código de suporte: " + code;
    }
    public static final String GENERATE_DEPENDENCIES = "Dependências, uma por linha (nome==versão)";
    public static final String DEPENDENCIES_NONE = "Nenhuma dependência declarada. Isso não quer dizer que o projeto não usa bibliotecas: na execução, só o que "
            + "está declarado é conferido.";
    public static final String DEPENDENCY_SOURCE = "Origem das dependências";
    public static final String DEPENDENCY_SOURCE_CHOOSE = "Escolha a origem";
    public static final String DEPENDENCY_SOURCE_MANUAL = "Edição manual";
    public static final String DEPENDENCY_SOURCES_DIFFER = "O manifesto da pasta e o arquivo de dependências declaram listas diferentes. Escolha qual delas vale; "
            + "nenhuma foi escolhida no seu lugar.";
    public static final String REQUIREMENTS_LABEL = "Arquivo de dependências (opcional)";
    public static final String REQUIREMENTS_NONE = "Nenhum arquivo selecionado";
    public static final String REQUIREMENTS_SELECT = "Selecionar requirements.txt";
    public static final String REQUIREMENTS_SELECT_TITLE = "Selecionar o arquivo de dependências";
    public static final String REQUIREMENTS_FILTER = "Arquivos de texto (*.txt)";
    public static final String REQUIREMENTS_CLEAR = "Remover seleção";
    public static final String REQUIREMENTS_DETAILS = "Linhas do arquivo de dependências";
    public static final String REQUIREMENTS_DETAILS_SHOW = "Mostrar todas as linhas do arquivo";
    public static final String REQUIREMENTS_DETAILS_HIDE = "Ocultar as linhas do arquivo";
    public static final String REQUIREMENTS_FAILED = "Não foi possível ler o arquivo de dependências.";
    public static final String REQUIREMENTS_UNRESOLVED_HINT = "O arquivo tem linhas que o pacote não representa (veja acima). Ajuste o arquivo e selecione-o de novo, "
            + "ou use a edição manual.";

    public static String dependencySourceManifest(int count) {
        return "Manifesto da pasta (" + dependencyCount(count) + ")";
    }

    public static String dependencySourceFile(com.nekkora.client.pack.Workshop.Requirements r, int count) {
        return "Arquivo de dependências (" + dependencyCount(count) + ")";
    }

    private static String dependencyCount(int count) {
        return count == 0 ? "nenhuma dependência" : count == 1 ? "1 dependência" : count + " dependências";
    }

    /** De onde veio o arquivo: reconhecido sozinho na raiz, escolhido dentro do projeto ou de fora dele. */
    public static String requirementsWhere(com.nekkora.client.pack.Workshop.Requirements r) {
        if (r.detected()) {
            return "Encontrado na raiz do projeto e selecionado: requirements.txt. Foi lido como texto; nada é instalado.";
        }
        if (r.external()) {
            return "Arquivo de fora da pasta do projeto. Foi lido como texto; ele só entra no pacote se você mandar copiar."
                    + (r.packagePath() == null ? " Não há nome livre na raiz do pacote para a cópia." : "");
        }
        return "Arquivo do projeto (" + r.packagePath() + "). Foi lido como texto; nada é instalado.";
    }

    public static String requirementsSummary(com.nekkora.client.pack.Workshop.Requirements r) {
        if (r.lines().isEmpty()) {
            return "O arquivo não declara nenhuma dependência (está vazio ou só tem comentários).";
        }
        int n = r.dependencies().size();
        String list = n == 0 ? "Nenhuma dependência com versão exata." : (n == 1 ? "1 dependência com versão exata: " : n + " dependências com versão exata: ")
                + String.join(", ", r.dependencies().subList(0, Math.min(n, 6))) + (n > 6 ? " e mais " + (n - 6) : "") + ".";
        long repeated = r.lines().stream().filter(l -> l.status().startsWith("repetida")).count();
        return list + (repeated == 0 ? "" : " " + repeated + (repeated == 1 ? " linha repetida foi usada uma vez." : " linhas repetidas foram usadas uma vez."));
    }

    /** As linhas que o pacote nao representa, uma a uma (vazio se nao ha). */
    public static String requirementsProblems(com.nekkora.client.pack.Workshop.Requirements r) {
        java.util.List<com.nekkora.client.pack.Workshop.RequirementLine> p = r.problems();
        if (p.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(p.size() == 1 ? "1 linha não pode ir para o pacote como está. " : p.size() + " linhas não podem ir para o pacote como estão. ");
        for (int i = 0; i < Math.min(p.size(), 4); i++) {
            b.append("Linha ").append(p.get(i).number()).append(" (").append(p.get(i).text()).append("): ").append(p.get(i).status()).append(". ");
        }
        if (p.size() > 4) {
            b.append("Veja as outras ").append(p.size() - 4).append(" em “").append(REQUIREMENTS_DETAILS_SHOW).append("”.");
        }
        return b.toString().trim();
    }

    public static String requirementsDetails(com.nekkora.client.pack.Workshop.Requirements r) {
        StringBuilder b = new StringBuilder();
        for (com.nekkora.client.pack.Workshop.RequirementLine l : r.lines()) {
            b.append(l.problem() ? "PROBLEMA  " : "ok        ").append("linha ").append(l.number()).append(": ").append(l.text()).append("   — ").append(l.status())
                    .append('\n');
        }
        return b.toString();
    }

    public static String requirementsCopy(String packagePath) {
        return "Copiar este arquivo para o pacote como " + packagePath;
    }
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
    public static final String GENERATED = "Pacote gerado";
    public static final String GENERATED_PATH = "Arquivo do pacote gerado";
    public static final String GENERATED_NEXT = "Para cadastrar ou publicar a automação, envie este arquivo pela Platform. Nada foi enviado.";
    public static final String GENERATING = "Gerando pacote…";
    public static final String GENERATE_CHECKING = "Conferindo os campos…";
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
        String origin = s.dependencySource() == null ? "" : switch (s.dependencySource()) {
            case MANIFEST -> " (do manifesto da pasta)";
            case FILE -> " (do arquivo de dependências)";
            case MANUAL -> s.dependencies().isEmpty() ? "" : " (edição manual)";
        };
        String deps = (s.dependencies().isEmpty() ? "nenhuma declarada" : String.join(", ", s.dependencies())) + origin
                + (s.requirementsCopiedAs() == null ? "" : " · o arquivo de dependências entra no pacote como " + s.requirementsCopiedAs());
        return "Resumo: nome " + s.name() + " · entrada " + s.entry() + " · versão declarada no pacote " + s.version()
                + " (não reservada nem publicada na Platform) · Python: " + com.nekkora.client.pack.Workshop.pythonMeaning(s.python())
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
