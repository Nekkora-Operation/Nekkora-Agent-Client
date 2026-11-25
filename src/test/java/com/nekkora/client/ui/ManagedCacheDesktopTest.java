package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.BuildInfo;
import com.nekkora.client.nav.FakeDirectory;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.remote.FakeGateway;
import com.nekkora.client.remote.MachineVault;
import com.nekkora.client.remote.RemoteMachine;
import com.nekkora.client.remote.WindowsKeyring;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * AMBIENTE-PYTHON-001 - "Ambientes gerenciados" nos detalhes tecnicos da tela "Execucao remota", como o aplicativo a abre de
 * verdade: pelo item do menu, em dialogo MODAL, com clique nos botoes reais (conduzido por dentro do processo, sem teclado nem
 * mouse do sistema). Uso do cache, limite editavel (valor invalido recusado na tela) e "Limpar cache" so com a maquina parada.
 */
@Tag("desktop")
@EnabledOnOs(OS.WINDOWS)
class ManagedCacheDesktopTest {

    /** Quem executa e prepara, simulado: so o cache e uma resolucao que o teste segura. */
    static final class CacheWork implements RemoteMachine.Work {
        final AtomicLong bytes = new AtomicLong(5L * 1024 * 1024 + 300 * 1024);
        final AtomicInteger limit = new AtomicInteger(2048);
        final AtomicBoolean cleared = new AtomicBoolean();
        final CountDownLatch hold = new CountDownLatch(1);
        final CountDownLatch resolving = new CountDownLatch(1);

        @Override
        public boolean busy() {
            return false;
        }

        @Override
        public Optional<String> dir(String attemptId) {
            return Optional.empty();
        }

        @Override
        public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
            throw new RemoteMachine.WorkRefused("BUSY");
        }

        @Override
        public Optional<RemoteMachine.Progress> progress(String run) {
            return Optional.empty();
        }

        @Override
        public void cancel(String run) {
        }

        @Override
        public void discard(String attemptId, String run) {
        }

        @Override
        public void discardOutputs(String attemptId) {
        }

        @Override
        public int sweep(String keepAttemptId) {
            return 0;
        }

        @Override
        public List<RemoteMachine.ResultFile> results(String attemptId, String run) {
            return List.of();
        }

        @Override
        public boolean managesEnvironments() {
            return true;
        }

        @Override
        public long[] environmentCache() {
            return new long[] {bytes.get(), limit.get()};
        }

        @Override
        public boolean environmentCacheLimit(int mib) {
            limit.set(mib);
            return true;
        }

        @Override
        public boolean clearEnvironmentCache() {
            cleared.set(true);
            bytes.set(0);
            return true;
        }

        @Override
        public RemoteMachine.Resolved resolveEnvironment(String resolutionId, String pythonLocalId, List<String> dependencies,
                                                         java.util.function.BooleanSupplier cancel) {
            resolving.countDown();
            try {
                hold.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new RemoteMachine.Resolved("ENVIRONMENT_GONE", null, null, List.of(), null);
        }
    }

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private RemoteMachine machine;
    private final CacheWork work = new CacheWork();

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        machine = new RemoteMachine(new MachineVault(tmp.resolve("remota"), new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
        machine.setPythonFinder(() -> List.of(new RemoteMachine.PythonSeen("computador:c:\\python312\\python.exe", false, "3.12.4")));
        machine.setWork(work);
        SwingUtilities.invokeAndWait(() -> {
            Themes.apply(ClientTheme.SAKURA_DARK);
            flow = new SignInFlow(fake, r -> {
                if (SwingUtilities.isEventDispatchThread()) {
                    r.run();
                } else {
                    SwingUtilities.invokeLater(r);
                }
            });
            window = new ClientWindow(flow, t -> { }, () -> { }, BuildInfo.load(), background, new Workshop(tmp.resolve("dados")));
            window.setLocation(40, 40);
            window.setVisible(true);
        });
        settle();
        edt(() -> window.signInView().signInButton().doClick(0));
        edt(() -> fake.last().listener.signedIn(new FakeSession()));
        answer();
    }

    @AfterEach
    void close() throws Exception {
        work.hold.countDown();
        edt(() -> window.closeRemote());
        SwingUtilities.invokeAndWait(() -> {
            flow.close();
            window.dispose();
        });
        background.runAll();
        machine.close();
        gw.close();
    }

    private static void settle() throws Exception {
        for (int i = 0; i < 4; i++) {
            SwingUtilities.invokeAndWait(() -> {
            });
        }
    }

    private void edt(Runnable r) throws Exception {
        SwingUtilities.invokeAndWait(r);
        settle();
    }

    private <T> T read(java.util.function.Supplier<T> s) throws Exception {
        AtomicReference<T> v = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> v.set(s.get()));
        return v.get();
    }

