package com.nekkora.client.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.auth.PlatformOrigin;
import com.nekkora.client.run.SafeTree;

/**
 * AMBIENTE-PYTHON-001 - o indice de laboratorio so vale com a API DESTE computador ({@code localhost} ou {@code 127.0.0.1}); em
 * qualquer outro caso, o PyPI publico. E a remocao de pastas nunca segue juncao. Sem Python.
 */
class ManagedIndexTest {

    private static final String LAB = "http://127.0.0.1:9000/simple";

    private static String chosen(String api) {
        return ManagedEnvironments.index(new PlatformOrigin(api, api).apiOnThisComputer(), LAB);
    }

    @Test
    void oIndiceDeLaboratorioSoValeComAApiEmLocalhostOu127() {
        assertEquals(LAB, chosen("https://localhost:8443"));
        assertEquals(LAB, chosen("https://127.0.0.1:8443"));
        assertEquals(ManagedEnvironments.PYPI, chosen(PlatformOrigin.PRODUCTION));
        assertEquals(ManagedEnvironments.PYPI, chosen("https://localhost.exemplo.com:8443"));
        assertEquals(ManagedEnvironments.PYPI, chosen("https://127.0.0.2:8443"));
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(false, LAB));
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(true, null));
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(true, "  "));
        assertEquals("https://localhost:8443/pypi/simple", ManagedEnvironments.index(true, "https://localhost:8443/pypi/simple"));
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(true, "http://usuario:senha@127.0.0.1:9000/simple"), "sem credencial no endereco");
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(true, "file:///c:/pacotes"));
        assertEquals(ManagedEnvironments.PYPI, ManagedEnvironments.index(true, LAB + " --trusted-host x"));
        assertTrue(new PlatformOrigin("https://localhost:8443", "https://localhost:8443").apiOnThisComputer());
        assertFalse(new PlatformOrigin(PlatformOrigin.PRODUCTION, PlatformOrigin.PRODUCTION).apiOnThisComputer());
    }

    @Test
    void aRemocaoNuncaSegueJuncaoNemLigacao(@TempDir Path tmp) throws Exception {
        Path outside = Files.createDirectories(tmp.resolve("fora"));
        Files.writeString(outside.resolve("importante.txt"), "fica");
        Path tree = Files.createDirectories(tmp.resolve("ambiente").resolve("Lib"));
        Files.writeString(tree.resolve("a.py"), "x");
        Path junction = tree.resolve("juncao");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0 && Files.exists(junction.resolve("importante.txt")), "juncao nao pode ser criada aqui");
        assertTrue(SafeTree.delete(tmp.resolve("ambiente")));
        assertFalse(Files.exists(tmp.resolve("ambiente")));
        assertEquals("fica", Files.readString(outside.resolve("importante.txt")), "o outro lado da juncao fica intacto");
        assertTrue(SafeTree.delete(tmp.resolve("nao-existe")));
    }
}
