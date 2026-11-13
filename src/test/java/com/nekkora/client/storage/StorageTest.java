package com.nekkora.client.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.theme.ClientTheme;

/** Pasta de dados propria, preferencia so de tema e instancia unica do Client. */
class StorageTest {

    @TempDir
    Path tmp;

    private static Properties props(String... kv) {
        Properties p = new Properties();
        for (int i = 0; i < kv.length; i += 2) {
            p.setProperty(kv[i], kv[i + 1]);
        }
        return p;
    }

    @Test
    void pastaPadraoEDoClientENuncaADoAgent() {
        Path local = tmp.resolve("Local");
        ClientDataDir d = ClientDataDir.resolve(props(), Map.of("LOCALAPPDATA", local.toString()));
        assertEquals(local.resolve("Nekkora").resolve("AgentClient"), d.root());
        assertEquals("preferencias.properties", d.preferencesFile().getFileName().toString());
        assertEquals("client.lock", d.lockFile().getFileName().toString());
        assertFalse(d.root().startsWith(local.resolve("Nekkora").resolve("Agent")));
    }

    @Test
    void pastaDoAgentERecusadaMesmoSePedida() {
        Path local = tmp.resolve("Local");
        Path agent = local.resolve("Nekkora").resolve("Agent");
        for (String pedido : List.of(agent.toString(), agent.resolve("config").toString(), agent.resolve("..").resolve("Agent").toString())) {
            assertThrows(IllegalStateException.class,
                    () -> ClientDataDir.resolve(props(ClientDataDir.PROPERTY, pedido), Map.of("LOCALAPPDATA", local.toString())), pedido);
            assertThrows(IllegalStateException.class,
                    () -> ClientDataDir.resolve(props(), Map.of("LOCALAPPDATA", local.toString(), ClientDataDir.ENVIRONMENT, pedido)), pedido);
        }
        // "AgentClient" comeca com "Agent" no nome, mas e outra pasta
        assertEquals(local.resolve("Nekkora").resolve("AgentClient"),
                ClientDataDir.resolve(props(), Map.of("LOCALAPPDATA", local.toString())).root());
    }

    @Test
    void propriedadeTemPrecedenciaSobreAVariavel() {
        Path a = tmp.resolve("a");
        Path b = tmp.resolve("b");
        Map<String, String> env = Map.of("LOCALAPPDATA", tmp.toString(), ClientDataDir.ENVIRONMENT, b.toString());
        assertEquals(a.toAbsolutePath(), ClientDataDir.resolve(props(ClientDataDir.PROPERTY, a.toString()), env).root());
        assertEquals(b.toAbsolutePath(), ClientDataDir.resolve(props(), env).root());
    }

    @Test
    void primeiraAberturaESakuraDark() {
        assertEquals(ClientTheme.SAKURA_DARK, new ClientPreferences(tmp.resolve("nao-existe.properties")).theme());
    }

    @Test
    void gravaSoOTema() throws Exception {
        Path file = tmp.resolve("dados").resolve("preferencias.properties");
        ClientPreferences p = new ClientPreferences(file);
        assertTrue(p.saveTheme(ClientTheme.NEKKORA_LIGHT));
        assertEquals(ClientTheme.NEKKORA_LIGHT, new ClientPreferences(file).theme());
        assertTrue(p.saveTheme(ClientTheme.SAKURA_DARK));
        assertEquals(ClientTheme.SAKURA_DARK, new ClientPreferences(file).theme());

        Properties lido = new Properties();
        lido.load(Files.newBufferedReader(file, StandardCharsets.UTF_8));
        assertEquals(java.util.Set.of("tema"), lido.stringPropertyNames(), "nada alem da preferencia visual");
        try (Stream<Path> s = Files.list(file.getParent())) {
            assertEquals(List.of("preferencias.properties"), s.map(x -> x.getFileName().toString()).toList(), "sem arquivo temporario nem de sessao");
        }
    }

    @Test
    void arquivoInvalidoOuTemaDesconhecidoCaiNoPadrao() throws Exception {
        Path file = tmp.resolve("preferencias.properties");
        Files.writeString(file, "tema=midnight-blue\n");
        assertEquals(ClientTheme.SAKURA_DARK, new ClientPreferences(file).theme());
        Files.writeString(file, "tema=\\u00zz\n");
        assertEquals(ClientTheme.SAKURA_DARK, new ClientPreferences(file).theme());
        Files.write(file, new byte[] {0, 1, 2, (byte) 0xff});
        assertEquals(ClientTheme.SAKURA_DARK, new ClientPreferences(file).theme());
    }

    @Test
    void instanciaUnica() throws Exception {
        Path lock = tmp.resolve("dados").resolve("client.lock");
        Optional<InstanceLock> primeira = InstanceLock.acquire(lock);
        assertTrue(primeira.isPresent());
        assertTrue(InstanceLock.acquire(lock).isEmpty(), "segunda instancia nao obtem a trava");
        primeira.get().close();
        Optional<InstanceLock> depois = InstanceLock.acquire(lock);
        assertTrue(depois.isPresent(), "liberada ao fechar");
        depois.get().close();
    }
}
