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

    /** Os botoes VISIVEIS do dialogo cujo texto fala em pausar, retomar ou voltar a receber (esperado: nenhum). */
    private java.util.List<String> pauseControls(RemotePanel p) throws Exception {
        return read(() -> {
            java.util.List<String> found = new java.util.ArrayList<>();
            java.util.ArrayDeque<Component> todo = new java.util.ArrayDeque<>(java.util.List.of(p));
            while (!todo.isEmpty()) {
                Component c = todo.pop();
                if (c instanceof AbstractButton b && b.isShowing() && b.getText() != null && b.getText().toLowerCase().matches(".*(paus|retom|voltar a receber).*")) {
                    found.add(b.getText());
                }
                if (c instanceof java.awt.Container k) {
                    todo.addAll(java.util.List.of(k.getComponents()));
                }
            }
            return found;
        });
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
    void habilitarEDesabilitarPeloDialogoModalSemPausarNemRetomarNaTela() throws Exception {
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        assertEquals(RemoteText.status(Status.NOT_ENABLED), read(() -> p.statusNotice().text()));
        assertFalse(read(() -> p.disableButton().isShowing()));

        // confirmacao desmarcada: o botao de habilitar fica DESABILITADO, com ou sem codigo digitado
        assertTrue(read(() -> p.enableButton().isShowing()));
        assertFalse(read(() -> p.enableButton().isEnabled()), "sem a confirmacao marcada o botao nao pode ser clicado");
        String plain = gw.code("plain");
        type(p, plain, false);
        assertFalse(read(() -> p.enableButton().isEnabled()), "codigo digitado nao basta: falta a confirmacao");
        // a validacao continua na logica: Enter no campo do codigo, sem a confirmacao, e recusado e nada e enviado
        edt(() -> p.codeField().postActionEvent());
        assertEquals(RemoteText.CONFIRM_REQUIRED, read(() -> p.resultNotice().text()));
        assertFalse(read(p::busy));
        assertEquals(0, gw.calls.size(), "sem a confirmacao explicita, o codigo nao sai deste computador");
        // marcar habilita o botao; desmarcar o desabilita de novo
        edt(() -> p.confirmBox().doClick(0));
        assertTrue(read(() -> p.enableButton().isEnabled()));
        edt(() -> p.confirmBox().doClick(0));
        assertFalse(read(() -> p.enableButton().isEnabled()));
        // confirmacao marcada e codigo vazio: o botao aceita o clique e a logica pede o codigo
        type(p, "", true);
        click(p.enableButton());
        assertEquals(RemoteText.CODE_REQUIRED, read(() -> p.resultNotice().text()));
        assertEquals(0, gw.calls.size());

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

        // pausar e retomar NAO estao na tela (pedido do aceite); o mecanismo interno continua e a tela mostra o estado que vier
        assertEquals(java.util.List.of(), pauseControls(p), "nenhum controle de pausar ou retomar na tela, habilitado e recebendo");
        machine.pause();
        answer();
        assertTrue(gw.paused, "o mecanismo interno de pausa continua valendo no servidor");
        assertEquals(RemoteText.status(Status.PAUSED), read(() -> p.statusNotice().text()));
        assertTrue(read(() -> p.statusNotice().text()).contains("desabilite este computador e habilite de novo"), "pausado: a tela diz como sair");
        assertEquals(java.util.List.of(), pauseControls(p), "nenhum controle de pausar ou retomar na tela, pausado");
        assertTrue(read(() -> p.disableButton().isShowing() && p.disableButton().isEnabled()), "desabilitar continua oferecido");
        machine.resume();
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
        assertFalse(read(() -> p.enableButton().isShowing()));
        assertEquals(java.util.List.of(), pauseControls(p));
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
        // o Worker desta maquina: enquanto a Platform nao informa o nome, a tela nao inventa nada (nem mostra identificador)
        assertTrue(machine.seatName().isEmpty());
        assertFalse(read(p::organizationText).contains("Worker"));
        edt(() -> window.closeRemote());
        // a presenca traz o nome atual do Worker; caracteres de controle nao chegam a tela
        gw.workerName = "Worker financeiro";
        machine.beat();
        assertEquals("Worker  financeiro", machine.seatName().orElseThrow());
        RemotePanel named = openDialog();
        assertEquals(RemoteText.organization("") + " " + RemoteText.seat("Worker  financeiro"), read(named::organizationText));
        assertTrue(RemoteText.seat("W-01").startsWith("Worker na Platform: W-01."));
        edt(() -> window.closeRemote());
        // o nome trocado na Platform chega na presenca seguinte; desabilitado o computador, nao ha mais Worker
        gw.workerName = "Worker fiscal";
        machine.beat();
        assertEquals("Worker fiscal", machine.seatName().orElseThrow());
        machine.disable();
        assertTrue(machine.seatName().isEmpty());
    }

    /** Tela principal: o resumo separa vinculo de comunicacao, mostra organizacao, Worker e atividade, e leva aos detalhes. */
    @Test
    void oResumoDaTelaPrincipalSeparaVinculoDeConexaoEMostraOrganizacaoWorkerEAtividade() throws Exception {
        assertNull(read(() -> window.remoteSummary()), "sem a execucao remota entregue, nao ha resumo");
        edt(() -> window.setRemote(machine));
        RemoteSummary r = read(() -> window.remoteSummary());
        assertNotNull(r);
        // sem vinculo: diz isso, sem organizacao nem Worker
        assertEquals("Execução remota: este computador não está vinculado à Platform", read(r::stateText));
        assertEquals("Atividade: nenhuma execução em andamento.", read(r::whereText));
        // vinculado e com a presenca aceita: conectado; a organizacao fora da lista de quem entrou nao mostra identificador
        machine.enable(gw.code("remote"));
        gw.workerName = "Worker financeiro";
        machine.beat();
        settle();
        assertEquals("Execução remota: vinculado à Platform · conectado", read(r::stateText));
        assertEquals("Organização: fora da sua lista · Worker na Platform: Worker financeiro · nenhuma execução em andamento.", read(r::whereText));
        assertFalse(read(r::whereText).contains(gw.organizationId));
        // os textos curtos de cada estado: vinculo e comunicacao nunca se confundem
        assertEquals("Execução remota: vinculado à Platform (novos trabalhos pausados) · sem contato agora", RemoteText.summaryState(Status.PAUSED, false));
        assertEquals("Execução remota: vínculo encerrado · sem contato", RemoteText.summaryState(Status.ENDED, true));
        assertEquals("Organização: Fábrica Norte · Worker na Platform: ainda não informado · executando uma solicitação da Platform.",
                RemoteText.summaryWhere(true, "Fábrica Norte", "", RemoteMachine.Activity.REMOTE_RUNNING));
        assertEquals("Atividade: execução local em andamento.", RemoteText.summaryWhere(false, "", "", RemoteMachine.Activity.LOCAL_RUNNING));
        // a Platform deixa de responder: continua VINCULADO, e a comunicacao passa a "sem contato agora"
        gw.status = 503;
        machine.beat();
        settle();
        assertEquals(Status.RECEIVING, machine.view().status());
        assertEquals("Execução remota: vinculado à Platform · sem contato agora", read(r::stateText));
        gw.status = null;
        // "Ver detalhes" abre a tela de execucao remota
        // (dialogo modal, como em producao: o clique vai pela fila de eventos, sem esperar o dialogo fechar)
        assertTrue(read(() -> r.detailsButton().isShowing() && r.detailsButton().isEnabled()));
        SwingUtilities.invokeLater(() -> r.detailsButton().doClick(0));
        settle();
        assertNotNull(read(() -> window.remoteDialog()));
        assertTrue(read(() -> window.remoteDialog().isModal()));
        edt(() -> window.closeRemote());
        // o resumo e so das telas de depois da entrada: na tela de entrada ele nao aparece (o vinculo da maquina continua)
        edt(() -> window.show(com.nekkora.client.signin.SignInState.INITIAL));
        assertEquals("entrada", read(() -> window.showing()));
        assertNull(read(() -> window.remoteSummary()));
        assertEquals(Status.RECEIVING, machine.view().status());
    }

    @Test
    void semContatoATelaDizQueOEstadoEOUltimoConhecido() throws Exception {
        machine.enable(gw.code("remote"));
        gw.status = 503;
        machine.beat();
        edt(() -> window.setRemote(machine));
        RemotePanel p = openDialog();
        // VINCULADO (ha credencial guardada) e SEM CONTATO sao ditos em linhas separadas: o vinculo nao comprova contato
        assertTrue(read(p::linkText).startsWith("Vínculo: este computador está vinculado à Platform"), read(p::linkText));
        assertTrue(read(p::linkText).contains("não comprova contato agora"));
        String semContato = read(() -> p.contactNotice().text());
        assertTrue(semContato.startsWith("Comunicação: sem contato agora."), semContato);
        assertTrue(semContato.matches(".*O último contato aceito foi às [0-9]{2}:[0-9]{2}:[0-9]{2}\\. O estado mostrado é o último conhecido\\."), semContato);
        assertTrue(read(() -> p.contactNotice().isError()) || !read(() -> p.contactNotice().text()).contains("conectado"));
        // o resumo da tela principal diz o mesmo, em poucas palavras
        RemoteSummary resumo = read(() -> window.remoteSummary());
        assertNotNull(resumo, "o resumo aparece na tela principal depois da entrada");
        assertEquals("Execução remota: vinculado à Platform · sem contato agora", read(resumo::stateText));
        // a presenca volta com a janela aberta: a comunicacao passa a conectado sem a pessoa fazer nada, na tela e no resumo
        gw.status = null;
        machine.beat();
        settle();
        String conectado = read(() -> p.contactNotice().text());
        assertTrue(conectado.matches("Comunicação: conectado\\. A Platform aceitou o último contato deste computador às [0-9]{2}:[0-9]{2}:[0-9]{2}\\."), conectado);
        assertEquals("Execução remota: vinculado à Platform · conectado", read(resumo::stateText));
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
        assertTrue(pendingLogs.startsWith("Registros pendentes neste computador: a Platform ainda não confirmou o recebimento de toda a saída do processo de 1 execução remota."),
                pendingLogs);
        // uma fila sem registro do motivo (sobra): nao ha novo envio, e isso e dito
        assertTrue(pendingLogs.contains("A Platform não confirmou o recebimento, e não há novo envio."), pendingLogs);
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
        assertTrue(both.startsWith("Resultados pendentes neste computador: 3 arquivos (2 KB) de 2 execuções remotas. A Platform não confirmou o recebimento, "
                + "e não há novo envio."), both);
        assertTrue(both.contains("guardados em " + tmp.resolve("remota").resolve("tentativas") + " e são removidos deste computador a partir de 22/11/2026"), both);
        assertEquals(RemoteText.DISABLE_WARNING, read(() -> r.disableWarningNotice().text()), "sem envio em andamento, o aviso de desabilitar e o de sempre");
        edt(() -> window.closeRemote());

        // uma pasta ainda sendo enviada e outra impedida pelo fim do vinculo: cada motivo e dito, e desabilitar avisa ANTES que o envio para
        held.clear();
        held.add(new RemoteMachine.Held("11111111-1111-4111-8111-111111111111", "SENDING", attempt + java.io.File.separator + "saida", 1, 2048,
                removeAt - 7 * 24 * 3600, removeAt));
        held.add(new RemoteMachine.Held("22222222-2222-4222-8222-222222222222", "LINK_ENDED",
                tmp.resolve("remota").resolve("tentativas").resolve("22222222-2222-4222-8222-222222222222").resolve("saida").toString(), 1, 10, removeAt, removeAt + 86400));
        RemotePanel t = openDialog();
        String mixed = read(() -> t.heldNotice().text());
        assertTrue(mixed.contains("O envio à Platform continua sendo tentado; o que ela confirmar sai deste computador."), mixed);
        assertTrue(mixed.contains("Não podem mais ser enviados: o vínculo deste computador com a Platform foi encerrado, e nada é enviado sem ele."), mixed);
        assertFalse(mixed.contains("não há novo envio"), mixed);
        String warning = read(() -> t.disableWarningNotice().text());
        assertEquals(RemoteText.DISABLE_WARNING_SENDING, warning);
        assertTrue(warning.contains("depois de desabilitar eles NÃO poderão mais ser enviados"), warning);
    }

    /** Cada fim da entrega tem o seu texto; a recusa de desabilitar com execucao ativa diz o que fazer e o que nao muda. */
    @Test
    void osMotivosDaPendenciaEARecusaDeDesabilitarComExecucaoAtivaSaoDitosSemRodeio() {
        assertEquals("O envio à Platform continua sendo tentado; o que ela confirmar sai deste computador. ", RemoteText.why(java.util.Set.of("SENDING")));
        assertEquals("A Platform não recebeu todos: há arquivo acima do limite de envio, que só existe aqui. ", RemoteText.why(java.util.Set.of("OUT_OF_LIMIT")));
        assertTrue(RemoteText.why(java.util.Set.of("NOT_AUTHORIZED")).startsWith("Não podem mais ser enviados: a Platform não reconhece mais a atribuição"));
        for (String r : java.util.List.of("NOT_CONFIRMED", "CHANGED_AFTER_SEND")) {
            assertEquals("A Platform não confirmou o recebimento, e não há novo envio. ", RemoteText.why(java.util.Set.of(r)));
        }
        String logs = RemoteText.heldLogs(2, java.util.Set.of("SENDING", "LINK_ENDED"), "23/11/2026", 7);
        assertTrue(logs.contains("continua sendo tentado") && logs.contains("o vínculo deste computador com a Platform foi encerrado"), logs);
        // nenhum texto de pendencia diz que algo foi entregue
        for (String r : java.util.List.of("SENDING", "OUT_OF_LIMIT", "LINK_ENDED", "NOT_AUTHORIZED", "NOT_CONFIRMED")) {
            String text = RemoteText.why(java.util.Set.of(r)).toLowerCase();
            assertFalse(text.contains("entregue") || text.contains("recebido pela platform"), text);
        }
        String active = RemoteText.problem(com.nekkora.client.remote.MachineChannel.Problem.ATTEMPT_ACTIVE);
        assertTrue(active.contains("Desabilitar agora a deixaria sem desfecho"), active);
        assertTrue(active.contains("peça o cancelamento na Platform"), active);
        assertFalse(active.toLowerCase().contains("pausar"), "a recusa nao indica um botao que a tela nao oferece mais: " + active);
        assertTrue(active.endsWith("Nada foi alterado."), active);
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
        assertTrue(impossible.endsWith("Não foi possível avisar a Platform de tudo: o vínculo deste computador foi encerrado, ou ela não reconhece mais a "
                + "atribuição dessas execuções."), impossible);
        for (String t : java.util.List.of(sent, pending, impossible)) {
            assertFalse(t.toLowerCase().contains("entregue à platform") || t.toLowerCase().contains("recebido pela platform"), t);
        }
    }
}
