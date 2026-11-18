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
    /** Enquanto o aplicativo nao executa o que a Platform solicitar (as proximas entregas trazem a execucao). */
    static final String NOT_YET = "Esta versão do aplicativo ainda não executa solicitações da Platform. Até lá, a Platform mostra este computador como "
            + "incompatível e não envia nada para ele.";

    static final String CODE_FIELD = "Código de habilitação";
    static final String CONFIRM = "Confirmo que este computador poderá receber execuções solicitadas pela Platform.";
    static final String ENABLE = "Habilitar este computador";
    static final String PAUSE = "Pausar recebimento";
    static final String RESUME = "Retomar recebimento";
    static final String DISABLE = "Desabilitar este computador";
    static final String DISABLE_CONFIRM = "Confirmar desabilitação";
    static final String DISABLE_WARNING = "Desabilitar encerra o vínculo deste computador na Platform. Para habilitar de novo será preciso outro código.";
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
            case RECEIVING -> "Este computador está habilitado e com o recebimento ligado.";
            case PAUSED -> "Recebimento pausado. Este computador não recebe novas execuções até você retomar.";
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
            case UNEXPECTED -> "A Platform respondeu de um jeito que o aplicativo não reconhece. Nada foi alterado.";
        };
    }
}
