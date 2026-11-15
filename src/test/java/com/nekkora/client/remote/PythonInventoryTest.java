package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.remote.MachineChannel.Problem;
import com.nekkora.client.remote.MachineChannel.Refused;
import com.nekkora.client.remote.RemoteMachine.PythonSeen;
import com.nekkora.client.remote.RemoteMachine.Status;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T01c (parte 1) - o inventario de Python que o aplicativo publica: so identificador
 * opaco, versao, tipo e instante. O caminho de cada instalacao fica neste computador. Canal simulado (HTTPS de verdade),
 * protecao real do Windows, descoberta simulada (a real e conferida em PythonEnvironmentsTest e no executavel).
 */
@EnabledOnOs(OS.WINDOWS)
class PythonInventoryTest {

    private static final String INVENTORY = "/agent/api/v1/remote/python-environments";
    private static final String PATH_A = "computador:c:\\users\\maria da silva\\appdata\\local\\programs\\python\\python312\\python.exe";
    private static final String PATH_B = "computador:c:\\projetos secretos\\cliente-x\\.venv\\scripts\\python.exe";

    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-15T12:00:00Z");

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
    private List<PythonSeen> found;
    private AtomicInteger searches;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        dir = tmp.resolve("remota");
        clock = new TestClock();
        found = new ArrayList<>(List.of(new PythonSeen(PATH_A, false, "3.12.4"), new PythonSeen(PATH_B, true, "3.13.1")));
        searches = new AtomicInteger();
    }

    @AfterEach
    void close() {
        gw.close();
    }

    private RemoteMachine machine() {
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"), clock);
        m.setPythonFinder(() -> {
            searches.incrementAndGet();
            return List.copyOf(found);
        });
        return m;
    }

    private List<String> ids(String body) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"environmentId\":\"([^\"]*)\"").matcher(body);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }

    @Test
    void semHabilitacaoNadaEProcuradoNemEnviado() throws Exception {
        RemoteMachine m = machine();
        m.beat();
        assertEquals(Status.NOT_ENABLED, m.publishInventory().status());
        assertEquals(0, searches.get(), "computador nao habilitado: o Python nem e procurado");
        assertEquals(0, gw.calls.size());
        assertFalse(Files.exists(dir.resolve(MachineVault.SALT)));
    }

    @Test
    void aPrimeiraPresencaPublicaSoVersaoTipoEIdentificadorOpaco() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        m.beat();
        assertEquals(1, gw.count(INVENTORY));
        FakeGateway.Call call = gw.calls.stream().filter(c -> c.path().equals(INVENTORY)).findFirst().orElseThrow();
        assertEquals("Bearer " + gw.credential, call.authorization(), "com a credencial DA MAQUINA");
        String body = call.body();
        // nada do caminho, do usuario ou da pasta sai deste computador
        for (String leak : new String[] {"maria", "silva", "appdata", "python312", "projetos", "cliente-x", ".venv", "python.exe", "c:", "\\\\", "/"}) {
            assertFalse(body.toLowerCase().contains(leak), "vazou: " + leak + " em " + body);
        }
        assertTrue(body.matches("\\{\"environments\":\\[(\\{\"environmentId\":\"[A-Za-z0-9_-]{43}\",\"pythonVersion\":\"3\\.\\d+\\.\\d+\",\"kind\":\"(INSTALLATION|VIRTUAL_ENVIRONMENT)\","
                + "\"observedAt\":\"2026-11-15T12:00Z\"\\},?){2}\\]\\}"), body);
        assertTrue(body.contains("\"pythonVersion\":\"3.12.4\",\"kind\":\"INSTALLATION\""));
        assertTrue(body.contains("\"pythonVersion\":\"3.13.1\",\"kind\":\"VIRTUAL_ENVIRONMENT\""));
        assertEquals(Optional.of(2), m.view().pythonEnvironments());
        // presencas seguintes nao republicam; depois de 6 horas, sim
        m.beat();
        assertEquals(1, gw.count(INVENTORY));
        clock.now = clock.now.plusSeconds(RemoteMachine.INVENTORY_EVERY_SECONDS);
        m.beat();
        assertEquals(2, gw.count(INVENTORY));
    }

    @Test
    void oIdentificadorEEstavelNestaMaquinaEDiferenteEmOutra() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        m.publishInventory();
        List<String> first = ids(gw.inventory);
        assertEquals(2, first.size());
        assertFalse(first.get(0).equals(first.get(1)));
        // outra abertura do aplicativo, a mesma instalacao: o mesmo identificador (a escolha feita na Platform continua valendo)
        RemoteMachine again = machine();
        again.publishInventory();
        assertEquals(first, ids(gw.inventory));
        // outro computador (outro cofre, outro sal), o MESMO caminho: outro identificador - ninguem cruza maquinas pelo caminho
        try (FakeGateway other = new FakeGateway()) {
            RemoteMachine elsewhere = new RemoteMachine(new MachineVault(tmp.resolve("outra"), new WindowsKeyring()), other.channel(), Optional.of("0.1.0"), clock);
            elsewhere.setPythonFinder(() -> List.copyOf(found));
            elsewhere.enable(other.code("remote"));
            elsewhere.publishInventory();
            List<String> otherIds = ids(other.inventory);
            assertEquals(2, otherIds.size());
            assertFalse(otherIds.contains(first.get(0)) || otherIds.contains(first.get(1)));
        }
        // desabilitar remove o sal: habilitar de novo gera identificadores novos
        m.disable();
        assertFalse(Files.exists(dir.resolve(MachineVault.SALT)));
        RemoteMachine renewed = machine();
        renewed.enable(gw.code("remote"));
        renewed.publishInventory();
        assertFalse(ids(gw.inventory).contains(first.get(0)));
    }

    @Test
    void versaoForaDoFormatoRepetidosEExcessoNaoSaoEnviados() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        found.clear();
        found.add(new PythonSeen(PATH_A, false, "3.12.4"));
        found.add(new PythonSeen(PATH_A, false, "3.12.4")); // a mesma instalacao por duas fontes
        found.add(new PythonSeen("computador:c:\\py27\\python.exe", false, "2.7.18"));
        found.add(new PythonSeen("computador:c:\\estranho\\python.exe", false, "3.12.4 (C:\\estranho)"));
        found.add(new PythonSeen(null, false, "3.12.4"));
        for (int k = 0; k < 40; k++) {
            found.add(new PythonSeen("computador:c:\\muitos\\" + k + "\\python.exe", false, "3.11." + k));
        }
        assertEquals(Optional.of(32), m.publishInventory().pythonEnvironments());
        assertEquals(32, ids(gw.inventory).size());
        assertFalse(gw.inventory.contains("2.7.18") || gw.inventory.contains("estranho"));
        // nenhum Python: a lista vazia tambem e publicada (a Platform fica sabendo)
        found.clear();
        assertEquals(Optional.of(0), m.publishInventory().pythonEnvironments());
        assertEquals("{\"environments\":[]}", gw.inventory);
    }

    @Test
    void falhaNaPublicacaoNaoAfetaAPresencaEVinculoEncerradoParaTudo() throws Exception {
        RemoteMachine m = machine();
        m.enable(gw.code("remote"));
        gw.status = 503;
        assertEquals(Problem.SERVER, assertThrows(Refused.class, m::publishInventory).problem());
        assertEquals(Optional.empty(), m.view().pythonEnvironments());
        gw.status = null;
        m.beat();
        assertTrue(m.view().inContact());
        assertEquals(1, gw.count(INVENTORY), "depois de uma falha, so tenta de novo passada a espera");
        clock.now = clock.now.plusSeconds(RemoteMachine.INVENTORY_RETRY_SECONDS);
        m.beat();
        assertEquals(2, gw.count(INVENTORY));
        assertEquals(Optional.of(2), m.view().pythonEnvironments());
        // a descoberta que falha nao derruba nada
        m.setPythonFinder(() -> {
            throw new IllegalStateException("descoberta quebrou");
        });
        assertEquals(Problem.UNEXPECTED, assertThrows(Refused.class, m::publishInventory).problem());
        assertEquals(Status.RECEIVING, m.view().status());
        // vinculo encerrado na Platform: a publicacao descobre e nada mais e enviado
        m.setPythonFinder(() -> List.copyOf(found));
        gw.credential = null;
        assertEquals(Problem.LINK_ENDED, assertThrows(Refused.class, m::publishInventory).problem());
        assertEquals(Status.ENDED, m.view().status());
        int before = gw.calls.size();
        int searched = searches.get();
        m.publishInventory();
        m.beat();
        assertEquals(before, gw.calls.size());
        assertEquals(searched, searches.get());
    }
}
