package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nekkora.client.pack.ImportProblem.Code;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Pacotes locais guardados por organizacao e ambiente: isolamento, idempotencia e metadados que nunca tocam o pacote. */
class LocalPackagesTest {

    static final PackageContext A = new PackageContext(UUID.fromString("11111111-1111-4111-8111-111111111111"), UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
    /** Mesma organizacao, outro ambiente. */
    static final PackageContext B = new PackageContext(A.organizationId(), UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    /** Outra organizacao, com um ambiente de MESMO identificador que o de A. */
    static final PackageContext C = new PackageContext(UUID.fromString("22222222-2222-4222-8222-222222222222"), A.workspaceId());

    @TempDir
    Path tmp;

    Path root;
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-13T12:00:00Z"));
    LocalPackages store;

    @BeforeEach
    void open() {
        root = tmp.resolve("dados").resolve("pacotes");
        store = new LocalPackages(root, AtomicWriter.DEFAULT, new Clock() {
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
                return now.get();
            }
        });
    }

    /** ZIP de um pacote externo valido (o vetor sintetico), dentro de uma pasta externa unica. */
    Path vectorZip(String name) throws Exception {
        Path src = ExternalPackageTest.vector(tmp.resolve("src-" + name), ExternalPackageTest.descriptor("main.py", "[]", ExternalPackageTest.FILES));
        return ZipForge.ofFolder(src, "relatorio/", tmp.resolve(name + ".zip"));
    }

    /** Outro pacote valido, gerado pelo empacotador a partir de uma fixture de codigo. */
    Packager.Built built(String fixture, String name, String entry) throws Exception {
        Path zip = ZipForge.ofFolder(ZipForge.fixture(fixture), "", tmp.resolve(fixture + "-" + System.nanoTime() + ".zip"));
        ImportedProject p = new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT).open(zip, SafeZip.Cancel.NEVER);
        return Packager.build(p, new PackagePlan(name, "1.0.0", entry, ">=3.10,<3.14", List.of(), true, Set.of(), Set.of(), false),
                Files.createDirectories(tmp.resolve("saida-" + System.nanoTime())), false, SafeZip.Cancel.NEVER);
    }

