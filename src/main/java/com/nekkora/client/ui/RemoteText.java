package com.nekkora.client.ui;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.RemoteMachine.Status;

/** Os textos da habilitacao deste computador para execucao remota. Nenhum deles mostra codigo, credencial ou identificador. */
final class RemoteText {

    static final String MENU = "Execução remota";
    static final String TITLE = "Execução remota neste computador";

    static final String WHAT = "Habilitar permite que a Platform solicite execuções neste computador enquanto o Nekkora Agent estiver aberto. "
            + "Entrar com a sua conta não habilita o computador: é preciso um código de habilitação, criado na Platform por quem administra as máquinas.";
    static final String LOCAL_STAYS_LOCAL = "O que você executa aqui pelo botão Executar continua local. Nada dessas execuções é enviado à Platform, "
            + "com o computador habilitado ou não.";
    /** Quando o aplicativo executa o que a Platform solicitar. */
    static final String RUNS_HERE = "Recebendo novos trabalhos, este computador executa o que a Platform solicitar: uma execução por vez, com o Python "
            + "escolhido na Platform para cada automação. Nada é instalado aqui. Com a tela do Windows bloqueada, a execução não inicia. "
            + "O andamento é acompanhado na Platform.";
    /** Quando o aplicativo foi montado sem quem execute (nao declara a capacidade a Platform). */
    static final String NOT_YET = "Esta versão do aplicativo ainda não executa solicitações da Platform. Até lá, a Platform mostra este computador como "
            + "incompatível e não envia nada para ele.";

    static final String REPORTS_PYTHON = "Com o computador habilitado, o aplicativo informa à Platform quais versões de Python encontrou aqui, para que "
            + "se possa escolher onde executar. Vai só a versão de cada instalação: nenhum caminho, nome de pasta ou lista de pacotes.";
    static final String REFRESH_PYTHON = "Atualizar versões de Python";
    static final String PYTHON_REFRESHED = "As versões de Python deste computador foram informadas à Platform.";

    static String pythonReported(int count) {
        return count == 0 ? "Nenhuma instalação de Python foi encontrada neste computador; a Platform foi informada disso."
                : count == 1 ? "1 instalação de Python informada à Platform (só a versão)." : count + " instalações de Python informadas à Platform (só a versão).";
    }

    /** O que este computador mantém sem apagar e se falta espaço: dito junto do que ele está fazendo. Vazio: nada a dizer. */
    static String kept(com.nekkora.client.remote.RemoteMachine.Kept k) {
        StringBuilder b = new StringBuilder();
        if (k.folders() > 0) {
            b.append(" Pastas mantidas neste computador: ").append(k.folders()).append(k.folders() == 1 ? " execução" : " execuções").append(" (")
                    .append(String.format(java.util.Locale.forLanguageTag("pt-BR"), "%.1f MB", k.bytes() / 1_000_000.0))
                    .append("), sem prova de que os processos terminaram. Elas não são apagadas automaticamente: saem quando houver essa prova.");
        }
        if (k.spaceLow()) {
            b.append(" Pouco espaço livre no disco: este computador não aceita novos trabalhos da Platform até haver espaço. Nada do que está "
                    + "guardado é apagado para abrir espaço.");
        }
        return b.toString();
    }

    /** T06 - o que este computador está fazendo agora, e de quem é a execução. */
    static String activity(com.nekkora.client.remote.RemoteMachine.Activity a) {
        return switch (a) {
            case IDLE -> "Nenhuma execução em andamento neste computador.";
            case LOCAL_RUNNING -> "Em andamento: uma execução iniciada neste computador (botão Executar). Ela é local: nada dela é enviado à Platform. "
                    + "Enquanto ela roda, este computador não inicia execuções solicitadas pela Platform.";
            case REMOTE_PREPARING -> "Em andamento: uma execução solicitada pela Platform foi recebida e está sendo preparada (pacote, Python e dependências). "
                    + "Ela ainda não iniciou.";
            case REMOTE_RUNNING -> "Em andamento: uma execução solicitada pela Platform. O andamento, os registros e os resultados são acompanhados na Platform.";
            case REMOTE_SENDING -> "A execução solicitada pela Platform terminou neste computador. O desfecho, os registros e os resultados estão sendo "
                    + "enviados à Platform.";
            case REMOTE_FINISHING -> "Finalizando: o processo principal da execução solicitada pela Platform terminou, mas há processos da automação ainda "
                    + "ativos neste computador. Ele continua ocupado, e a limpeza espera, até eles terminarem.";
            case REMOTE_UNCERTAIN -> "Ocupação incerta: uma execução solicitada pela Platform terminou e este computador não consegue confirmar se restou "
                    + "algum processo dela. A Platform não envia novos trabalhos até haver confirmação. Sair da conta do Windows ou reiniciar o "
                    + "computador encerra essa dúvida; o resultado da automação não muda.";
            case LOCAL_FINISHING -> "Finalizando: o processo principal de uma execução iniciada neste computador (botão Executar) terminou, mas há "
                    + "processos dela ainda ativos. Ela é local: nada dela é enviado à Platform. Enquanto eles não terminarem, este computador não "
                    + "inicia outra execução, daqui nem da Platform.";
            case LOCAL_UNCERTAIN -> "Ocupação incerta: uma execução iniciada neste computador (botão Executar) terminou e não há como confirmar se "
                    + "restou algum processo dela. Ela é local: nada dela é enviado à Platform. Este computador não inicia outra execução, daqui nem "
                    + "da Platform, até haver confirmação. Sair da conta do Windows ou reiniciar o computador encerra essa dúvida.";
        };
    }