    private void answer() throws Exception {
        for (int i = 0; i < 8; i++) {
            background.runAll();
            settle();
        }
    }

    private void click(AbstractButton b) throws Exception {
        assertTrue(read(() -> b.isShowing() && b.isEnabled()), "botao na tela e habilitado: " + read(b::getText));
        edt(() -> b.doClick(0));
    }

    private RemotePanel openDialog() throws Exception {
        JMenuItem item = read(() -> {
            JPopupMenu m = window.avatar().menu();
            for (Component c : m.getComponents()) {
                if (c instanceof JMenuItem i && RemoteText.MENU.equals(i.getText())) {
                    return i;
                }
            }
            return null;
        });
        assertNotNull(item, "o menu tem Execucao remota");
        SwingUtilities.invokeLater(() -> item.doClick(0));
        settle();
        JDialog d = read(() -> window.remoteDialog());
        assertNotNull(d);
        assertTrue(read(() -> d.isShowing() && d.isModal()), "dialogo modal, como em producao");
        return read(() -> window.remotePanel());
    }

    @Test
    void oCacheApareceNosDetalhesComLimiteEditavelELimparSoComAMaquinaParada() throws Exception {
        machine.enable(gw.code("remote"));
        machine.beat();
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        // o cache e medido fora da linha da tela; a parte aparece nos detalhes tecnicos, recolhidos por padrao
        answer();
        assertTrue(read(p::environmentSectionVisible));
        assertFalse(read(() -> p.clearCacheButton().isShowing()), "recolhido: nada dos detalhes na tela");
        click(p.detailsToggle());
        assertEquals(RemoteText.environmentCache(work.bytes.get(), 2048, true), read(p::cacheUsageText));
        assertTrue(read(p::cacheUsageText).contains("5,3 MiB em uso, de um limite de 2048 MiB"), read(p::cacheUsageText));
        assertEquals("2048", read(() -> p.cacheLimitField().getText()));

        // limite invalido: dito na tela, nada gravado
        edt(() -> p.cacheLimitField().setText("vinte"));
        click(p.saveLimitButton());
        assertEquals(RemoteText.ENV_LIMIT_INVALID, read(() -> p.resultNotice().text()));
        edt(() -> p.cacheLimitField().setText("20481"));
        click(p.saveLimitButton());
        assertEquals(RemoteText.ENV_LIMIT_INVALID, read(() -> p.resultNotice().text()));
        assertEquals(2048, work.limit.get());
        // limite valido: gravado, e a tela mostra o novo
        edt(() -> p.cacheLimitField().setText("512"));
        click(p.saveLimitButton());
        assertTrue(read(p::busy));
        answer();
        assertEquals(512, work.limit.get());
        assertEquals(RemoteText.ENV_LIMIT_SAVED, read(() -> p.resultNotice().text()));
        assertTrue(read(p::cacheUsageText).contains("limite de 512 MiB"), read(p::cacheUsageText));
        assertEquals("512", read(() -> p.cacheLimitField().getText()));

        // com uma resolucao em andamento a maquina nao esta parada: "Limpar cache" fica desabilitado e a tela diz por que
        java.util.regex.Matcher published = java.util.regex.Pattern.compile("\"environmentId\":\"([A-Za-z0-9_-]{43})\"").matcher(gw.inventory);
        assertTrue(published.find());
        gw.environmentResolution = "{\"resolutionId\":\"" + java.util.UUID.randomUUID() + "\",\"pythonEnvironmentId\":\"" + published.group(1)
                + "\",\"dependencies\":[],\"index\":\"PYPI\"}";
        machine.pull();
        assertTrue(work.resolving.await(20, TimeUnit.SECONDS));
        machine.refreshEnvironmentCache();
        settle();
        assertFalse(read(() -> p.clearCacheButton().isEnabled()), "so com a maquina parada");
        assertTrue(read(p::cacheUsageText).contains("Há um ambiente em preparo agora"), read(p::cacheUsageText));
        assertFalse(machine.clearEnvironmentCache(), "a acao confere de novo: nada e apagado com o executor ocupado");
        assertFalse(work.cleared.get());
        // a resolucao termina: o botao volta e limpa
        work.hold.countDown();
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!machine.environmentsIdle() && System.nanoTime() < end) {
            Thread.sleep(20);
        }
        machine.refreshEnvironmentCache();
        settle();
        click(p.clearCacheButton());
        answer();
        assertTrue(work.cleared.get());
        assertEquals(RemoteText.ENV_CLEARED, read(() -> p.resultNotice().text()));
        assertTrue(read(p::cacheUsageText).startsWith("Cache de pacotes: 0,0 MiB em uso"), read(p::cacheUsageText));
    }
}
