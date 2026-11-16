package com.nekkora.client.auth;

import java.awt.Desktop;
import java.net.URI;

/**
 * Pede ao sistema que mostre uma PASTA deste computador no gerenciador de arquivos (a pasta do pacote que a pessoa acabou
 * de gerar). So aceita endereco {@code file:}; nao abre pagina, nao envia nada e nao executa o que esta na pasta. Fica aqui
 * porque esta e a unica camada que pede algo ao sistema fora do aplicativo.
 */
public final class FolderOpener {

    private FolderOpener() {
    }

    /**
     * Pede ao sistema que abra um arquivo de TEXTO no programa padrao de texto. Quem chama ja conferiu QUAL arquivo e (o motor
     * de execucao so entrega a saida de texto de uma execucao local). So {@code .txt}: abrir outro tipo poderia executa-lo.
     */
    public static boolean openText(String path) {
        if (path == null || !path.toLowerCase(java.util.Locale.ROOT).endsWith(".txt") || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
            return false;
        }
        try {
            Desktop.getDesktop().open(new java.io.File(path));
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }

    /** {@code true} se o pedido foi feito. Sem gerenciador de arquivos, ou endereco que nao e de pasta local: {@code false}. */
    public static boolean open(URI folder) {
        if (folder == null || !"file".equalsIgnoreCase(folder.getScheme()) || !Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            return false;
        }
        try {
            Desktop.getDesktop().browse(folder);
            return true;
        } catch (java.io.IOException | RuntimeException e) {
            return false;
        }
    }
}
