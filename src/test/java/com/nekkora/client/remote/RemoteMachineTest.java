package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;
import com.nekkora.client.remote.RemoteMachine.Status;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01a - a habilitacao deste computador, contra um canal da maquina simulado (HTTPS de
 * verdade) e com a protecao REAL do Windows (DPAPI do usuario que roda os testes; nada sai da pasta temporaria).
 */
@EnabledOnOs(OS.WINDOWS)
class RemoteMachineTest {

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
    void close() throws Exception {
        gw.close();
    }

    private RemoteMachine machine() {
        return new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
    }

    private static Problem refused(org.junit.jupiter.api.function.Executable e) {
        return assertThrows(Refused.class, e).problem();
    }

    private String file() throws IOException {
        return Files.readString(dir.resolve(MachineVault.FILE), StandardCharsets.UTF_8);
    }

    @Test
    void semCodigoNadaEEnviadoENadaEGuardado() throws Exception {
        RemoteMachine m = machine();
        assertEquals(Status.NOT_ENABLED, m.view().status());
        m.beat();
        assertEquals(Problem.NOT_ENABLED, refused(m::pause));
        assertEquals(Status.NOT_ENABLED, m.disable().status());
        // codigo fora do formato: recusado aqui mesmo, sem rede
        for (String bad : new String[] {null, "", "   ", "nkp1.abc", "nka1." + gw.organizationId + "." + gw.credentialId + "." + "A".repeat(43)}) {
            assertEquals(Problem.CODE_REJECTED, refused(() -> m.enable(bad)));
        }
        assertEquals(0, gw.calls.size(), "nenhuma chamada sem um vinculo e sem um codigo valido");
        assertFalse(Files.exists(dir));
    }