    /** T06 - para qual organização este computador está habilitado. {@code name} vazio: a organização não está na lista de quem entrou. */
    static String organization(String name) {
        return name == null || name.isBlank() ? "Habilitado para uma organização que não aparece na sua lista agora (entre com uma conta dessa organização para ver o nome)."
                : "Habilitado para a organização " + name + ".";
    }

    /**
     * O posto, na Platform, a que este computador está associado: é em nome dele que as execuções chegam aqui. A Platform o chama
     * de Worker, e é com esse nome que a pessoa o encontra lá; por isso a palavra aparece neste texto, e só nele.
     */
    static String seat(String name) {
        return "Worker na Platform: " + name + ". É para ele que as versões são autorizadas e é em nome dele que este computador recebe execuções.";
    }

    /** T06 - onde ver o que as execuções pedidas pela Platform escreveram e produziram. Este aplicativo não mostra esse conteúdo. */
    static final String SEE_ON_PLATFORM = "Registros e resultados das execuções solicitadas pela Platform são consultados na Platform, na página da versão da "
            + "automação, em \"Execução remota\". Este aplicativo não mostra esse conteúdo. O que você executa aqui fica só aqui: veja em \"Última execução\", "
            + "na preparação da execução.";

    static final String PYTHON_HERE_TITLE = "Ambientes Python informados à Platform. O código é o que a Platform mostra ao lado da versão; a pasta só aparece aqui:";

    /** T06 - uma linha por ambiente: o mesmo código curto que a Platform mostra, a versão, o tipo e onde ele está NESTE computador. */
    static String pythonHere(String code, String version, boolean virtual, String where) {
        return "código " + code + " · Python " + version + " · " + (virtual ? "ambiente virtual" : "instalação") + " · " + where;
    }

    /** Resultados de execuções remotas que ficaram neste computador porque a Platform não confirmou o recebimento. */
    static String held(int attempts, int files, long bytes, boolean outOfLimit, String removeDay, String folder) {
        return held(attempts, files, bytes, outOfLimit, removeDay, folder, 7);
    }

    private static String days(int days) {
        return days == 1 ? "1 dia" : days + " dias";
    }

    private static String runs(int n) {
        return n == 1 ? "1 execução remota" : n + " execuções remotas";
    }

    /** O mesmo, dizendo o MOTIVO da remoção: o prazo de guarda vigente, em dias. */
    static String held(int attempts, int files, long bytes, boolean outOfLimit, String removeDay, String folder, int retentionDays) {
        return held(attempts, files, bytes, java.util.Set.of(outOfLimit ? "OUT_OF_LIMIT" : "NOT_CONFIRMED"), removeDay, folder, retentionDays);
    }

    /**
     * Por que o que está guardado ainda não saiu daqui, em palavras. Cada motivo é dito uma vez; com mais de um, todos são ditos.
     * {@code SENDING}: nada impede, o envio continua. {@code OUT_OF_LIMIT}: há arquivo acima do limite. {@code LINK_ENDED} e
     * {@code NOT_AUTHORIZED}: impedimento de autorização - não há novo envio. Os demais: a Platform não confirmou.
     */
    static String why(java.util.Set<String> reasons) {
        StringBuilder b = new StringBuilder();
        if (reasons.contains("SENDING")) {
            b.append("O envio à Platform continua sendo tentado; o que ela confirmar sai deste computador. ");
        }
        if (reasons.contains("OUT_OF_LIMIT")) {
            b.append("A Platform não recebeu todos: há arquivo acima do limite de envio, que só existe aqui. ");
        }
        if (reasons.contains("LINK_ENDED")) {
            b.append("Não podem mais ser enviados: o vínculo deste computador com a Platform foi encerrado, e nada é enviado sem ele. ");
        }
        if (reasons.contains("NOT_AUTHORIZED")) {
            b.append("Não podem mais ser enviados: a Platform não reconhece mais a atribuição dessa execução a este computador. ");
        }
        if (reasons.stream().anyMatch(r -> !java.util.Set.of("SENDING", "OUT_OF_LIMIT", "LINK_ENDED", "NOT_AUTHORIZED").contains(r))) {
            b.append("A Platform não confirmou o recebimento, e não há novo envio. ");
        }
        return b.toString();
    }

