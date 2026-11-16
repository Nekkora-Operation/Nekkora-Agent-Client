package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.remote.RemoteMachine.Status;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02 - a entrega de uma tentativa a esta maquina: perguntar, gravar no diario,
 * confirmar o recebimento e informar o desfecho, contra um canal simulado (HTTPS de verdade) e com a protecao REAL do
 * Windows. Esta versao nao executa: no ensaio do laboratorio, o desfecho e sempre a recusa CLIENT_INCOMPATIBLE.
 */
@EnabledOnOs(OS.WINDOWS)
class RemoteDeliveryTest {

    private static final String NEXT = "/agent/api/v1/remote/next";

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private Path dir;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        dir = tmp.resolve("remota");
    }

    @AfterEach
    void close() {
        gw.close();
    }

    private RemoteMachine machine(boolean trial) {
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
        m.setDeliveryTrial(trial);
        return m;
    }

    private RemoteMachine enabled(boolean trial) throws Exception {
        RemoteMachine m = machine(trial);
        m.enable(gw.code("remote"));
        m.beat();
        return m;
    }

    private Path journal() {
        return dir.resolve(AttemptJournal.FILE);
    }

    private long acks() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/ack")).count();
    }

    private List<FakeGateway.Call> states() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/state")).toList();
    }

    @Test
    void semOEnsaioAMaquinaNaoDeclaraExecucaoNemPerguntaPelaEntrega() throws Exception {
        RemoteMachine m = enabled(false);
        gw.queue();
        m.pull();
        m.beat();
        assertFalse(gw.declaredRun, "esta versao nao executa: nao declara a capacidade");
        assertTrue(gw.lastHeartbeat.contains("\"capabilities\":[\"PY_INVENTORY_1_0\"]"));
        assertEquals(0, gw.count(NEXT));
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void noEnsaioATentativaEGravadaConfirmadaERecusadaComOMotivoEODiarioSome() throws Exception {
        RemoteMachine m = enabled(true);
        assertTrue(gw.declaredRun);
        String attempt = gw.queue();
        gw.failStates = 1; // o desfecho ainda nao chega: o diario fica
        m.pull();
        assertEquals("RECEIVED", gw.attemptState, "recebida, e so isso: nada foi iniciado");
        String file = Files.readString(journal(), StandardCharsets.UTF_8);
        assertTrue(file.contains(attempt));
        assertTrue(file.contains("\"confirmada\":true"));
        assertTrue(file.contains("\"informar\":\"REFUSED\""));
        assertFalse(file.contains(gw.attemptToken), "o token da entrega nunca fica em claro no disco");
        // a presenca diz que ha uma tentativa nao resolvida
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":true"));
        // proxima consulta: so o desfecho, sem perguntar de novo
        long asked = gw.count(NEXT);
        m.pull();
        assertEquals(asked, gw.count(NEXT));
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        assertEquals(1, acks());
        assertFalse(Files.exists(journal()), "desfecho aceito pela Platform: o diario e apagado");
        FakeGateway.Call state = states().get(states().size() - 1);
        assertEquals("Bearer " + gw.credential, state.authorization(), "o canal da maquina usa a credencial da maquina");
        assertEquals("1.0", state.contract());
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":false"));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void respostaPerdidaDaConfirmacaoRepeteAMesmaConfirmacaoSemNovaEntrega() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.dropAckResponses = 2;
        m.pull();
        assertEquals("RECEIVED", gw.attemptState, "o servidor processou; a resposta se perdeu");
        assertTrue(Files.readString(journal(), StandardCharsets.UTF_8).contains("\"confirmada\":false"));
        int epoch = gw.attemptEpoch;
        gw.dropAckResponses = 0; // o cliente do JDK pode ou nao ter repetido sozinho: dai em diante as respostas chegam
        m.pull();
        assertEquals(1, gw.count(NEXT), "nao houve nova entrega");
        assertEquals(epoch, gw.attemptEpoch);
        assertEquals("REFUSED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void aplicativoFechadoDepoisDeGravarAProximaAberturaResolveODiarioMesmoSemOEnsaio() throws Exception {
        RemoteMachine first = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        first.pull();
        assertEquals("OFFERED", gw.attemptState);
        assertTrue(Files.exists(journal()), "gravada ANTES de confirmar");
        first.close();

        RemoteMachine second = machine(false);
        second.pull();
        assertEquals(1, gw.count(NEXT), "resolve a que ja tinha; nao pergunta por outra");
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void tokenQueNaoValeMaisNaoAlteraNadaEODiarioEDescartado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        String old = gw.attemptToken;
        // outra entrega tomou o lugar (token e epoca novos no servidor)
        gw.attemptToken = "Z".repeat(43);
        gw.attemptEpoch++;
        m.pull();
        assertEquals("OFFERED", gw.attemptState, "o consumidor obsoleto nao confirma nem sobrescreve");
        assertFalse(Files.exists(journal()));
        assertTrue(states().isEmpty());
        // a consulta seguinte recebe a entrega vigente e segue
        m.pull();
        assertNotEquals(old, gw.attemptToken);
        assertEquals("REFUSED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void tentativaQueAPlatformJaEncerrouNaoSegueENadaEInformado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        gw.attemptState = "CANCELLED"; // a pessoa cancelou antes de a maquina confirmar
        m.pull();
        assertEquals("CANCELLED", gw.attemptState);
        assertTrue(states().isEmpty(), "proceed=false: a maquina nao informa desfecho nenhum");
        assertFalse(Files.exists(journal()));
    }

    @Test
    void tentativaQueAPlatformNaoTemMaisDescartaODiario() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        gw.attemptId = null;
        m.pull();
        assertFalse(Files.exists(journal()));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void entregaForaDoContratoNaoEGravadaNemConfirmada() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.malformedOffer = true;
        m.pull();
        assertFalse(Files.exists(journal()));
        assertEquals(0, acks());
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void semConseguirGravarODiarioNadaEConfirmado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        Files.createDirectories(journal().resolve("ocupado")); // o lugar do arquivo esta tomado: gravar falha
        m.pull();
        assertEquals(0, acks(), "sem diario, sem confirmacao: a Platform entrega de novo");
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void pausadaNadaEEntregueEDesabilitarApagaODiario() throws Exception {
        RemoteMachine m = enabled(true);
        m.pause();
        gw.queue();
        m.pull();
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
        m.resume();
        gw.failAcks = 1;
        m.pull();
        assertTrue(Files.exists(journal()));
        m.disable();
        assertFalse(Files.exists(journal()), "o diario sai junto com o vinculo");
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertNull(gw.credential);
    }

    @Test
    void oDiarioDeOutraMaquinaOuIlegivelNaoVale() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        AttemptJournal j = new AttemptJournal(dir, new WindowsKeyring());
        assertTrue(j.load(gw.agentId).isPresent());
        assertTrue(j.load(gw.workerId).isEmpty(), "outra maquina (vinculo anterior)");
        assertFalse(j.load(gw.agentId).orElseThrow().toString().contains(gw.attemptToken));
        Files.writeString(journal(), "{\"versao\":1,", StandardCharsets.UTF_8);
        assertTrue(j.load(gw.agentId).isEmpty());
    }
}