    @Test
    void habilitarDeclaraAConfirmacaoDaMaquinaEGuardaOSegredoProtegido() throws Exception {
        RemoteMachine m = machine();
        AtomicInteger changes = new AtomicInteger();
        m.addListener(changes::incrementAndGet);
        String code = gw.code("remote");
        assertEquals(Status.RECEIVING, m.enable("  " + code + "\n").status());
        assertTrue(m.view().inContact());
        assertTrue(changes.get() >= 1);

        FakeGateway.Call call = gw.calls.get(0);
        assertEquals("/agent/api/v1/enrollments/redeem", call.path());
        assertEquals("pair-remote", call.intent(), "a maquina declara que a confirmacao foi feita nela");
        assertEquals("1.0", call.contract());
        assertEquals(null, call.authorization(), "o resgate nao leva credencial nem token de pessoa");
        assertTrue(call.body().contains("\"agentVersion\":\"0.1.0+client\""));

        // no disco: a identidade e o segredo PROTEGIDO; nem o segredo, nem a credencial, nem o codigo em texto claro
        String secret = gw.credential.substring(gw.credential.lastIndexOf('.') + 1);
        String stored = file();
        assertFalse(stored.contains(secret));
        assertFalse(stored.contains("nka1."));
        assertFalse(stored.contains(code));
        assertTrue(stored.contains(gw.agentId));
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE + ".novo")));
        MachineVault.Link link = new MachineVault(dir, new WindowsKeyring()).load().orElseThrow();
        assertEquals(gw.credential, link.credential());
        assertFalse(link.toString().contains(secret));

        // outra abertura do aplicativo: o vinculo continua, sem novo codigo; a presenca usa a credencial DA MAQUINA
        RemoteMachine again = machine();
        assertEquals(Status.RECEIVING, again.view().status());
        assertFalse(again.view().inContact(), "antes da primeira presenca nao ha contato");
        again.beat();
        assertTrue(again.view().inContact());
        FakeGateway.Call hb = gw.calls.get(gw.calls.size() - 1);
        assertEquals("/agent/api/v1/heartbeat", hb.path());
        assertEquals("Bearer " + gw.credential, hb.authorization());
        assertTrue(hb.body().contains("\"capabilities\":[\"PY_INVENTORY_1_0\"]"), "esta versao declara so o inventario: nenhuma capacidade de execucao remota");
        assertFalse(hb.body().contains("REMOTE_RUN"));
        // ja habilitado: outro codigo nao troca o vinculo
        assertEquals(Problem.ALREADY_ENABLED, refused(() -> again.enable(gw.code("remote"))));
    }

    @Test
    void cadaRecusaDoCodigoTemSeuMotivoENadaFicaGuardado() throws Exception {
        RemoteMachine m = machine();
        assertEquals(Problem.CODE_NOT_REMOTE, refused(() -> m.enable(gw.code("plain"))));
        assertEquals(Problem.CODE_FOR_RENEWAL, refused(() -> m.enable(gw.code("rotation"))));
        assertEquals(Problem.CODE_REJECTED, refused(() -> m.enable("nkp1." + gw.organizationId + "." + gw.agentId + "." + "B".repeat(43))));
        gw.featureOn = false;
        String remote = gw.code("remote");
        assertEquals(Problem.FEATURE_OFF, refused(() -> m.enable(remote)));
        gw.featureOn = true;
        gw.workerTaken = true;
        assertEquals(Problem.SEAT_TAKEN, refused(() -> m.enable(remote)));
        gw.workerTaken = false;
        // PLATFORM-LICENCIAMENTO-001: o limite de maquinas da organizacao nao e "posto ocupado"
        gw.redeemConflict = "worker_limit_reached";
        assertEquals(Problem.MACHINE_LIMIT, refused(() -> m.enable(remote)));
        gw.redeemConflict = "motivo_que_o_aplicativo_nao_conhece";
        assertEquals(Problem.SEAT_TAKEN, refused(() -> m.enable(remote)));
        gw.redeemConflict = null;
        gw.status = 503;
        assertEquals(Problem.SERVER, refused(() -> m.enable(remote)));
        gw.status = 429;
        assertEquals(Problem.RATE_LIMITED, refused(() -> m.enable(remote)));
        // resposta que nao e a do contrato nunca vira habilitacao
        gw.status = 200;
        gw.body = "{\"ok\":true}";
        assertEquals(Problem.UNEXPECTED, refused(() -> m.enable(remote)));
        gw.contentType = "text/html";
        gw.body = "<html>";
        assertEquals(Problem.UNEXPECTED, refused(() -> m.enable(remote)));
        gw.status = null;
        gw.body = null;
        gw.contentType = "application/json";
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));
        // um pareamento comum aceito pelo servidor (sem remoteExecution) NAO e tratado como habilitacao
        gw.codes.put(remote, "plain-accepted");
        assertEquals(Problem.UNEXPECTED, refused(() -> m.enable(remote)));
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));
    }

    @Test
    void respostaPerdidaNoResgateRepeteOMesmoSegredo() throws Exception {
        RemoteMachine m = machine();
        String code = gw.code("remote");
        gw.dropRedeemResponse = true;
        assertEquals(Problem.NETWORK, refused(() -> m.enable(code)));
        assertEquals(Status.NOT_ENABLED, m.view().status());
        // a pessoa tenta de novo com o mesmo codigo: o servidor ja o consumiu, e so o MESMO segredo o reabre
        assertEquals(Status.RECEIVING, m.enable(code).status());
        assertEquals(2, gw.count("/agent/api/v1/enrollments/redeem"));
        m.beat();
        assertTrue(m.view().inContact());
    }

    @Test
    void pausarERetomarValemNoServidorEFicamComoUltimoConhecido() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        assertEquals(Status.PAUSED, m.pause().status());
        assertTrue(gw.paused);
        assertTrue(file().contains("\"pausado\":true"));
        assertEquals(Status.PAUSED, machine().view().status(), "o ultimo recebimento conhecido volta na proxima abertura");
        assertEquals(Status.RECEIVING, m.resume().status());
        assertFalse(gw.paused);
        // o servidor e a referencia: se o recebimento mudou la, a presenca traz
        gw.paused = true;
        m.beat();
        assertEquals(Status.PAUSED, m.view().status());
        // sem contato: nada muda e a tela sabe que o estado e o ultimo conhecido
        gw.status = 503;
        assertEquals(Problem.SERVER, refused(m::resume));
        assertEquals(Status.PAUSED, m.view().status());
        assertFalse(m.view().inContact());
    }

    @Test
    void desabilitarEncerraNaPlatformERemoveDaqui() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        String credential = gw.credential;
        // sem contato: nada e removido
        gw.status = 503;
        assertEquals(Problem.SERVER, refused(m::disable));
        assertEquals(Status.RECEIVING, m.view().status());
        assertTrue(Files.exists(dir.resolve(MachineVault.FILE)));
        gw.status = null;
        assertEquals(Status.NOT_ENABLED, m.disable().status());
        assertEquals(null, gw.credential, "o vinculo foi encerrado na Platform");
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));
        java.util.List<String> operations = gw.calls.stream().filter(c -> c.path().equals("/agent/api/v1/link/unlink"))
                .map(c -> c.body().replaceAll(".*\\\"operationId\\\":\\\"([0-9a-f-]{36})\\\".*", "$1")).toList();
        assertEquals(2, operations.size());
        assertEquals(operations.get(0), operations.get(1), "a nova tentativa repete a MESMA operacao");
        assertEquals(36, operations.get(0).length());
        FakeGateway.Call unlink = gw.calls.get(gw.calls.size() - 1);
        assertEquals("Bearer " + credential, unlink.authorization());
        // depois de desabilitar, nada mais e enviado
        int before = gw.calls.size();
        m.beat();
        assertEquals(before, gw.calls.size());
        assertEquals(Status.NOT_ENABLED, machine().view().status());
    }

    @Test
    void vinculoEncerradoNaPlatformSoRestaRemover() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        // quem administra revogou a maquina na Platform
        gw.credential = null;
        m.beat();
        assertEquals(Status.ENDED, m.view().status());
        assertEquals(Problem.NOT_ENABLED, refused(m::pause));
        assertEquals(Problem.ALREADY_ENABLED, refused(() -> m.enable(gw.code("remote"))));
        int before = gw.calls.size();
        m.beat();
        assertEquals(before, gw.calls.size(), "credencial recusada nao e reenviada");
        // remover e so local: a Platform nao e chamada
        assertEquals(Status.NOT_ENABLED, m.disable().status());
        assertEquals(before, gw.calls.size());
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));
        // e da para habilitar de novo, com um codigo novo
        assertEquals(Status.RECEIVING, m.enable(gw.code("remote")).status());

        // vinculada, mas a Platform nao a tem mais como habilitada
        gw.enabled = false;
        m.beat();
        assertEquals(Status.ENDED, m.view().status());
        // encerrada de outro modo durante o desabilitar: conclui a remocao local
        gw.unlinkConflict = "link_ended_by_administration";
        assertEquals(Status.NOT_ENABLED, m.disable().status());
    }

    @Test
    void aCredencialSoEUsadaNoEnderecoEmQueFoiHabilitada() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        try (FakeGateway other = new FakeGateway()) {
            RemoteMachine elsewhere = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), other.channel(), Optional.empty());
            assertEquals(Status.ENDED, elsewhere.view().status());
            elsewhere.beat();
            assertEquals(Problem.NOT_ENABLED, refused(elsewhere::pause));
            assertEquals(Status.NOT_ENABLED, elsewhere.disable().status());
            assertEquals(0, other.calls.size(), "a credencial de um endereco nunca e enviada a outro");
        }
    }

    @Test
    void arquivoAdulteradoOuDeOutroUsuarioNaoViraVinculo() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        String stored = file();
        // segredo trocado por bytes que a protecao do Windows nao reconhece
        Files.writeString(dir.resolve(MachineVault.FILE), stored.replaceAll("\"segredo\":\"[^\"]+\"", "\"segredo\":\"AAAAAAAAAAAAAAAAAAAAAA==\""));
        RemoteMachine tampered = machine();
        assertEquals(Status.ENDED, tampered.view().status());
        int before = gw.calls.size();
        tampered.beat();
        assertEquals(before, gw.calls.size());
        assertEquals(Status.NOT_ENABLED, tampered.disable().status());
        // arquivo que nao e do formato
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(MachineVault.FILE), "{\"versao\":2}");
        assertEquals(Status.ENDED, machine().view().status());
    }

    @Test
    void semProtecaoDoSistemaNaoHabilitaENaoGravaNada() throws Exception {
        Keyring none = new Keyring() {
            @Override
            public boolean available() {
                return false;
            }

            @Override
            public byte[] protect(byte[] plain) throws IOException {
                throw new IOException("x");
            }

            @Override
            public byte[] unprotect(byte[] blob) throws IOException {
                throw new IOException("x");
            }
        };
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, none), gw.channel(), Optional.of("0.1.0"));
        assertEquals(Status.UNAVAILABLE, m.view().status());
        String code = gw.code("remote");
        assertEquals(Problem.STORAGE, refused(() -> m.enable(code)));
        assertEquals(0, gw.calls.size(), "sem protecao, o codigo nem e enviado");
        assertFalse(Files.exists(dir.resolve(MachineVault.FILE)));
    }

    @Test
    void seNaoDerParaGuardarOVinculoEEncerradoNaPlatform() throws Exception {
        // a protecao funciona na conferencia e falha na hora de gravar
        Keyring flaky = new Keyring() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public byte[] protect(byte[] plain) throws IOException {
                throw new IOException("x");
            }

            @Override
            public byte[] unprotect(byte[] blob) throws IOException {
                throw new IOException("x");
            }
        };
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, flaky), gw.channel(), Optional.of("0.1.0"));
        assertEquals(Problem.STORAGE, refused(() -> m.enable(gw.code("remote"))));
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertEquals(null, gw.credential, "nao fica uma maquina habilitada que este computador nao controla");
        assertEquals(1, gw.count("/agent/api/v1/link/unlink"));
    }

    @Test
    void nenhumaFalhaCarregaCodigoOuSegredo() throws Exception {
        RemoteMachine m = machine();
        String code = gw.code("plain");
        Refused e = assertThrows(Refused.class, () -> m.enable(code));
        assertEquals("CODE_NOT_REMOTE", e.getMessage());
        assertEquals(0, e.getStackTrace().length);
        assertFalse(String.valueOf(e).contains("nkp1"));
    }
}
