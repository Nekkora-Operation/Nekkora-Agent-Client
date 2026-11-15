package com.nekkora.client.auth;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;

/**
 * Navegador padrao do sistema. O aplicativo so pede ao sistema que abra o endereco: nao escolhe navegador, nao le perfil,
 * cookie ou senha dele, e nao acompanha a aba (fecha-la nao e detectavel; a tentativa vence pelo prazo).
 */
public final class DefaultBrowser implements BrowserSignIn.Browser {

    @Override
    public void open(URI page) throws IOException {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("sem navegador padrao");
        }
        try {
            Desktop.getDesktop().browse(page);
        } catch (RuntimeException e) {
            throw new IOException(e.getClass().getSimpleName());
        }
    }
}
