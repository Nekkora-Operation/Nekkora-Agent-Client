package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01b - o aplicativo renova a credencial da maquina sozinho, perto do vencimento,
 * contra um canal simulado (HTTPS de verdade) e com a protecao REAL do Windows. O tempo e adiantado pelo relogio do teste.
 */
@EnabledOnOs(OS.WINDOWS)
class CredentialRenewalTest {

    private static final String RENEW = "/agent/api/v1/remote/credential/renew";
    private static final String HEARTBEAT = "/agent/api/v1/heartbeat";

    /** Relogio que o teste adianta. */
    private static final class TestClock extends Clock {
        Instant now;

        TestClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private Path dir;
    private TestClock clock;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        gw.credentialExpiresAt = "2027-02-13T12:00:00Z";
        dir = tmp.resolve("remota");
        // 90 dias antes do vencimento
        clock = new TestClock(Instant.parse("2026-11-15T12:00:00Z"));
    }

    @AfterEach
    void close() {
        gw.close();
    }

    private RemoteMachine machine() {
        return new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"), clock);
    }

    private RemoteMachine enabled() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        return m;
    }

    private List<String> paths() {
        return gw.calls.stream().map(FakeGateway.Call::path).toList();
    }

    private String file(String name) throws IOException {
        return Files.readString(dir.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void longeDoVencimentoNadaERenovado() throws Exception {
        RemoteMachine m = enabled();
        m.beat();
        clock.now = clock.now.plus(Duration.ofDays(59)); // faltam 31 dias
        m.beat();
        assertEquals(0, gw.count(RENEW));
        assertFalse(Files.exists(dir.resolve(MachineVault.PENDING)));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void naJanelaATrocaAconteceSozinhaEAPresencaJaUsaACredencialNova() throws Exception {
        RemoteMachine m = enabled();
        String old = gw.credential;
        String oldSecret = old.substring(old.lastIndexOf('.') + 1);
        clock.now = clock.now.plus(Duration.ofDays(61)); // faltam 29 dias
        m.beat();
        assertEquals(1, gw.count(RENEW));
        FakeGateway.Call renew = gw.calls.stream().filter(c -> c.path().equals(RENEW)).findFirst().orElseThrow();
        assertEquals("Bearer " + old, renew.authorization(), "a troca e pedida com a credencial ATUAL da maquina");
        assertEquals("1.0", renew.contract());
        // a presenca que vem depois ja usa a nova
        FakeGateway.Call hb = gw.calls.get(gw.calls.size() - 1);
        assertEquals(HEARTBEAT, hb.path());
        assertEquals("Bearer " + gw.credential, hb.authorization());
        assertNotEquals(old, gw.credential);
        assertEquals(Status.RECEIVING, m.view().status());
        assertTrue(m.view().inContact());
        assertEquals(Optional.of(gw.renewedExpiresAt), m.view().credentialExpiresAt());
        // no disco: a credencial nova, protegida; nada do segredo antigo nem do novo em claro; o pendente foi apagado
        String newSecret = gw.credential.substring(gw.credential.lastIndexOf('.') + 1);
        String stored = file(MachineVault.FILE);
        assertTrue(stored.contains(gw.renewedCredentialId));
        assertFalse(stored.contains(newSecret) || stored.contains(oldSecret) || stored.contains("nka1."));
        assertFalse(Files.exists(dir.resolve(MachineVault.PENDING)));
        MachineVault.Link link = new MachineVault(dir, new WindowsKeyring()).load().orElseThrow();
        assertEquals(gw.credential, link.credential());
        // a mesma maquina, o mesmo posto; so a credencial mudou
        assertEquals(gw.agentId, link.agentId());
        assertEquals(gw.workerId, link.workerId());
        // a nova acabou de nascer: nada mais e pedido
        m.beat();
        assertEquals(1, gw.count(RENEW));
        // outra abertura do aplicativo: segue com a nova
        RemoteMachine again = machine();
        again.beat();
        assertTrue(again.view().inContact());
        assertEquals(1, gw.count(RENEW));
    }

    @Test
    void oRecebimentoPausadoContinuaPausadoDepoisDaTroca() throws Exception {
        RemoteMachine m = enabled();
        m.pause();
        clock.now = clock.now.plus(Duration.ofDays(70));
        m.beat();
        assertEquals(1, gw.count(RENEW));
        assertEquals(Status.PAUSED, m.view().status());
        assertEquals(Status.PAUSED, machine().view().status());
    }

    @Test
    void respostaPerdidaATrocaERepetidaComOMesmoSegredo() throws Exception {
        RemoteMachine m = enabled();
        String old = gw.credential;
        clock.now = clock.now.plus(Duration.ofDays(61));
        gw.dropRenewResponses = 2;
        m.beat();
        gw.dropRenewResponses = 0;
        // o servidor ja trocou; o aplicativo nao sabe. O segredo novo esta guardado (protegido), e o vinculo NAO e dado por encerrado
        assertNotEquals(old, gw.credential);
        String newSecret = gw.credential.substring(gw.credential.lastIndexOf('.') + 1);
        assertTrue(Files.exists(dir.resolve(MachineVault.PENDING)));
        assertFalse(file(MachineVault.PENDING).contains(newSecret), "o segredo pendente nunca fica em claro");
        assertEquals(Status.RECEIVING, m.view().status());
        assertFalse(m.view().inContact());
        long renewsBefore = gw.count(RENEW);
        // antes da espera, a troca nao e repetida
        m.beat();
        assertEquals(renewsBefore, gw.count(RENEW));
        assertEquals(Status.RECEIVING, m.view().status(), "a credencial antiga recusada na presenca nao encerra o vinculo: ha uma troca em curso");
        // passada a espera: repete com a credencial ANTERIOR e o MESMO segredo; o servidor devolve a mesma credencial nova
        clock.now = clock.now.plusSeconds(RemoteMachine.RENEW_RETRY_FIRST_SECONDS);
        m.beat();
        assertEquals(renewsBefore + 1, gw.count(RENEW));
        List<FakeGateway.Call> renews = gw.calls.stream().filter(c -> c.path().equals(RENEW)).toList();
        for (FakeGateway.Call c : renews) {
            assertEquals("Bearer " + old, c.authorization(), "sempre com a credencial ANTERIOR");
            assertEquals(renews.get(0).body(), c.body(), "sempre o MESMO segredo novo");
        }
        assertTrue(m.view().inContact());
        assertEquals(gw.credential, new MachineVault(dir, new WindowsKeyring()).load().orElseThrow().credential());
        assertFalse(Files.exists(dir.resolve(MachineVault.PENDING)));
    }

    @Test
    void oAplicativoFechouNoMeioDaTrocaAProximaAberturaConclui() throws Exception {
        RemoteMachine m = enabled();
        String old = gw.credential;
        clock.now = clock.now.plus(Duration.ofDays(61));
        gw.dropRenewResponses = 2;
        m.beat();
        gw.dropRenewResponses = 0;
        m.close();
        // outra abertura: o disco tem a credencial antiga e o segredo pendente
        RemoteMachine again = machine();
        assertEquals(Status.RECEIVING, again.view().status());
        again.beat();
        assertTrue(again.view().inContact());
        assertEquals(gw.credential, new MachineVault(dir, new WindowsKeyring()).load().orElseThrow().credential());
        assertNotEquals(old, gw.credential);
        // pausar no meio de uma troca interrompida nao encerra o vinculo
        RemoteMachine third = enabledAgainForInterrupted();
        assertEquals(Status.RECEIVING, third.view().status());
    }

    /** Uma segunda maquina (outro cofre) com a troca interrompida, para o caso de uma acao da pessoa antes da repeticao. */
    private RemoteMachine enabledAgainForInterrupted() throws Exception {
        try (FakeGateway other = new FakeGateway()) {
            Path d2 = tmp.resolve("remota-2");
            RemoteMachine m = new RemoteMachine(new MachineVault(d2, new WindowsKeyring()), other.channel(), Optional.of("0.1.0"), clock);
            m.enable(other.code("remote"));
            other.renewalDue = true;
            clock.now = clock.now.plus(Duration.ofDays(61));
            other.dropRenewResponses = 2;
            m.beat();
            other.dropRenewResponses = 0;
            // a credencial antiga ja foi trocada no servidor: pausar recebe 401, mas ha uma troca em curso
            assertEquals(MachineChannel.Problem.LINK_ENDED, org.junit.jupiter.api.Assertions.assertThrows(MachineChannel.Refused.class, m::pause).problem());
            assertEquals(Status.RECEIVING, m.view().status(), "nao e o fim do vinculo: a repeticao da troca e que decide");
            clock.now = clock.now.plusSeconds(RemoteMachine.RENEW_RETRY_FIRST_SECONDS);
            m.beat();
            assertTrue(m.view().inContact());
            assertEquals(Status.PAUSED, m.pause().status());
            m.resume();
            return m;
        }
    }

    @Test
    void falhaPassageiraEsperaCrescenteEACredencialAtualContinuaValendo() throws Exception {
        RemoteMachine m = enabled();
        String old = gw.credential;
        clock.now = clock.now.plus(Duration.ofDays(61));
        gw.status = 503;
        m.beat();
        assertEquals(1, gw.count(RENEW));
        assertEquals(Status.RECEIVING, m.view().status());
        // dentro da espera, a renovacao nao e repetida; a presenca continua sendo tentada com a credencial atual
        gw.status = null;
        gw.renewalDue = false; // (a simulacao so volta a aceitar a troca quando o teste disser)
        m.beat();
        assertEquals(1, gw.count(RENEW));
        assertTrue(m.view().inContact());
        assertEquals(old, gw.credential);
        // a espera dobra a cada falha, ate o teto
        gw.status = 503;
        long wait = RemoteMachine.RENEW_RETRY_FIRST_SECONDS;
        for (int k = 2; k <= 9; k++) {
            clock.now = clock.now.plusSeconds(wait - 1);
            m.beat();
            assertEquals(k - 1, gw.count(RENEW), "antes do fim da espera " + wait);
            clock.now = clock.now.plusSeconds(1);
            m.beat();
            assertEquals(k, gw.count(RENEW));
            wait = Math.min(wait * 2, RemoteMachine.RENEW_RETRY_MAX_SECONDS);
        }
        assertEquals(RemoteMachine.RENEW_RETRY_MAX_SECONDS, wait);
        // a Platform volta: conclui
        gw.status = null;
        gw.renewalDue = true;
        clock.now = clock.now.plusSeconds(wait);
        m.beat();
        assertNotEquals(old, gw.credential);
        assertTrue(m.view().inContact());
    }

    @Test
    void oServidorDizQueAindaNaoEHoraOSegredoGeradoEDescartado() throws Exception {
        RemoteMachine m = enabled();
        String old = gw.credential;
        clock.now = clock.now.plus(Duration.ofDays(61));
        gw.renewalDue = false;
        m.beat();
        assertEquals(1, gw.count(RENEW));
        assertFalse(Files.exists(dir.resolve(MachineVault.PENDING)));
        assertEquals(old, gw.credential);
        assertEquals(Status.RECEIVING, m.view().status());
        assertTrue(m.view().inContact(), "a presenca segue com a credencial atual");
    }

    @Test
    void credencialVencidaOuRevogadaNuncaERenovadaSoRestaHabilitarDeNovo() throws Exception {
        RemoteMachine m = enabled();
        clock.now = clock.now.plus(Duration.ofDays(61));
        // quem administra revogou a maquina (ou a credencial venceu): o servidor nao reconhece mais a credencial
        gw.credential = null;
        m.beat();
        assertEquals(1, gw.count(RENEW));
        assertEquals(Status.ENDED, m.view().status());
        int before = gw.calls.size();
        clock.now = clock.now.plus(Duration.ofDays(1));
        m.beat();
        assertEquals(before, gw.calls.size(), "nada mais e enviado: nem renovacao, nem presenca");
        // remover apaga tambem o segredo pendente
        assertEquals(Status.NOT_ENABLED, m.disable().status());
        assertFalse(Files.exists(dir.resolve(MachineVault.PENDING)));
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));

        // maquina que a Platform nao tem mais como habilitada
        RemoteMachine other = machine();
        other.enable(gw.code("remote"));
        gw.renewalDue = true;
        gw.enabled = false;
        clock.now = clock.now.plus(Duration.ofDays(80));
        other.beat();
        assertEquals(Status.ENDED, other.view().status());
    }

    @Test
    void segredoPendenteDeOutraCredencialOuAdulteradoNaoEUsado() throws Exception {
        RemoteMachine m = enabled();
        Files.writeString(dir.resolve(MachineVault.PENDING), "{\"versao\":1,\"credencial\":\"" + gw.agentId + "\",\"segredo\":\"AAAA\"}");
        m.beat();
        assertEquals(0, gw.count(RENEW), "pendente de outra credencial: ignorado; longe do vencimento, nada e pedido");
        Files.writeString(dir.resolve(MachineVault.PENDING), "{\"versao\":1,\"credencial\":\"" + gw.credentialId + "\",\"segredo\":\"AAAAAAAAAAAAAAAAAAAAAA==\"}");
        m.beat();
        assertEquals(0, gw.count(RENEW), "pendente que a protecao do Windows nao reconhece: ignorado");
        assertTrue(m.view().inContact());
    }
}
