package com.nekkora.client;

import java.io.IOException;
import java.util.Optional;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import com.formdev.flatlaf.FlatLaf;
import com.nekkora.client.auth.BrowserSignIn;
import com.nekkora.client.auth.DefaultBrowser;
import com.nekkora.client.auth.PlatformApi;
import com.nekkora.client.auth.PlatformOrigin;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.storage.ClientDataDir;
import com.nekkora.client.storage.ClientPreferences;
import com.nekkora.client.storage.InstanceLock;
import com.nekkora.client.ui.ClientWindow;
import com.nekkora.client.ui.SignInText;
import com.nekkora.client.ui.Themes;

/**
 * Entrada do Nekkora Agent (Client). Abre uma janela, com a tela de entrada. Nao inicia tarefas, automacoes, pareamento
 * nem o Nekkora Agent; nao le o perfil dele. Do disco, so a propria pasta de dados, e nela so a preferencia de tema.
 */
public final class ClientMain {

    private ClientMain() {
    }

    public static void main(String[] args) {
        ClientDataDir data = ClientDataDir.resolve();
        Optional<InstanceLock> lock;
        try {
            data.create();
            lock = InstanceLock.acquire(data.lockFile());
        } catch (IOException e) {
            lock = Optional.empty();
        }
        ClientPreferences preferences = new ClientPreferences(data.preferencesFile());
        Optional<InstanceLock> held = lock;
        SwingUtilities.invokeLater(() -> {
            FlatLaf.setUseNativeWindowDecorations(true);
            Themes.apply(preferences.theme());
            if (held.isEmpty()) {
                JOptionPane.showMessageDialog(null, SignInText.ALREADY_RUNNING, SignInText.APP_NAME, JOptionPane.INFORMATION_MESSAGE);
                System.exit(0);
                return;
            }
            PlatformOrigin origin;
            try {
                origin = PlatformOrigin.resolve();
            } catch (IllegalStateException e) {
                // endereco da Platform fora do permitido: o aplicativo nao abre apontando para outro lugar
                JOptionPane.showMessageDialog(null, SignInText.BAD_CONFIGURATION, SignInText.APP_NAME, JOptionPane.ERROR_MESSAGE);
                System.exit(2);
                return;
            }
            BrowserSignIn signIn = new BrowserSignIn(origin, new PlatformApi(origin), new DefaultBrowser(), BrowserSignIn.Timing.DEFAULT);
            SignInFlow flow = new SignInFlow(signIn, SwingUtilities::invokeLater);
            // area local de pacotes, na pasta de dados DESTE aplicativo (nunca a do Nekkora Agent administrativo)
            Workshop workshop = new Workshop(data.root());
            java.util.concurrent.ExecutorService background = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "consultas");
                t.setDaemon(true);
                return t;
            });
            ClientWindow window = new ClientWindow(flow, preferences::saveTheme, () -> {
                // fechar: a tentativa em curso e cancelada e a sessao encerrada na Platform; nada fica para a proxima abertura
                flow.close();
                signIn.close();
                held.get().close();
                System.exit(0);
            }, BuildInfo.load(), background, workshop);
            Runtime.getRuntime().addShutdownHook(new Thread(flow::close, "encerramento"));
            window.setVisible(true);
        });
    }
}
