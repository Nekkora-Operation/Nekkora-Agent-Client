package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
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
import com.nekkora.client.remote.RemoteMachine.Status;
import com.nekkora.client.remote.WindowsKeyring;
import com.nekkora.client.signin.FakeSignIn;
import com.nekkora.client.signin.FakeSignIn.FakeSession;
import com.nekkora.client.signin.SignInFlow;
import com.nekkora.client.theme.ClientTheme;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a - a tela da habilitacao como o aplicativo a abre de verdade: pelo item do
 * menu, em dialogo MODAL, com o clique nos botoes (conduzido por dentro do processo, sem teclado nem mouse do sistema).
 * O canal da maquina e simulado (HTTPS de verdade) e a protecao da credencial e a REAL do Windows.
 */
@Tag("desktop")
@EnabledOnOs(OS.WINDOWS)
class RemoteDesktopTest {

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private FakeSignIn fake;
    private FakeDirectory.Manual background;
    private SignInFlow flow;
    private ClientWindow window;
    private RemoteMachine machine;
    private Path vaultFile;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        fake = new FakeSignIn();
        background = new FakeDirectory.Manual();
        vaultFile = tmp.resolve("remota").resolve("maquina.json");
        machine = new RemoteMachine(new MachineVault(tmp.resolve("remota"), new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
        machine.setPythonFinder(() -> java.util.List.of(new RemoteMachine.PythonSeen("computador:c:\\python312\\python.exe", false, "3.12.4")));
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
        edt(() -> window.closeRemote());
        SwingUtilities.invokeAndWait(() -> {
            flow.close();
            window.dispose();
        });
        background.runAll();
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

    /** Clique de verdade num botao do dialogo (na tela e habilitado). */
    private void click(AbstractButton b) throws Exception {
        assertTrue(read(() -> b.isShowing() && b.isEnabled()), "botao na tela e habilitado: " + read(b::getText));
        edt(() -> b.doClick(0));
    }

    private JMenuItem menuItem() throws Exception {
        return read(() -> {
            JPopupMenu m = window.avatar().menu();
            for (Component c : m.getComponents()) {
                if (c instanceof JMenuItem i && RemoteText.MENU.equals(i.getText())) {
                    return i;
                }
            }
            return null;
        });
    }

    /** Abre pelo item do menu: o clique so retorna quando a janela modal fecha, entao ele e postado na fila. */
    private RemotePanel openDialog() throws Exception {
        JMenuItem item = menuItem();
        assertNotNull(item, "o menu tem Execucao remota");
        SwingUtilities.invokeLater(() -> item.doClick(0));
        settle();
        JDialog d = read(() -> window.remoteDialog());
        assertNotNull(d);
        assertTrue(read(() -> d.isShowing() && d.isModal()), "dialogo modal, como em producao");
        return read(() -> window.remotePanel());
    }

    private void type(RemotePanel p, String code, boolean confirm) throws Exception {
        edt(() -> {
            p.codeField().setText(code);
            if (p.confirmBox().isSelected() != confirm) {
                p.confirmBox().doClick(0);
            }
        });
    }

    @Test
    void semAAcaoEntregueOMenuNaoTemExecucaoRemota() throws Exception {
        assertNull(menuItem());
        edt(() -> window.setRemote(machine));
        assertNotNull(menuItem());
    }

    @Test
    void habilitarPausarRetomarEDesabilitarPeloDialogoModal() throws Exception {
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        assertEquals(RemoteText.status(Status.NOT_ENABLED), read(() -> p.statusNotice().text()));
        assertFalse(read(() -> p.toggleButton().isShowing() || p.disableButton().isShowing()));

        // sem codigo e sem confirmacao: nada e enviado
        click(p.enableButton());
        assertEquals(RemoteText.CODE_REQUIRED, read(() -> p.resultNotice().text()));
        String plain = gw.code("plain");
        type(p, plain, false);
        click(p.enableButton());
        assertEquals(RemoteText.CONFIRM_REQUIRED, read(() -> p.resultNotice().text()));
        assertFalse(read(p::busy));
        assertEquals(0, gw.calls.size(), "sem a confirmacao explicita, o codigo nao sai deste computador");

        // codigo de pareamento comum, com a confirmacao: recusado com o motivo; o codigo sai da tela; nada guardado
        type(p, plain, true);
        click(p.enableButton());
        assertTrue(read(p::busy));
        assertFalse(read(() -> p.enableButton().isEnabled() || p.codeField().isEnabled()), "ocupado: uma tentativa por clique");
        answer();
        assertEquals(RemoteText.problem(com.nekkora.client.remote.MachineChannel.Problem.CODE_NOT_REMOTE), read(() -> p.resultNotice().text()));
        assertTrue(read(() -> p.resultNotice().isError()));
        assertEquals("", read(() -> p.codeField().getText()));
        assertFalse(read(() -> p.confirmBox().isSelected()));
        assertEquals(Status.NOT_ENABLED, machine.view().status());
        assertFalse(Files.exists(vaultFile));

        // codigo de habilitacao + confirmacao: habilitado
        type(p, gw.code("remote"), true);
        click(p.enableButton());
        answer();
        assertEquals(Status.RECEIVING, machine.view().status());
        assertEquals(RemoteText.status(Status.RECEIVING), read(() -> p.statusNotice().text()));
        assertEquals(RemoteText.ENABLED_NOW, read(() -> p.resultNotice().text()));
        assertEquals(RemoteText.NOT_YET, read(() -> p.notYetNotice().text()), "a tela diz que esta versao ainda nao executa solicitacoes");
        assertFalse(read(() -> p.codeField().isShowing() || p.enableButton().isShowing()));
        assertEquals("", read(() -> p.codeField().getText()));
        assertTrue(Files.exists(vaultFile));
        // T01b: a tela diz ate quando a credencial vale e que a renovacao e automatica
        assertTrue(read(p::validityText).matches("A credencial deste computador vale até [0-9]{2}/02/2027 e é renovada automaticamente.*"), read(p::validityText));

        // T01c: a tela diz o que e informado sobre o Python (so a versao) e deixa atualizar
        assertTrue(read(p::reportsPythonText).contains("nenhum caminho, nome de pasta ou lista de pacotes"));
        assertEquals("", read(p::pythonReportedText), "nada foi informado ainda");
        click(p.refreshPythonButton());
        answer();
        assertEquals(RemoteText.PYTHON_REFRESHED, read(() -> p.resultNotice().text()));
        assertEquals("1 instalação de Python informada à Platform (só a versão).", read(p::pythonReportedText));
        assertFalse(gw.inventory.toLowerCase().contains("python312") || gw.inventory.contains("c:"), "o caminho nao sai deste computador");

        // pausar e retomar
        assertEquals(RemoteText.PAUSE, read(() -> p.toggleButton().getText()));
        click(p.toggleButton());
        answer();
        assertTrue(gw.paused);
        assertEquals(RemoteText.status(Status.PAUSED), read(() -> p.statusNotice().text()));
        assertEquals(RemoteText.RESUME, read(() -> p.toggleButton().getText()));
        click(p.toggleButton());
        answer();
        assertFalse(gw.paused);
        assertEquals(RemoteText.status(Status.RECEIVING), read(() -> p.statusNotice().text()));

        // trocar o tema com o dialogo aberto: o conteudo continua cabendo, sem barra de rolagem
        edt(() -> window.themeToggle().doClick(0));
        JDialog d = read(() -> window.remoteDialog());
        assertFalse(read(() -> ((JScrollPane) d.getContentPane()).getVerticalScrollBar().isVisible()), "sem rolagem depois de trocar o tema");
        edt(() -> window.themeToggle().doClick(0));

        // desabilitar pede um segundo passo; "Manter" nao envia nada
        long before = gw.calls.size();
        click(p.disableButton());
        assertTrue(read(() -> p.disableConfirmButton().isShowing() && p.keepButton().isShowing()));
        assertFalse(read(() -> p.toggleButton().isShowing()));
        click(p.keepButton());
        assertEquals(before, gw.calls.size());
        assertEquals(Status.RECEIVING, machine.view().status());
        click(p.disableButton());
        click(p.disableConfirmButton());
        answer();
        assertEquals(Status.NOT_ENABLED, machine.view().status());
        assertNull(gw.credential, "o vinculo foi encerrado na Platform");
        assertFalse(Files.exists(vaultFile));
        assertEquals(RemoteText.DISABLED_NOW, read(() -> p.resultNotice().text()));
        assertTrue(read(() -> p.enableButton().isShowing()), "volta a oferecer a habilitacao");
        assertEquals("", read(p::validityText));
        assertEquals("", read(p::pythonReportedText));
        assertFalse(read(() -> p.refreshPythonButton().isShowing()));

        // fechar pelo botao
        click(p.closeButton());
        assertNull(read(() -> window.remoteDialog()));
    }

    @Test
    void vinculoEncerradoNaPlatformSoOfereceRemover() throws Exception {
        machine.enable(gw.code("remote"));
        gw.credential = null;
        machine.beat();
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        assertEquals(RemoteText.status(Status.ENDED), read(() -> p.statusNotice().text()));
        assertFalse(read(() -> p.toggleButton().isShowing() || p.enableButton().isShowing()));
        assertEquals(RemoteText.REMOVE, read(() -> p.disableButton().getText()));
        long before = gw.calls.size();
        click(p.disableButton());
        answer();
        assertEquals(before, gw.calls.size(), "remover e so local");
        assertEquals(Status.NOT_ENABLED, machine.view().status());
        assertEquals(RemoteText.REMOVED_NOW, read(() -> p.resultNotice().text()));
        assertTrue(read(() -> p.enableButton().isShowing()));
    }

    /** T06 - o estado operacional: para qual organizacao, o que roda agora e de quem e, onde ver o conteudo, e o codigo de cada Python. */
    @Test
    void aTelaMostraOEstadoOperacionalEOCodigoDeCadaAmbienteSemMostrarConteudoDeExecucao() throws Exception {
        edt(() -> window.setRemote(machine));
        // nao habilitado e sem execucao: nada de organizacao, de atividade nem de ambientes
        RemotePanel before = openDialog();
        assertEquals("", read(before::organizationText));
        assertEquals("", read(() -> before.activityNotice().text()));
        assertEquals("", read(before::seeOnPlatformText));
        assertEquals("", read(before::pythonHereText));
        edt(() -> window.closeRemote());

        machine.enable(gw.code("remote"));
        machine.beat();
        RemotePanel p = openDialog();
        // a organizacao do vinculo nao esta na lista de quem entrou neste teste: a tela diz isso, sem mostrar identificador
        assertEquals(RemoteText.organization(""), read(p::organizationText));
        assertFalse(read(p::organizationText).contains(gw.organizationId));
        assertEquals("Habilitado para a organização Fábrica Norte.", RemoteText.organization("Fábrica Norte"));
        assertEquals(RemoteText.activity(RemoteMachine.Activity.IDLE), read(() -> p.activityNotice().text()));
        assertEquals(RemoteText.SEE_ON_PLATFORM, read(p::seeOnPlatformText));
        // o ambiente Python: o codigo que a Platform mostra, a versao, o tipo e a pasta - a pasta so aqui
        String code = machine.pythonHere().get(0).code();
        assertEquals(6, code.length());
        assertTrue(gw.inventory.contains("\"environmentId\":\"" + code), "o codigo e o comeco do identificador publicado");
        assertEquals(RemoteText.PYTHON_HERE_TITLE + "\ncódigo " + code + " · Python 3.12.4 · instalação · c:\\python312\\python.exe", read(p::pythonHereText));
        assertFalse(gw.inventory.contains("python312"));
        // os textos de cada atividade dizem a ORIGEM; a local diz que nada vai a Platform
        assertTrue(RemoteText.activity(RemoteMachine.Activity.LOCAL_RUNNING).contains("iniciada neste computador"));
        assertTrue(RemoteText.activity(RemoteMachine.Activity.LOCAL_RUNNING).contains("nada dela é enviado à Platform"));
        for (RemoteMachine.Activity a : new RemoteMachine.Activity[] {RemoteMachine.Activity.REMOTE_PREPARING, RemoteMachine.Activity.REMOTE_RUNNING,
                RemoteMachine.Activity.REMOTE_SENDING}) {
            assertTrue(RemoteText.activity(a).contains("solicitada pela Platform"), a.name());
        }
        assertTrue(RemoteText.SEE_ON_PLATFORM.contains("Este aplicativo não mostra esse conteúdo."));
    }

    @Test
    void semContatoATelaDizQueOEstadoEOUltimoConhecido() throws Exception {
        machine.enable(gw.code("remote"));
        gw.status = 503;
        machine.beat();
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        assertEquals(RemoteText.NO_CONTACT, read(() -> p.contactNotice().text()));
        // a presenca volta com a janela aberta: o aviso some sem a pessoa fazer nada
        gw.status = null;
        machine.beat();
        settle();
        assertEquals("", read(() -> p.contactNotice().text()));
        // terminar a sessao da pessoa nao desabilita a maquina
        edt(() -> window.closeRemote());
        edt(() -> flow.close());
        assertEquals(Status.RECEIVING, machine.view().status());
        assertTrue(Files.exists(vaultFile));
    }

    @Test
    void resultadoGuardadoSemConfirmacaoApareceNaTelaComAPastaEADataDeRemocao() throws Exception {
        java.util.List<RemoteMachine.Held> held = new java.util.concurrent.CopyOnWriteArrayList<>();
        machine.setWork(new RemoteMachine.Work() {
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
            public java.util.List<RemoteMachine.ResultFile> results(String attemptId, String run) {
                return java.util.List.of();
            }

            @Override
            public java.util.List<RemoteMachine.Held> held(String exceptAttemptId) {
                return held;
            }
        });
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        assertEquals("", read(() -> p.heldNotice().text()), "sem pendencia, nada e mostrado");
        edt(() -> window.closeRemote());

        // uma tentativa com arquivo acima do limite: a tela diz quanto, onde esta e a partir de quando sai deste computador
        String attempt = tmp.resolve("remota").resolve("tentativas").resolve("11111111-1111-4111-8111-111111111111").toString();
        long removeAt = java.time.LocalDate.of(2026, 11, 22).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond();
        held.add(new RemoteMachine.Held("11111111-1111-4111-8111-111111111111", "OUT_OF_LIMIT", attempt + java.io.File.separator + "saida", 2, 3L * 1024 * 1024,
                removeAt - 7 * 24 * 3600, removeAt));
        RemotePanel q = openDialog();
        String text = read(() -> q.heldNotice().text());
        assertTrue(text.startsWith("Resultados pendentes neste computador: 2 arquivos (3,0 MB) de 1 execução remota."), text);
        assertTrue(text.contains("há arquivo acima do limite de envio"), text);
        assertTrue(text.contains("guardados em " + attempt + " e são removidos deste computador a partir de 22/11/2026"), text);
        // o MOTIVO da remocao e dito junto com a data, antes de ela acontecer
        assertTrue(text.contains("é quando vence o prazo de guarda de 7 dias sem confirmação de recebimento"), text);
        assertEquals("", read(() -> q.heldLogsNotice().text()), "sem fila guardada, nada e mostrado");
        assertEquals("", read(() -> q.lostNotice().text()), "sem perda, nada e mostrado");
        edt(() -> window.closeRemote());

        // registros guardados sem confirmacao: a tela diz quantas execucoes, a data e o motivo - e nada do conteudo. O prazo
        // configurado (3 dias) aparece nos dois avisos
        Path fila = Files.createDirectories(tmp.resolve("remota").resolve("fila"));
        Path queued = Files.write(fila.resolve("33333333-3333-4333-8333-333333333333.fila"), "conteudo protegido que a tela nao le".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        java.time.ZonedDateTime since = java.time.LocalDate.of(2026, 11, 10).atTime(9, 0).atZone(java.time.ZoneId.systemDefault());
        Files.setLastModifiedTime(queued, java.nio.file.attribute.FileTime.from(since.toInstant()));
        Files.writeString(tmp.resolve("remota").resolve(MachineVault.RETENTION_FILE), "3");
        RemotePanel logs = openDialog();
        String pendingLogs = read(() -> logs.heldLogsNotice().text());
        assertTrue(pendingLogs.startsWith("Registros pendentes neste computador: a Platform não confirmou o recebimento de toda a saída do processo de 1 execução remota."),
                pendingLogs);
        assertTrue(pendingLogs.contains("É removido deste computador a partir de 13/11/2026: é quando vence o prazo de guarda de 3 dias sem confirmação de recebimento."),
                pendingLogs);
        assertFalse(pendingLogs.contains("conteudo protegido"), "a tela nao mostra conteudo de registro remoto");
        assertTrue(read(() -> logs.heldNotice().text()).contains("prazo de guarda de 3 dias"));
        edt(() -> window.closeRemote());
        Files.delete(queued);
        Files.delete(tmp.resolve("remota").resolve(MachineVault.RETENTION_FILE));

        // duas tentativas, nenhuma por limite: a pasta mostrada e a que reune as tentativas, e a data e a da mais antiga
        held.add(new RemoteMachine.Held("22222222-2222-4222-8222-222222222222", "NOT_CONFIRMED",
                tmp.resolve("remota").resolve("tentativas").resolve("22222222-2222-4222-8222-222222222222").resolve("saida").toString(), 1, 10, removeAt, removeAt + 86400));
        held.remove(0);
        held.add(0, new RemoteMachine.Held("11111111-1111-4111-8111-111111111111", "NOT_CONFIRMED", attempt + java.io.File.separator + "saida", 2, 2048,
                removeAt - 7 * 24 * 3600, removeAt));
        RemotePanel r = openDialog();
        String both = read(() -> r.heldNotice().text());
        assertTrue(both.startsWith("Resultados pendentes neste computador: 3 arquivos (2 KB) de 2 execuções remotas. A Platform não confirmou o recebimento."), both);
        assertTrue(both.contains("guardados em " + tmp.resolve("remota").resolve("tentativas") + " e são removidos deste computador a partir de 22/11/2026"), both);
    }

    /** O que ja foi removido pelo prazo nunca e dito como entregue: o texto diz o que saiu, quando, e o que a Platform sabe. */
    @Test
    void oTextoDaPerdaPorPrazoDeGuardaDizOQueSaiuEOQueAPlatformSabe() {
        String sent = RemoteText.lost(1, 4, 1, 1008, "23/11/2026", 0);
        assertEquals("Removido deste computador pelo prazo de guarda, sem confirmação de recebimento pela Platform: 4 arquivos de resultado de 1 execução remota"
                + " e registros (1008 linhas capturadas) de 1 execução remota. Última remoção em 23/11/2026. Esse conteúdo não foi entregue por inteiro e não pode"
                + " mais ser recuperado daqui. A Platform foi avisada.", sent);
        String pending = RemoteText.lost(0, 0, 2, 1, "23/11/2026", 1);
        assertTrue(pending.contains("pela Platform: registros (1 linha capturada) de 2 execuções remotas. Última remoção"), pending);
        assertTrue(pending.endsWith("A Platform ainda não foi avisada de tudo: o aviso é tentado de novo a cada hora, com este computador habilitado."), pending);
        String impossible = RemoteText.lost(2, 1, 0, 0, "23/11/2026", 2);
        assertTrue(impossible.contains("pela Platform: 1 arquivo de resultado de 2 execuções remotas. Última remoção"), impossible);
        assertTrue(impossible.endsWith("Não foi possível avisar a Platform de tudo: ela não reconhece mais a atribuição dessas execuções."), impossible);
        for (String t : java.util.List.of(sent, pending, impossible)) {
            assertFalse(t.toLowerCase().contains("entregue à platform") || t.toLowerCase().contains("recebido pela platform"), t);
        }
    }
}
