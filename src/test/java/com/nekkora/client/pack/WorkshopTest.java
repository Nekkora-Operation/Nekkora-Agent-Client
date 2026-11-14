package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * O que a tela pede a area local (AGENT-CLIENT-NAVEGACAO-001 T04): importar um .zip, preparar um pacote novo a partir de
 * codigo e renomear so o nome local. Tudo em pastas temporarias; nada e executado e nada vai para a rede.
 */
class WorkshopTest {

    static final UUID ORG_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ORG_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID WS_1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID WS_2 = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");

    @TempDir
    Path tmp;
    Workshop workshop;

    @BeforeEach
    void setUp() {
        workshop = new Workshop(tmp.resolve("dados"));
    }

    private String refused(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(Workshop.Refused.class, call).getMessage();
    }

    /** Conteudo (caminho relativo -> bytes) de tudo o que esta guardado, para provar que nada mudou. */
    private Map<String, String> stored() throws Exception {
        Map<String, String> out = new TreeMap<>();
        Path root = tmp.resolve("dados");
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                if (rel.endsWith("nekkora-external.json") || rel.endsWith(".py")) {
                    out.put(rel, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
                }
            }
        }
        return out;
    }

    @Test
    void importaUmPacoteProntoSoNoContextoPedido() throws Exception {
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "conciliacao", "1.2.0", "main.py");
        Workshop.Imported done = workshop.importZip(ORG_A, WS_1, zip.toString());
        assertFalse(done.alreadyPresent());
        assertEquals("conciliacao", done.item().name());
        assertEquals("1.2.0", done.item().packageVersion());
        assertTrue(done.item().fingerprint().startsWith("sha256:"));
        assertEquals(List.of(done.item()), workshop.list(ORG_A, WS_1));
        // outro ambiente da mesma organizacao, e o mesmo ambiente em outra organizacao: nada
        assertEquals(List.of(), workshop.list(ORG_A, WS_2));
        assertEquals(List.of(), workshop.list(ORG_B, WS_1));
        // de novo: nao duplica
        assertTrue(workshop.importZip(ORG_A, WS_1, zip.toString()).alreadyPresent());
        assertEquals(1, workshop.list(ORG_A, WS_1).size());
        // o mesmo pacote em outro contexto e outro item, separado
        assertFalse(workshop.importZip(ORG_B, WS_1, zip.toString()).alreadyPresent());
        assertEquals(1, workshop.list(ORG_B, WS_1).size());
        assertTrue(done.item().matches(done.item().fingerprint().toUpperCase()));
        assertTrue(done.item().matches(done.item().fingerprint().substring(7)), "com ou sem o prefixo da regra");
        assertFalse(done.item().matches(null));
        assertFalse(done.item().matches("sha256:" + "0".repeat(64)));
    }

    @Test
    void soAceitaArquivoZipEDizPorQueRecusa() throws Exception {
        Path codigo = PackFixtures.codeZip(tmp.resolve("zips"), "minimo");
        // extensao: o texto e explicito
        Path semExtensao = Files.copy(codigo, tmp.resolve("pacote.tar"));
        assertEquals("Escolha um arquivo .zip.", refused(() -> workshop.importZip(ORG_A, WS_1, semExtensao.toString())));
        assertEquals("Escolha um arquivo .zip.", refused(() -> workshop.importZip(ORG_A, WS_1, null)));
        // conteudo: a extensao nao basta
        String naoZip = refused(() -> workshop.importZip(ORG_A, WS_1, PackFixtures.notAZip(tmp.resolve("zips")).toString()));
        assertTrue(naoZip.contains("AGT-PAC-"), naoZip);
        // ZIP so de codigo, sem manifesto: nao e um pacote pronto
        String semManifesto = refused(() -> workshop.importZip(ORG_A, WS_1, codigo.toString()));
        assertTrue(semManifesto.contains("AGT-EXE-002"), semManifesto);
        // arquivo que nao existe
        assertFalse(refused(() -> workshop.importZip(ORG_A, WS_1, tmp.resolve("nao-existe.zip").toString())).isBlank());
        assertEquals(List.of(), workshop.list(ORG_A, WS_1), "nada recusado fica guardado");
    }

    @Test
    void novaAutomacaoPreparaUmPacoteLocalAPartirDoCodigo() throws Exception {
        Path codigo = PackFixtures.codeZip(tmp.resolve("zips"), "declarado");
        try (Workshop.Draft draft = workshop.open(codigo.toString())) {
            assertTrue(draft.code());
            assertEquals(List.of(), draft.blockers());
            assertTrue(draft.pythonFiles().contains("app.py"));
            Workshop.Imported done = workshop.build(ORG_A, WS_1, draft, "relatorio", "1.0.0", "app.py", draft.python().orElse(">=3.10,<3.14"), draft.dependencies(), true);
            assertEquals("relatorio", done.item().name());
            assertEquals("1.0.0", done.item().packageVersion());
            assertEquals(List.of(done.item()), workshop.list(ORG_A, WS_1));
            assertEquals(List.of(), workshop.list(ORG_A, WS_2), "preparado so no contexto pedido");
        }
        // sem os dados obrigatorios, nada e gravado e a recusa explica
        try (Workshop.Draft draft = workshop.open(codigo.toString())) {
            assertFalse(refused(() -> workshop.build(ORG_A, WS_2, draft, "", "1.0.0", "app.py", ">=3.10,<3.14", List.of(), true)).isBlank());
            assertFalse(refused(() -> workshop.build(ORG_A, WS_2, draft, "x", "um", "app.py", ">=3.10,<3.14", List.of(), true)).isBlank());
            assertFalse(refused(() -> workshop.build(ORG_A, WS_2, draft, "x", "1.0.0", "nao-existe.py", ">=3.10,<3.14", List.of(), true)).isBlank());
            assertFalse(refused(() -> workshop.build(ORG_A, WS_2, draft, "x", "1.0.0", "app.py", "qualquer", List.of(), true)).isBlank());
            assertEquals(List.of(), workshop.list(ORG_A, WS_2));
        }
    }

    @Test
    void pacoteProntoNaoPassaPorNovaAutomacao() throws Exception {
        Path pronto = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "pronto", "1.0.0", "main.py");
        try (Workshop.Draft draft = workshop.open(pronto.toString())) {
            assertFalse(draft.code());
            assertEquals(List.of("Este ZIP já é um pacote pronto. Use Importar (.zip) para trazê-lo como está."), draft.blockers());
        }
    }

    @Test
    void renomearLocalMudaSoONomeMostrado() throws Exception {
        Path zip = PackFixtures.packageZip(tmp.resolve("zips"), "minimo", "conciliacao", "1.2.0", "main.py");
        Workshop.LocalItem item = workshop.importZip(ORG_A, WS_1, zip.toString()).item();
        Map<String, String> antes = stored();
        assertFalse(antes.isEmpty());
        Workshop.LocalItem depois = workshop.rename(ORG_A, WS_1, item.fingerprint(), "Conciliação do financeiro");
        assertEquals("Conciliação do financeiro", depois.name());
        assertEquals("conciliacao", depois.declaredName(), "o nome declarado no pacote continua o mesmo");
        assertEquals(item.fingerprint(), depois.fingerprint(), "a impressao digital nao muda");
        assertEquals("1.2.0", depois.packageVersion());
        assertEquals(antes, stored(), "manifesto e arquivos do pacote: byte a byte os mesmos");
        assertEquals(List.of(depois), workshop.list(ORG_A, WS_1));
        // em branco volta ao nome do pacote
        assertEquals("conciliacao", workshop.rename(ORG_A, WS_1, item.fingerprint(), null).name());
        // o item de outro contexto nao e alcancado por esta chamada
        assertFalse(refused(() -> workshop.rename(ORG_A, WS_2, item.fingerprint(), "x")).isBlank());
        assertFalse(refused(() -> workshop.rename(ORG_B, WS_1, item.fingerprint(), "x")).isBlank());
        assertEquals("conciliacao", workshop.list(ORG_A, WS_1).get(0).name());
    }
}