    /** Todos os arquivos de uma arvore: caminho relativo para SHA-256 dos bytes. */
    static Map<String, String> bytesOf(Path dir) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (var s = Files.walk(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                out.put(dir.relativize(p).toString().replace('\\', '/'),
                        HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));
            }
        }
        return out;
    }

    @Test
    void guardaACopiaConferidaNoEnderecoDoConteudo() throws Exception {
        LocalPackages.Added a = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER);
        assertFalse(a.alreadyPresent());
        LocalPackages.Item it = a.item();
        assertEquals(ExternalPackageTest.FINGERPRINT, it.fingerprint());
        Path dir = root.resolve(A.organizationId().toString()).resolve(A.workspaceId().toString()).resolve(ExternalPackageTest.FINGERPRINT.substring(7));
        assertEquals(dir, it.dir(), "<dados>/pacotes/<organizacao>/<ambiente>/<impressao-hex>/");
        assertEquals(Set.of("nekkora-external.json", "main.py", "util/x.bin"), bytesOf(dir).keySet(), "so o descritor e os arquivos listados");
        assertTrue(Files.isRegularFile(dir.resolveSibling(dir.getFileName() + ".json")), "metadados ao lado da pasta, fora do pacote");
        assertEquals("vetor", it.declaredName());
        assertEquals("1.0.0", it.packageVersion());
        assertEquals("main.py", it.entry());
        assertEquals(">=3.10,<3.14", it.pythonRequirement());
        assertEquals(List.of(), it.dependencies());
        assertNull(it.localName());
        assertNull(it.linkedAutomationId());
        assertEquals(now.get(), it.addedAt());
        assertEquals("vetor", it.displayName());
        assertEquals(ExternalPackageTest.FINGERPRINT, a.pkg().fingerprint());
        assertEquals(ExternalPackageTest.FINGERPRINT, store.inspect(A, it.fingerprint()).fingerprint(), "a pasta guardada passa de novo pelo validador");
        try (var s = Files.list(root.resolve(".preparo"))) {
            assertEquals(0, s.count(), "nenhuma sobra da operacao");
        }
    }

    @Test
    void arquivosNaoListadosDoZipFicamForaDaCopia() throws Exception {
        Path src = ExternalPackageTest.vector(tmp.resolve("src-extra"), ExternalPackageTest.descriptor("main.py", "[]", ExternalPackageTest.FILES));
        Path zip = ZipForge.ofFolder(src, "", tmp.resolve("extra.zip"), ZipForge.e("leia-me.txt", "nao listado"), ZipForge.e("sub/outro.py", "print(2)"));
        LocalPackages.Item it = store.add(A, zip, SafeZip.Cancel.NEVER).item();
        assertEquals(Set.of("nekkora-external.json", "main.py", "util/x.bin"), bytesOf(it.dir()).keySet());
    }

    @Test
    void guardarDeNovoEIdempotenteEPreservaOsMetadados() throws Exception {
        Path zip = vectorZip("v");
        LocalPackages.Item first = store.add(A, zip, SafeZip.Cancel.NEVER).item();
        store.rename(A, first.fingerprint(), "Relatório diário");
        UUID automation = UUID.randomUUID();
        store.link(A, first.fingerprint(), automation);
        Map<String, String> before = bytesOf(first.dir());
        now.set(now.get().plus(Duration.ofHours(3)));
        // o mesmo conteudo num ZIP montado de outro jeito (sem pasta externa): mesmo item
        Path src = ExternalPackageTest.vector(tmp.resolve("src-2"), ExternalPackageTest.descriptor("main.py", "[]", ExternalPackageTest.FILES));
        LocalPackages.Added again = store.add(A, ZipForge.ofFolder(src, "", tmp.resolve("outro.zip")), SafeZip.Cancel.NEVER);
        assertTrue(again.alreadyPresent());
        assertEquals(first.addedAt(), again.item().addedAt(), "a data e a da primeira vez");
        assertEquals("Relatório diário", again.item().localName());
        assertEquals(automation, again.item().linkedAutomationId());
        assertEquals(before, bytesOf(first.dir()));
        assertEquals(1, store.list(A).size());
    }

    @Test
    void itensDeUmContextoNuncaAparecemEmOutro() throws Exception {
        Path zip = vectorZip("v");
        LocalPackages.Item inA = store.add(A, zip, SafeZip.Cancel.NEVER).item();
        assertEquals(List.of(), store.list(B), "outro ambiente da mesma organizacao");
        assertEquals(List.of(), store.list(C), "outra organizacao com ambiente de mesmo identificador");
        assertTrue(store.find(B, inA.fingerprint()).isEmpty());
        assertTrue(store.find(C, inA.fingerprint()).isEmpty());
        assertEquals(ErrorCode.EXEC_PACKAGE_NOT_FOUND, assertThrows(ExecRefused.class, () -> store.inspect(B, inA.fingerprint())).code());
        assertEquals(Code.LOCAL_NOT_FOUND, assertThrows(ImportProblem.class, () -> store.rename(B, inA.fingerprint(), "x")).code());
        assertEquals(Code.LOCAL_NOT_FOUND, assertThrows(ImportProblem.class, () -> store.link(C, inA.fingerprint(), UUID.randomUUID())).code());
        assertFalse(store.remove(B, inA.fingerprint()), "remover em outro contexto nao encontra nada");
        assertTrue(store.find(A, inA.fingerprint()).isPresent(), "e nao toca o item do contexto certo");

        // a MESMA impressao guardada em dois contextos: dois itens independentes
        now.set(now.get().plusSeconds(60));
        LocalPackages.Added inB = store.add(B, zip, SafeZip.Cancel.NEVER);
        assertFalse(inB.alreadyPresent(), "estar guardado em A nao conta para B");
        assertEquals(inA.fingerprint(), inB.item().fingerprint());
        assertNotEquals(inA.dir(), inB.item().dir());
        assertNotEquals(inA.addedAt(), inB.item().addedAt());
        store.rename(A, inA.fingerprint(), "nome em A");
        store.link(B, inA.fingerprint(), UUID.fromString("33333333-3333-4333-8333-333333333333"));
        assertEquals("nome em A", store.find(A, inA.fingerprint()).orElseThrow().localName());
        assertNull(store.find(A, inA.fingerprint()).orElseThrow().linkedAutomationId());
        assertNull(store.find(B, inA.fingerprint()).orElseThrow().localName());
        assertEquals(B, store.find(B, inA.fingerprint()).orElseThrow().context());
        assertTrue(store.remove(A, inA.fingerprint()));
        assertEquals(List.of(), store.list(A));
        assertEquals(1, store.list(B).size(), "remover de A nao remove de B");
        assertEquals(ExternalPackageTest.FINGERPRINT, store.inspect(B, inA.fingerprint()).fingerprint());
    }

    /** Metadados copiados a mao de um contexto para outro nao viram item: organizacao e ambiente gravados precisam ser os do lugar. */
    @Test
    void metadadosDeOutroContextoNaoSaoAceitos() throws Exception {
        LocalPackages.Item inA = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        Path dirB = root.resolve(B.organizationId().toString()).resolve(B.workspaceId().toString());
        Files.createDirectories(dirB.resolve(inA.dir().getFileName().toString()));
        Files.copy(inA.dir().resolveSibling(inA.dir().getFileName() + ".json"), dirB.resolve(inA.dir().getFileName() + ".json"));
        assertEquals(List.of(), store.list(B));
        assertTrue(store.find(B, inA.fingerprint()).isEmpty());
    }

    @Test
    void renomearMudaSoOsMetadados() throws Exception {
        LocalPackages.Item it = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        Map<String, String> before = bytesOf(it.dir());
        String fingerprint = store.inspect(A, it.fingerprint()).fingerprint();
        LocalPackages.Item renamed = store.rename(A, it.fingerprint(), "  Fechamento mensal — São Paulo  ");
        assertEquals("Fechamento mensal — São Paulo", renamed.localName(), "sem os espacos das pontas");
        assertEquals("Fechamento mensal — São Paulo", renamed.displayName());
        assertEquals("vetor", renamed.declaredName(), "o nome declarado continua disponivel");
        assertEquals(it.fingerprint(), renamed.fingerprint());
        assertEquals(it.addedAt(), renamed.addedAt());
        assertEquals(before, bytesOf(it.dir()), "nenhum byte do pacote mudou");
        ExecutionPackage after = store.inspect(A, it.fingerprint());
        assertEquals(fingerprint, after.fingerprint(), "a impressao digital e a mesma");
        assertEquals("vetor", after.name(), "o descritor nao foi tocado");
        assertEquals(renamed, store.find(A, it.fingerprint()).orElseThrow(), "gravado");
        assertEquals(List.of(renamed), store.list(A));
        // limpar o nome local volta ao declarado
        assertNull(store.rename(A, it.fingerprint(), "   ").localName());
        assertEquals("vetor", store.find(A, it.fingerprint()).orElseThrow().displayName());
        assertNull(store.rename(A, it.fingerprint(), null).localName());
        assertEquals(before, bytesOf(it.dir()));
    }

    @Test
    void nomeLocalInvalidoERecusadoSemMudarNada() throws Exception {
        LocalPackages.Item it = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        store.rename(A, it.fingerprint(), "nome bom");
        for (String bad : new String[] {"x".repeat(81), "duas\nlinhas", "tab\taqui", "bidi\u202Etxt", "nulo\0"}) {
            assertEquals(Code.LOCAL_NAME_INVALID, assertThrows(ImportProblem.class, () -> store.rename(A, it.fingerprint(), bad)).code(), bad);
        }
        assertEquals("nome bom", store.find(A, it.fingerprint()).orElseThrow().localName());
        assertEquals("x".repeat(80), store.rename(A, it.fingerprint(), "x".repeat(80)).localName());
    }

    @Test
    void associarEDesassociarAutomacaoMudamSoOsMetadados() throws Exception {
        LocalPackages.Item it = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        store.rename(A, it.fingerprint(), "meu nome");
        Map<String, String> before = bytesOf(it.dir());
        UUID automation = UUID.fromString("44444444-4444-4444-8444-444444444444");
        LocalPackages.Item linked = store.link(A, it.fingerprint(), automation);
        assertEquals(automation, linked.linkedAutomationId());
        assertEquals("meu nome", linked.localName(), "associar nao mexe no nome");
        assertEquals(automation, store.find(A, it.fingerprint()).orElseThrow().linkedAutomationId());
        LocalPackages.Item unlinked = store.unlink(A, it.fingerprint());
        assertNull(unlinked.linkedAutomationId());
        assertNull(store.find(A, it.fingerprint()).orElseThrow().linkedAutomationId());
        assertEquals("meu nome", unlinked.localName());
        assertEquals(before, bytesOf(it.dir()));
        assertEquals(it.fingerprint(), store.inspect(A, it.fingerprint()).fingerprint());
        assertThrows(NullPointerException.class, () -> store.link(A, it.fingerprint(), null));
    }

    @Test
    void listaEmOrdemFixa() throws Exception {
        LocalPackages.Item first = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        now.set(now.get().plusSeconds(10));
        LocalPackages.Item second = store.addBuilt(A, built("minimo", "minimo", "main.py"), SafeZip.Cancel.NEVER).item();
        // dois itens no MESMO instante: desempate pela impressao digital
        LocalPackages.Item third = store.addBuilt(A, built("modular", "relatorio-modular", "principal.py"), SafeZip.Cancel.NEVER).item();
        List<LocalPackages.Item> expected = second.fingerprint().compareTo(third.fingerprint()) < 0 ? List.of(first, second, third) : List.of(first, third, second);
        assertEquals(expected, store.list(A));
        assertEquals(expected, store.list(A), "a mesma ordem a cada leitura");
        assertEquals(expected, new LocalPackages(root, AtomicWriter.DEFAULT, Clock.systemUTC()).list(A), "e depois de reabrir o aplicativo");
    }

    @Test
    void identificadoresInvalidosSaoRecusados() throws Exception {
        store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER);
        for (String bad : new String[] {"", "..", "../..", "1-1-1-1-1", "11111111-1111-4111-8111-11111111111", "11111111-1111-4111-8111-1111111111111",
                "11111111_1111_4111_8111_111111111111", "C:\\Windows", "g1111111-1111-4111-8111-111111111111", "11111111-1111-4111-8111-111111111111/.."}) {
            assertThrows(IllegalArgumentException.class, () -> PackageContext.parse(bad, A.workspaceId().toString()), bad);
            assertThrows(IllegalArgumentException.class, () -> PackageContext.parse(A.organizationId().toString(), bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> PackageContext.parse(null, null));
        assertThrows(NullPointerException.class, () -> new PackageContext(null, A.workspaceId()));
        assertThrows(NullPointerException.class, () -> new PackageContext(A.organizationId(), null));
        assertEquals(A, PackageContext.parse("11111111-1111-4111-8111-111111111111", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"), "maiusculas: o mesmo UUID, a mesma pasta");
        for (String bad : new String[] {null, "", "e71ca1e0aedd", ExternalPackageTest.FINGERPRINT.substring(7), "sha256:" + "E".repeat(64), "sha256:../" + "0".repeat(61),
                "sha256:" + "0".repeat(63), "sha256:" + "0".repeat(65), "..", "sha1:" + "0".repeat(64)}) {
            assertThrows(IllegalArgumentException.class, () -> store.find(A, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.rename(A, bad, "x"), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.link(A, bad, UUID.randomUUID()), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.unlink(A, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.remove(A, bad), String.valueOf(bad));
            assertThrows(IllegalArgumentException.class, () -> store.inspect(A, bad), String.valueOf(bad));
        }
        assertEquals(1, store.list(A).size(), "nada foi tocado");
        try (var s = Files.walk(tmp)) {
            assertTrue(s.allMatch(p -> p.startsWith(tmp)));
        }
    }

    @Test
    void removerApagaSoOItemDoContexto() throws Exception {
        LocalPackages.Item v = store.add(A, vectorZip("v"), SafeZip.Cancel.NEVER).item();
        LocalPackages.Item m = store.addBuilt(A, built("minimo", "minimo", "main.py"), SafeZip.Cancel.NEVER).item();
        assertTrue(store.remove(A, v.fingerprint()));
        assertFalse(Files.exists(v.dir()));
        assertFalse(Files.exists(v.dir().resolveSibling(v.dir().getFileName() + ".json")));
        assertTrue(store.find(A, v.fingerprint()).isEmpty());
        assertEquals(List.of(m), store.list(A));
        assertFalse(store.remove(A, v.fingerprint()), "remover de novo: nada a fazer");
        assertEquals(Code.LOCAL_NOT_FOUND, assertThrows(ImportProblem.class, () -> store.rename(A, v.fingerprint(), "x")).code());
        // guardar de novo depois de remover: item novo, sem os metadados antigos
        assertFalse(store.add(A, vectorZip("v2"), SafeZip.Cancel.NEVER).alreadyPresent());
    }

    @Test
    void zipQueNaoEPacoteExternoERecusadoENadaFica() throws Exception {
        // codigo sem descritor: nao e um pacote (o caminho dele e "nova automacao", pelo empacotador)
        Path code = ZipForge.ofFolder(ZipForge.fixture("minimo"), "", tmp.resolve("codigo.zip"));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNKNOWN, assertThrows(ExecRefused.class, () -> store.add(A, code, SafeZip.Cancel.NEVER)).code());
        // pacote do Studio: recusado com motivo proprio
        Path studio = new ZipForge().file("main.py", "print(1)").file("manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}")
                .write(tmp.resolve("studio.zip"));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSUPPORTED, assertThrows(ExecRefused.class, () -> store.add(A, studio, SafeZip.Cancel.NEVER)).code());
        Path lab = new ZipForge().file("lab.py", "print(1)").file("nekkora-agent-lab.json", "{\"schema\":\"nekkora-agent/lab-package\",\"version\":\"1.0.0\"}")
                .write(tmp.resolve("lab.zip"));
        assertEquals(ErrorCode.EXEC_PACKAGE_UNSUPPORTED, assertThrows(ExecRefused.class, () -> store.add(A, lab, SafeZip.Cancel.NEVER)).code());
        // pacote externo adulterado depois de gerado
        Path src = ExternalPackageTest.vector(tmp.resolve("src-mau"), ExternalPackageTest.descriptor("main.py", "[]", ExternalPackageTest.FILES));
        Files.writeString(src.resolve("main.py"), "print('adulterado')\n");
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED,
                assertThrows(ExecRefused.class, () -> store.add(A, ZipForge.ofFolder(src, "", tmp.resolve("mau.zip")), SafeZip.Cancel.NEVER)).code());
        // ZIP hostil e arquivo que nao e ZIP: recusa do leitor seguro
        Path evil = new ZipForge().file("../fora.txt", "x").write(tmp.resolve("hostil.zip"));
        assertEquals(Code.UNSAFE_PATH, assertThrows(ImportProblem.class, () -> store.add(A, evil, SafeZip.Cancel.NEVER)).code());
        Path text = Files.writeString(tmp.resolve("texto.zip"), "isto nao e um zip");
        assertEquals(Code.NOT_ZIP, assertThrows(ImportProblem.class, () -> store.add(A, text, SafeZip.Cancel.NEVER)).code());
        assertEquals(Code.SOURCE_NOT_FOUND, assertThrows(ImportProblem.class, () -> store.add(A, tmp.resolve("nao-existe.zip"), SafeZip.Cancel.NEVER)).code());
        assertEquals(Code.CANCELLED, assertThrows(ImportProblem.class, () -> store.add(A, vectorZip("c"), () -> true)).code());
        assertEquals(List.of(), store.list(A));
        assertFalse(Files.exists(tmp.resolve("fora.txt")));
        try (var s = Files.walk(root)) {
            assertEquals(List.of(), s.filter(Files::isRegularFile).toList(), "nenhum arquivo ficou na pasta de pacotes");
        }
    }

    @Test
    void novaAutomacaoEmpacotaEGuardaSemExecutarNada() throws Exception {
        long children = ProcessHandle.current().children().count();
        Path zip = ZipForge.ofFolder(ZipForge.fixture("modular"), "modular/", tmp.resolve("projeto.zip"));
        byte[] zipBefore = Files.readAllBytes(zip);
        ProjectImporter importer = new ProjectImporter(tmp.resolve("dados").resolve("importacao"), ZipLimits.DEFAULT);
        ImportedProject p = importer.open(zip, SafeZip.Cancel.NEVER);
        PackagePlan plan = new PackagePlan("relatorio-modular", "1.2.3", "principal.py", ">=3.10,<3.14", List.of("openpyxl==3.1.5"), true, Set.of(), Set.of(), false);
        Packager.Preview preview = Packager.preview(p, plan);
        LocalPackages.Added a = store.addBuilt(A, p, plan, SafeZip.Cancel.NEVER);
        importer.close(p);
        assertEquals(preview.fingerprint(), a.item().fingerprint(), "o que foi guardado e o que a previa mostrou");
        assertEquals("relatorio-modular", a.item().declaredName());
        assertEquals("1.2.3", a.item().packageVersion());
        assertEquals("principal.py", a.item().entry());
        assertEquals(List.of("openpyxl==3.1.5"), a.item().dependencies());
        assertEquals(List.of("openpyxl==3.1.5"), store.find(A, a.item().fingerprint()).orElseThrow().dependencies(), "lidas de volta dos metadados");
        assertTrue(Files.isRegularFile(a.item().dir().resolve("calculos/totais.py")));
        assertFalse(Files.exists(a.item().dir().resolve("dados/exemplo.csv")), "dados ficam fora por padrao");
        assertEquals(children, ProcessHandle.current().children().count(), "nenhum processo foi criado");
        try (var s = Files.walk(tmp)) {
            assertTrue(s.noneMatch(f -> f.getFileName().toString().equals("SENTINELA-EXECUTOU.txt")), "setup.py e conftest.py nao rodaram");
        }
        assertEquals(HexFormat.of().formatHex(zipBefore), HexFormat.of().formatHex(Files.readAllBytes(zip)), "o ZIP de origem nao foi tocado");
        try (var s = Files.list(root.resolve(".preparo"))) {
            assertEquals(0, s.count(), "o ZIP intermediario foi apagado");
        }
        // empacotar de novo o mesmo plano: mesmo conteudo, mesmo item
        ImportedProject again = importer.open(zip, SafeZip.Cancel.NEVER);
        assertTrue(store.addBuilt(A, again, plan, SafeZip.Cancel.NEVER).alreadyPresent());
    }

    @Test
    void pacoteEmpacotadoQueMudouAntesDeGuardarERecusado() throws Exception {
        Packager.Built b = built("minimo", "minimo", "main.py");
        Packager.Built other = built("modular", "relatorio-modular", "principal.py");
        Files.copy(other.zip(), b.zip(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> store.addBuilt(A, b, SafeZip.Cancel.NEVER)).code());
        assertEquals(List.of(), store.list(A));
    }

    @Test
    void pastaGuardadaAlteradaPorForaEDetectadaERefeita() throws Exception {
        Path zip = vectorZip("v");
        LocalPackages.Item it = store.add(A, zip, SafeZip.Cancel.NEVER).item();
        store.rename(A, it.fingerprint(), "meu nome");
        Files.writeString(it.dir().resolve("main.py"), "print('adulterado')\n", StandardCharsets.UTF_8);
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class, () -> store.inspect(A, it.fingerprint())).code());
        LocalPackages.Added again = store.add(A, zip, SafeZip.Cancel.NEVER);
        assertFalse(again.alreadyPresent(), "a pasta nao era mais o pacote: refeita");
        assertEquals("meu nome", again.item().localName(), "os metadados do item ficam");
        assertEquals(it.fingerprint(), store.inspect(A, it.fingerprint()).fingerprint());
    }

    @Test
    void falhaAoGravarMetadadosNaoDeixaItemPelaMetade() throws Exception {
        LocalPackages failing = new LocalPackages(root, (target, data) -> {
            throw new IOException("sem permissao");
        }, Clock.systemUTC());
        assertEquals(Code.STORE_FAILED, assertThrows(ImportProblem.class, () -> failing.add(A, vectorZip("v"), SafeZip.Cancel.NEVER)).code());
        assertEquals(List.of(), store.list(A), "sem metadados nao ha item");
        LocalPackages.Item it = store.add(A, vectorZip("v2"), SafeZip.Cancel.NEVER).item();
        assertEquals(Code.STORE_FAILED, assertThrows(ImportProblem.class, () -> failing.rename(A, it.fingerprint(), "novo")).code());
        assertNull(store.find(A, it.fingerprint()).orElseThrow().localName(), "o nome anterior continua");
    }
}