    /** O mesmo, com os motivos de cada pasta guardada (ver {@link #why}). */
    static String held(int attempts, int files, long bytes, java.util.Set<String> reasons, String removeDay, String folder, int retentionDays) {
        String size = bytes < 1024 * 1024 ? Math.max(1, bytes / 1024) + " KB" : String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)).replace('.', ',') + " MB";
        return "Resultados pendentes neste computador: " + (files == 1 ? "1 arquivo" : files + " arquivos") + " (" + size + ") de "
                + (attempts == 1 ? "1 execução remota" : attempts + " execuções remotas") + ". " + why(reasons)
                + "Eles ficam guardados em " + folder + " e são removidos deste computador a partir de " + removeDay + ": é quando vence o prazo de guarda de "
                + days(retentionDays) + " sem confirmação de recebimento. Copie o que precisar antes dessa data.";
    }

    /** Registros (saída do processo) de execuções remotas que ficaram neste computador sem confirmação. Nada do conteúdo. */
    static String heldLogs(int attempts, String removeDay, int retentionDays) {
        return heldLogs(attempts, java.util.Set.of("NOT_CONFIRMED"), removeDay, retentionDays);
    }

    /** O mesmo, com os motivos (ver {@link #why}). */
    static String heldLogs(int attempts, java.util.Set<String> reasons, String removeDay, int retentionDays) {
        return "Registros pendentes neste computador: a Platform ainda não confirmou o recebimento de toda a saída do processo de " + runs(attempts) + ". "
                + why(reasons) + "O que falta fica guardado aqui, protegido, e não é mostrado nesta tela. É removido deste computador a partir de " + removeDay
                + ": é quando vence o prazo de guarda de " + days(retentionDays) + " sem confirmação de recebimento. O que já chegou continua na Platform.";
    }

    /**
     * O que este computador removeu pelo prazo de guarda sem a Platform ter confirmado o recebimento. {@code notice}: 0 = a
     * Platform foi avisada de tudo; 1 = ainda falta avisar; 2 = não há mais como avisar de tudo.
     */
    static String lost(int resultRuns, int resultFiles, int logRuns, int logLines, String lastDay, int notice) {
        String results = resultRuns == 0 ? "" : (resultFiles == 1 ? "1 arquivo de resultado" : resultFiles + " arquivos de resultado") + " de " + runs(resultRuns);
        String logs = logRuns == 0 ? "" : "registros (" + (logLines == 1 ? "1 linha capturada" : logLines + " linhas capturadas") + ") de " + runs(logRuns);
        return "Removido deste computador pelo prazo de guarda, sem confirmação de recebimento pela Platform: " + results
                + (results.isEmpty() || logs.isEmpty() ? "" : " e ") + logs + ". Última remoção em " + lastDay
                + ". Esse conteúdo não foi entregue por inteiro e não pode mais ser recuperado daqui. "
                + (notice == 0 ? "A Platform foi avisada."
                        : notice == 1 ? "A Platform ainda não foi avisada de tudo: o aviso é tentado de novo a cada hora, com este computador habilitado."
                                : "Não foi possível avisar a Platform de tudo: o vínculo deste computador foi encerrado, ou ela não reconhece mais a atribuição "
                                        + "dessas execuções.");
    }

    static final String CODE_FIELD = "Código de habilitação";
    static final String CONFIRM = "Confirmo que este computador poderá receber execuções solicitadas pela Platform.";
    static final String ENABLE = "Habilitar este computador";
    static final String DISABLE = "Desabilitar este computador";
    static final String DISABLE_CONFIRM = "Confirmar desabilitação";
    static final String DISABLE_WARNING = "Desabilitar encerra o vínculo deste computador na Platform. Para habilitar de novo será preciso outro código.";
    /** Com registros ou resultados ainda sendo enviados: desabilitar é um impedimento definitivo para esse envio, e isso é dito antes. */
    static final String DISABLE_WARNING_SENDING = DISABLE_WARNING + " Há registros ou resultados de execuções remotas que a Platform ainda não confirmou: "
            + "depois de desabilitar eles NÃO poderão mais ser enviados. Ficam neste computador até o prazo de guarda.";
    static final String REMOVE = "Remover deste computador";
    static final String KEEP = "Manter";
    static final String CLOSE = "Fechar";

    static final String CODE_REQUIRED = "Informe o código de habilitação.";
    static final String CONFIRM_REQUIRED = "Marque a confirmação para habilitar este computador.";
    static final String ENABLED_NOW = "Este computador foi habilitado.";
    static final String DISABLED_NOW = "Este computador foi desabilitado. A Platform não o reconhece mais.";
    static final String REMOVED_NOW = "O vínculo foi removido deste computador.";
    /** A validade em dia/mês/ano; a renovação é do aplicativo, sem ação da pessoa. */
    static String validUntil(String date) {
        return "A credencial deste computador vale até " + date + " e é renovada automaticamente perto do vencimento, enquanto o Nekkora Agent "
                + "estiver aberto. Se vencer ou for revogada, será preciso habilitar de novo.";
    }

    static final String NO_CONTACT = "Sem contato com a Platform no momento. O estado mostrado é o último conhecido.";

    private RemoteText() {
    }

    static String status(Status s) {
        return switch (s) {
            case UNAVAILABLE -> "Este computador não consegue proteger a credencial da máquina. A habilitação não está disponível aqui.";
            case NOT_ENABLED -> "Este computador não está habilitado. Ele não recebe execuções solicitadas pela Platform.";
            case RECEIVING -> "Este computador está habilitado e recebendo novos trabalhos.";
            // a tela nao oferece pausar nem retomar; o estado ainda pode vir (pausado antes desta versao): diz como sair dele
            case PAUSED -> "Novos trabalhos pausados. Este computador continua vinculado, mas não recebe novas execuções. "
                    + "Uma execução já em andamento continua até o fim. Para voltar a receber, desabilite este computador e habilite de novo com um código novo.";
            case ENDED -> "A habilitação deste computador expirou, foi revogada ou não pode mais ser usada. Remova-a para poder habilitar de novo "
                    + "com um código novo.";
        };
    }

    static String problem(Problem p) {
        return switch (p) {
            case NETWORK -> "Não foi possível falar com a Platform. Confira a conexão e tente de novo. Nada foi alterado.";
            case TLS -> "A conexão segura com a Platform não pôde ser conferida. Nada foi alterado.";
            case TIMEOUT -> "A Platform demorou para responder. Tente de novo. Nada foi alterado.";
            case SERVER -> "A Platform está com um problema no momento. Tente de novo em instantes. Nada foi alterado.";
            case RATE_LIMITED -> "Muitas tentativas em pouco tempo. Aguarde um pouco e tente de novo.";
            case CODE_REJECTED -> "Código inválido, vencido ou já usado. Peça um código novo a quem administra as máquinas.";
            case CODE_NOT_REMOTE -> "Este código não foi criado para habilitar execução remota. Peça um código de habilitação a quem administra as máquinas.";
            case CODE_FOR_RENEWAL -> "Este é um código de renovação, não de habilitação. Peça um código de habilitação a quem administra as máquinas.";
            case FEATURE_OFF -> "A execução remota não está disponível nesta Platform. Nada foi alterado.";
            case SEAT_TAKEN -> "O posto de trabalho deste código já tem uma máquina. Peça um código novo a quem administra as máquinas.";
            case LINK_ENDED -> "A Platform encerrou o vínculo deste computador. Remova-o para poder habilitar de novo com um código novo.";
            case NOT_ENABLED -> "Este computador não está habilitado na Platform.";
            case STORAGE -> "Este computador não conseguiu guardar a credencial da máquina com proteção. Nada ficou habilitado.";
            case RENEWAL_NOT_DUE -> "A credencial deste computador ainda não precisa ser renovada.";
            case ALREADY_ENABLED -> "Este computador já tem um vínculo. Desabilite ou remova o vínculo atual antes de habilitar de novo.";
            case ATTEMPT_ACTIVE -> "Há uma execução solicitada pela Platform em andamento neste computador, ou com o desfecho ainda por informar. "
                    + "Desabilitar agora a deixaria sem desfecho. Espere terminar, ou peça o cancelamento na Platform, e desabilite depois. "
                    + "Nada foi alterado.";
            // da entrega de uma tentativa: nunca chegam a uma acao da pessoa nesta tela
            case ATTEMPT_STALE, ATTEMPT_GONE, ATTEMPT_TRANSITION, PACKAGE_UNAVAILABLE, RESULT_CONFLICT, LOG_CONFLICT, LOG_LIMIT, RESULT_LIMIT, UNEXPECTED -> "A Platform respondeu de um jeito que o aplicativo não reconhece. Nada foi alterado.";
        };
    }
}
