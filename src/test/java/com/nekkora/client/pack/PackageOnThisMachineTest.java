package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-CLIENT-PACOTES-001 T02: "pacote nesta maquina" - escolher um .zip ou uma pasta de pacote para uma automacao
 * ({@link Workshop#inspectLocal}, {@link Workshop#useLocal}) e instalar um pacote que outra camada baixou
 * ({@link Workshop#downloadTarget}, {@link Workshop#installDownloaded}, {@link Workshop#discardDownload}). Sem rede: o
 * "download" dos testes e a copia de bytes para o arquivo parcial. Tudo em pastas temporarias.
 */
class PackageOnThisMachineTest {

    static final UUID ORG_A = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID ORG_B = UUID.fromString("22222222-2222-4222-8222-222222222222");
    static final UUID WS_1 = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final UUID WS_2 = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    static final UUID AUTOMACAO = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    static final UUID VERSAO_1 = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddd01");
    static final UUID VERSAO_2 = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddd02");
    static final String PLATFORM = "https://platform.exemplo.test";
    static final String OUTRA_PLATFORM = "https://outra.exemplo.test";

    @TempDir
    Path tmp;
    Path dados;
    Path baixados;
    Workshop workshop;
    Path publicado;
    String impressao;

    @BeforeEach
    void setUp() throws Exception {
        dados = tmp.resolve("dados");
        baixados = dados.resolve("baixados");
        workshop = new Workshop(dados);
        publicado = pacote("conciliacao", "print('versao publicada')\n");
        impressao = workshop.inspectLocal(publicado.toString()).fingerprint();
    }

    private Path pacote(String nome, String main) throws Exception {
        return PackFixtures.packageZip(tmp.resolve("zips"), nome, Map.of("main.py", main, "lib/util.py", "def f():\n    return 1\n"), "main.py",
                List.of("requests==2.31.0"));
    }

    /** O mesmo conteudo em outro ZIP: ordem invertida, sem compressao, dentro de uma pasta externa. Os bytes sao outros. */
    private Path recompactado(Path zip) throws Exception {
        Path into = Files.createTempDirectory(tmp, "extraido-");
        SafeZip.Content c = SafeZip.extract(zip, into, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
        List<SafeZip.Item> itens = new ArrayList<>(c.files());
        Collections.reverse(itens);
        ZipForge f = new ZipForge();
        for (SafeZip.Item i : itens) f.add(new ZipForge.E("pacote-recompactado/" + i.name(), Files.readAllBytes(i.file())).method(0));
        return f.write(tmp.resolve("zips").resolve("recompactado-" + System.nanoTime() + ".zip"));
    }

    /** Uma pasta de pacote: o ZIP extraido. */
    private Path pastaDePacote(Path zip) throws Exception {
        Path pasta = Files.createTempDirectory(tmp, "pasta-pacote-");
        SafeZip.extract(zip, pasta, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
        return pasta;
    }

    private String refused(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(Workshop.Refused.class, call).getMessage();
    }

    private static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    private static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) return out;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.filter(Files::isRegularFile).toList()) out.put(root.relativize(f).toString().replace('\\', '/'), sha256(Files.readAllBytes(f)));
        }
        return out;
    }

    private static List<String> names(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    /** Nada guardado em nenhum contexto e nenhuma copia de trabalho esquecida. */
    private void assertNadaGuardado() throws Exception {
        assertEquals(List.of(), workshop.list(ORG_A, WS_1));
        assertTrue(names(dados.resolve("pacotes")).stream().allMatch(n -> n.equals(".preparo")), names(dados.resolve("pacotes")).toString());
        assertEquals(List.of(), names(dados.resolve("pacotes/.preparo")));
        assertEquals(List.of(), names(dados.resolve("importacao")));
    }

    // ------------------------------------------------------------------ escolher nesta maquina

    @Test
    void inspecionarDizOQueFoiEscolhidoSemGuardarNada() throws Exception {
        Workshop.LocalCandidate zip = workshop.inspectLocal(publicado.toString());
        assertEquals(Workshop.LocalKind.PACKAGE_ZIP, zip.kind());
        assertTrue(zip.isPackage());
        assertEquals("conciliacao", zip.declaredName());
        assertEquals("1.0.0", zip.packageVersion());
        assertTrue(ExecutionPackage.validFingerprint(zip.fingerprint()));
        assertEquals(publicado.toString(), zip.pathLabel());
        assertEquals("Arquivo .zip", zip.originLabel());

        Path outro = recompactado(publicado);
        assertNotEquals(-1, Files.mismatch(publicado, outro), "os bytes do ZIP sao outros");
        assertEquals(impressao, workshop.inspectLocal(outro.toString()).fingerprint(), "o conteudo e o mesmo: mesma impressao digital");

        Path pasta = pastaDePacote(publicado);
        Workshop.LocalCandidate daPasta = workshop.inspectLocal(pasta.toString());
        assertEquals(Workshop.LocalKind.PACKAGE_FOLDER, daPasta.kind());
        assertEquals(impressao, daPasta.fingerprint());
        assertEquals("conciliacao", daPasta.declaredName());
        assertEquals("Pasta de pacote", daPasta.originLabel());
        assertEquals(pasta.toRealPath().toString(), daPasta.pathLabel());

        Path codigo = Files.createDirectories(tmp.resolve("codigo"));
        Files.writeString(codigo.resolve("main.py"), "print(1)\n");
        Workshop.LocalCandidate deCodigo = workshop.inspectLocal(codigo.toString());
        assertEquals(Workshop.LocalKind.CODE_FOLDER, deCodigo.kind());
        assertFalse(deCodigo.isPackage());
        assertNull(deCodigo.fingerprint());
        assertTrue(deCodigo.message().contains("Gerar pacote novo") && deCodigo.message().contains("AGT-PAC-092"), deCodigo.message());

        Map<String, String> recusas = new TreeMap<>();
        recusas.put(new ZipForge().file("main.py", "print(1)").file("manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}")
                .write(tmp.resolve("studio.zip")).toString(), "AGT-EXE-030");
        recusas.put(PackFixtures.codeZip(tmp.resolve("zips"), "minimo").toString(), "AGT-PAC-093");
        recusas.put(PackFixtures.notAZip(tmp.resolve("zips")).toString(), "AGT-PAC-005");
        recusas.put(tmp.resolve("nao-existe.zip").toString(), "AGT-PAC-091");
        recusas.put(Files.writeString(tmp.resolve("notas.txt"), "x").toString(), "AGT-PAC-090");
        Path vazia = Files.createDirectories(tmp.resolve("vazia"));
        recusas.put(vazia.toString(), "AGT-PAC-094");
        Path studio = Files.createDirectories(tmp.resolve("pasta-studio"));
        Files.writeString(studio.resolve("main.py"), "print(1)\n");
        Files.writeString(studio.resolve("manifest.json"), "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}");
        recusas.put(studio.toString(), "AGT-PAC-082");
        for (Map.Entry<String, String> r : recusas.entrySet()) {
            Workshop.LocalCandidate c = workshop.inspectLocal(r.getKey());
            assertEquals(Workshop.LocalKind.UNSUPPORTED, c.kind(), r.getKey());
            assertTrue(c.message().contains(r.getValue()), r.getKey() + " -> " + c.message());
            assertNull(c.fingerprint());
            assertEquals(c.message(), refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, r.getKey(), impressao, Workshop.Choice.SEPARATE_LOCAL)));
        }
        assertEquals(Workshop.LocalKind.UNSUPPORTED, workshop.inspectLocal(null).kind());
        assertTrue(workshop.inspectLocal(" ").message().contains("AGT-PAC-090"));
        assertNadaGuardado();
    }

    @Test
    void mesmoConteudoRecompactadoEReconhecidoEAssociadoAAutomacao() throws Exception {
        Path outro = recompactado(publicado);
        // a impressao publicada pode vir sem o prefixo e em maiusculas
        Workshop.UseLocalResult r = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, outro.toString(), impressao.substring(7).toUpperCase(), Workshop.Choice.ASK);
        assertEquals(Workshop.UseLocalOutcome.MATCHES_PUBLISHED, r.outcome());
        assertEquals(impressao, r.fingerprint());
        assertEquals("Arquivo .zip", r.origin());
        assertEquals(outro.toString(), r.pathLabel());
        assertEquals("conciliacao", r.declaredName());
        assertEquals("1.0.0", r.packageVersion());
        Workshop.LocalItem item = r.item();
        assertEquals(impressao, item.fingerprint());
        assertEquals(AUTOMACAO, item.linkedAutomationId());
        assertEquals(LocalPackages.Origin.LOCAL_ZIP, item.origin());
        assertEquals("Arquivo .zip", item.originLabel());
        assertEquals(outro.toString(), item.sourceLabel());
        assertNull(item.automationVersionId());
        assertNull(item.versionNumber());
        assertTrue(item.matches(impressao));
        assertEquals(List.of(item), workshop.list(ORG_A, WS_1), "a lista mostra a origem");
        assertEquals(List.of(), workshop.list(ORG_A, WS_2));
        assertEquals(List.of(), workshop.list(ORG_B, WS_1));
        // o ZIP original de novo: o mesmo item, sem duplicar
        Workshop.UseLocalResult outraVez = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, publicado.toString(), impressao, Workshop.Choice.ASK);
        assertEquals(Workshop.UseLocalOutcome.MATCHES_PUBLISHED, outraVez.outcome());
        assertEquals(item, outraVez.item());
        assertEquals(1, workshop.list(ORG_A, WS_1).size());
        // guardado: so o descritor e os arquivos listados, conferidos pelo validador
        ExecutionPackage guardado = workshop.packages().inspect(new PackageContext(ORG_A, WS_1), impressao);
        assertEquals(impressao, guardado.fingerprint());
        assertEquals(List.of(), names(dados.resolve("importacao")));
    }

    @Test
    void conteudoDiferenteNaoEGuardadoSemAEscolhaDaPessoaENuncaViraAVersaoPublicada() throws Exception {
        Path diferente = pacote("conciliacao", "print('alterado nesta maquina')\n");
        String outra = workshop.inspectLocal(diferente.toString()).fingerprint();
        assertNotEquals(impressao, outra, "mesmo nome, conteudo diferente");

        for (Workshop.Choice escolha : new Workshop.Choice[] {Workshop.Choice.ASK, null}) {
            Workshop.UseLocalResult r = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, diferente.toString(), impressao, escolha);
            assertEquals(Workshop.UseLocalOutcome.DIFFERS, r.outcome());
            assertNull(r.item());
            assertEquals(outra, r.fingerprint());
            assertEquals("Arquivo .zip", r.origin());
            assertEquals("conciliacao", r.declaredName());
        }
        // sem versao publicada nao ha com o que conferir
        assertEquals(Workshop.UseLocalOutcome.DIFFERS, workshop.useLocal(ORG_A, WS_1, AUTOMACAO, publicado.toString(), null, Workshop.Choice.ASK).outcome());
        assertNadaGuardado();

        Workshop.UseLocalResult separado = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, diferente.toString(), impressao, Workshop.Choice.SEPARATE_LOCAL);
        assertEquals(Workshop.UseLocalOutcome.STORED_SEPARATE, separado.outcome());
        assertNull(separado.item().linkedAutomationId(), "guardado sem associacao com a automacao");
        assertFalse(separado.item().matches(impressao));
        assertEquals(outra, separado.item().fingerprint());
        assertEquals(LocalPackages.Origin.LOCAL_ZIP, separado.item().origin());
        assertEquals(List.of(separado.item()), workshop.list(ORG_A, WS_1));
        // o item so local continua sem associacao mesmo pedido de novo
        assertNull(workshop.useLocal(ORG_A, WS_1, AUTOMACAO, diferente.toString(), impressao, Workshop.Choice.SEPARATE_LOCAL).item().linkedAutomationId());
        // e o publicado, quando chega, e outro item, associado
        Workshop.UseLocalResult certo = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, publicado.toString(), impressao, Workshop.Choice.ASK);
        assertEquals(AUTOMACAO, certo.item().linkedAutomationId());
        assertEquals(2, workshop.list(ORG_A, WS_1).size());
    }

    @Test
    void pastaDePacoteValeComoOZipEInvalidaERecusada() throws Exception {
        Path pasta = pastaDePacote(publicado);
        Files.writeString(pasta.resolve("anotacoes.txt"), "arquivo fora do manifesto: ignorado, como num ZIP\n");
        Files.createDirectories(pasta.resolve(".venv"));
        Files.writeString(pasta.resolve(".venv/x.py"), "x = 1\n");
        Map<String, String> antes = tree(pasta);
        Workshop.UseLocalResult r = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, pasta.toString(), impressao, Workshop.Choice.ASK);
        assertEquals(Workshop.UseLocalOutcome.MATCHES_PUBLISHED, r.outcome());
        assertEquals("Pasta de pacote", r.origin());
        assertEquals(LocalPackages.Origin.LOCAL_FOLDER, r.item().origin());
        assertEquals(pasta.toRealPath().toString(), r.item().sourceLabel());
        assertEquals(AUTOMACAO, r.item().linkedAutomationId());
        assertEquals(antes, tree(pasta), "a pasta escolhida nao muda");
        Path guardado = workshop.packages().find(new PackageContext(ORG_A, WS_1), impressao).orElseThrow().dir();
        assertEquals(List.of("lib", "main.py", "nekkora-external.json"), names(guardado), "so o descritor e os arquivos listados");
        assertTrue(workshop.packages().remove(new PackageContext(ORG_A, WS_1), impressao));

        // arquivo alterado: o hash nao confere
        Path alterada = pastaDePacote(publicado);
        Files.writeString(alterada.resolve("main.py"), "print('mexido')\n");
        String m = refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, alterada.toString(), impressao, Workshop.Choice.SEPARATE_LOCAL));
        assertTrue(m.contains("AGT-PAC-081") && m.contains("AGT-EXE-004"), m);
        assertEquals(Workshop.LocalKind.UNSUPPORTED, workshop.inspectLocal(alterada.toString()).kind());
        // arquivo listado que falta
        Path incompleta = pastaDePacote(publicado);
        Files.delete(incompleta.resolve("lib/util.py"));
        assertTrue(refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, incompleta.toString(), impressao, Workshop.Choice.SEPARATE_LOCAL))
                .contains("AGT-PAC-081"));
        // manifesto de versao que nao existe
        Path versao = pastaDePacote(publicado);
        Files.writeString(versao.resolve("nekkora-external.json"), Files.readString(versao.resolve("nekkora-external.json")).replace("\"version\": \"1.0.0\"",
                "\"version\": \"7.0.0\""));
        assertTrue(refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, versao.toString(), impressao, Workshop.Choice.SEPARATE_LOCAL)).contains("AGT-EXE-003"));
        assertEquals(List.of(), workshop.list(ORG_A, WS_1));
        assertEquals(Map.of(), tree(dados.resolve("pacotes")), "nenhum arquivo ficou guardado (o item valido do inicio foi removido)");
        assertEquals(List.of(), names(dados.resolve("importacao")));
    }

    @Test
    void pastaDePacoteComJuncaoParaForaERecusada() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "juncao so existe no Windows");
        Path pasta = pastaDePacote(publicado);
        Path fora = Files.createDirectories(tmp.resolve("de-fora"));
        Files.writeString(fora.resolve("x.py"), "x = 1\n");
        Path juncao = pasta.resolve("extra");
        boolean criada;
        try {
            Process pr = new ProcessBuilder("cmd", "/c", "mklink", "/J", juncao.toString(), fora.toString()).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            criada = pr.waitFor(20, TimeUnit.SECONDS) && pr.exitValue() == 0 && Files.exists(juncao.resolve("x.py"));
        } catch (IOException e) {
            criada = false;
        }
        assumeTrue(criada, "nao foi possivel criar a juncao");
        try {
            assertTrue(refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, pasta.toString(), impressao, Workshop.Choice.ASK)).contains("AGT-PAC-071"));
            assertEquals(Workshop.LocalKind.UNSUPPORTED, workshop.inspectLocal(pasta.toString()).kind());
            assertNadaGuardado();
        } finally {
            Files.delete(juncao);
        }
    }

    @Test
    void pastaDeCodigoNaoEAssociadaEExplicaOGerarPacote() throws Exception {
        Path codigo = Files.createDirectories(tmp.resolve("codigo"));
        Files.writeString(codigo.resolve("main.py"), "print(1)\n");
        for (Workshop.Choice escolha : Workshop.Choice.values()) {
            String m = refused(() -> workshop.useLocal(ORG_A, WS_1, AUTOMACAO, codigo.toString(), impressao, escolha));
            assertTrue(m.contains("Gerar pacote novo") && m.contains("Nada foi associado"), m);
        }
        assertNadaGuardado();
    }

    @Test
    void importarEPrepararRegistramAOrigemEMetadadosAntigosContinuamValendo() throws Exception {
        Workshop.LocalItem importado = workshop.importZip(ORG_A, WS_1, publicado.toString()).item();
        assertEquals(LocalPackages.Origin.LOCAL_ZIP, importado.origin());
        assertEquals(publicado.toString(), importado.sourceLabel());
        try (Workshop.Draft d = workshop.open(PackFixtures.codeZip(tmp.resolve("zips"), "minimo").toString())) {
            Workshop.LocalItem gerado = workshop.build(ORG_A, WS_2, d, "novo", "1.0.0", "main.py", ">=3.10,<3.14", List.of(), true).item();
            assertEquals(LocalPackages.Origin.BUILT, gerado.origin());
            assertEquals("Gerado neste computador", gerado.originLabel());
        }

        // um arquivo de metadados como o aplicativo gravava antes (sem os campos da origem) continua sendo lido
        Path meta = dados.resolve("pacotes").resolve(ORG_A.toString()).resolve(WS_1.toString()).resolve(impressao.substring(7) + ".json");
        String atual = Files.readString(meta);
        assertTrue(atual.contains("\"origin\": \"LOCAL_ZIP\""), atual);
        String antigo = atual.lines().filter(l -> !l.contains("\"origin\"") && !l.contains("\"sourceLabel\"") && !l.contains("\"sourceAutomationId\"")
                && !l.contains("\"automationVersionId\"") && !l.contains("\"versionNumber\"")).collect(Collectors.joining("\n", "", "\n"));
        assertTrue(antigo.contains("\"version\": \"1.0.0\"") && !antigo.contains("origin"), antigo);
        Files.writeString(meta, antigo);
        Workshop.LocalItem lido = workshop.list(ORG_A, WS_1).get(0);
        assertEquals(impressao, lido.fingerprint());
        assertEquals("conciliacao", lido.name());
        assertNull(lido.origin());
        assertEquals("", lido.originLabel());
        assertNull(lido.sourceLabel());
        assertNull(lido.versionNumber());
        // e continua podendo ser renomeado e associado (a gravacao seguinte mantem o que nao conhece como ausente)
        assertEquals("nome local", workshop.rename(ORG_A, WS_1, impressao, "nome local").name());
        // uma origem que esta versao nao conhece nao derruba o item
        Files.writeString(meta, atual.replace("\"LOCAL_ZIP\"", "\"ORIGEM_DO_FUTURO\""));
        assertNull(workshop.list(ORG_A, WS_1).get(0).origin());
    }

    // ------------------------------------------------------------------ pacote baixado

    /** O "download": os bytes vao para o arquivo parcial entregue pelo aplicativo. */
    private String baixar(String origem, UUID org, UUID versao, byte[] bytes) throws Exception {
        String parcial = workshop.downloadTarget(origem, org, AUTOMACAO, versao);
        assertEquals(baixados.resolve(".parcial"), Path.of(parcial).getParent(), "o parcial so vive na pasta de parciais");
        try (OutputStream out = workshop.downloadStream(parcial)) {
            out.write(bytes);
        }
        return parcial;
    }

    private Workshop.LocalItem instalar(String origem, UUID org, UUID ws, UUID versao, int numero, Path zip) throws Exception {
        byte[] bytes = Files.readAllBytes(zip);
        String fp = workshop.inspectLocal(zip.toString()).fingerprint();
        return workshop.installDownloaded(origem, org, ws, AUTOMACAO, versao, numero, baixar(origem, org, versao, bytes), sha256(bytes), bytes.length, fp,
                SafeZip.Cancel.NEVER);
    }

    private Path pastaDaVersao(String origem, UUID org, UUID versao) throws Exception {
        return baixados.resolve(DownloadedPackages.originKey(origem)).resolve(org.toString()).resolve(AUTOMACAO.toString()).resolve(versao.toString());
    }

    @Test
    void pacoteBaixadoEConferidoGuardadoEAssociado() throws Exception {
        byte[] bytes = Files.readAllBytes(publicado);
        Workshop.LocalItem item = instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 3, publicado);
        assertEquals(impressao, item.fingerprint());
        assertEquals(LocalPackages.Origin.PLATFORM_DOWNLOAD, item.origin());
        assertEquals("Baixado da Platform", item.originLabel());
        assertEquals(AUTOMACAO, item.linkedAutomationId());
        assertEquals(AUTOMACAO, item.sourceAutomationId());
        assertEquals(VERSAO_1, item.automationVersionId());
        assertEquals(3, item.versionNumber());
        assertEquals("1.0.0", item.packageVersion());
        assertEquals(List.of(item), workshop.list(ORG_A, WS_1), "a lista mostra a origem e a versao");

        Path pasta = pastaDaVersao(PLATFORM, ORG_A, VERSAO_1);
        assertEquals(List.of("pacote.json", "pacote.zip"), names(pasta));
        assertArrayEquals(bytes, Files.readAllBytes(pasta.resolve("pacote.zip")), "o ZIP conferido, como chegou");
        String meta = Files.readString(pasta.resolve("pacote.json"));
        assertTrue(meta.contains("\"schema\": \"nekkora-agent-client/downloaded-package\"") && meta.contains("\"versionNumber\": 3")
                && meta.contains(impressao) && meta.contains(sha256(bytes)) && meta.contains(PLATFORM), meta);
        assertEquals(List.of(), names(baixados.resolve(".parcial")), "nenhum parcial sobra");

        // a mesma versao de novo: nada duplica nem muda
        Map<String, String> antes = tree(dados);
        Workshop.LocalItem deNovo = instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 3, publicado);
        assertEquals(item, deNovo);
        assertEquals(antes, tree(dados));
        assertEquals(1, workshop.list(ORG_A, WS_1).size());
        // o endereco com barra no fim ou em maiusculas e o mesmo lugar
        assertEquals(DownloadedPackages.originKey(PLATFORM), DownloadedPackages.originKey("HTTPS://Platform.Exemplo.Test/"));
    }

    @Test
    void conteudoQueJaEstavaAquiPassaASerAVersaoBaixada() throws Exception {
        Workshop.LocalItem local = workshop.useLocal(ORG_A, WS_1, AUTOMACAO, publicado.toString(), null, Workshop.Choice.SEPARATE_LOCAL).item();
        assertNull(local.linkedAutomationId());
        Workshop.LocalItem baixado = instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 1, recompactado(publicado));
        assertEquals(local.fingerprint(), baixado.fingerprint());
        assertEquals(LocalPackages.Origin.PLATFORM_DOWNLOAD, baixado.origin());
        assertEquals(VERSAO_1, baixado.automationVersionId());
        assertEquals(AUTOMACAO, baixado.linkedAutomationId());
        assertEquals(List.of(baixado), workshop.list(ORG_A, WS_1));
    }

    @Test
    void downloadQueNaoConfereERecusadoSemDeixarNada() throws Exception {
        byte[] bytes = Files.readAllBytes(publicado);
        String sha = sha256(bytes);
        List<String> mensagens = new ArrayList<>();
        // SHA-256 diferente do informado
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, bytes),
                "0".repeat(64), bytes.length, impressao, SafeZip.Cancel.NEVER)));
        // tamanho diferente do informado
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, bytes), sha,
                bytes.length + 1, impressao, SafeZip.Cancel.NEVER)));
        // arquivo cortado (download interrompido), conferido contra os valores do arquivo inteiro
        byte[] cortado = java.util.Arrays.copyOf(bytes, bytes.length / 2);
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, cortado), sha,
                bytes.length, impressao, SafeZip.Cancel.NEVER)));
        // impressao digital diferente da informada (ZIP integro, conteudo que nao e o publicado)
        byte[] outro = Files.readAllBytes(pacote("conciliacao", "print('nao e o publicado')\n"));
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, outro),
                sha256(outro), outro.length, impressao, SafeZip.Cancel.NEVER)));
        // valores informados ausentes ou fora do formato: nao ha o que conferir
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, bytes), null,
                bytes.length, impressao, SafeZip.Cancel.NEVER)));
        mensagens.add(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, bytes), sha,
                bytes.length, "sha256:abc", SafeZip.Cancel.NEVER)));
        for (String m : mensagens) {
            assertTrue(m.startsWith("O pacote baixado não confere com o que a Platform informou") && m.contains("AGT-PAC-095") && m.contains("nada foi instalado"), m);
        }

        // arquivo cortado conferido contra os proprios valores: passa no tamanho e no SHA-256, mas nao e um ZIP inteiro
        String m = refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, cortado),
                sha256(cortado), cortado.length, impressao, SafeZip.Cancel.NEVER));
        assertTrue(m.startsWith("O pacote baixado não confere com o que a Platform informou") && m.contains("AGT-PAC-096") && m.contains("AGT-PAC-006"), m);
        // ZIPs maliciosos: caminho que sai da pasta, ligacao simbolica, nome duplicado so por maiusculas, manifesto do Studio
        String descritor = new String(descritorDe(publicado), java.nio.charset.StandardCharsets.UTF_8);
        Map<String, byte[]> maliciosos = new TreeMap<>();
        maliciosos.put("AGT-PAC-020", new ZipForge().file("nekkora-external.json", descritor).file("../../fora.py", "print('fora')").bytes());
        maliciosos.put("AGT-PAC-021", new ZipForge().file("nekkora-external.json", descritor).add(ZipForge.e("main.py", "/etc/passwd").unixMode(0120777)).bytes());
        maliciosos.put("AGT-PAC-022", new ZipForge().file("nekkora-external.json", descritor).file("main.py", "a").file("MAIN.py", "b").bytes());
        maliciosos.put("AGT-EXE-030", new ZipForge().file("main.py", "print(1)")
                .file("manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}").bytes());
        maliciosos.put("AGT-EXE-004", new ZipForge().file("nekkora-external.json", descritor).file("main.py", "print('trocado')")
                .file("lib/util.py", "def f():\n    return 1\n").bytes());
        for (Map.Entry<String, byte[]> z : maliciosos.entrySet()) {
            String r = refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, z.getValue()),
                    sha256(z.getValue()), z.getValue().length, impressao, SafeZip.Cancel.NEVER));
            assertTrue(r.contains("AGT-PAC-096") && r.contains(z.getKey()), z.getKey() + " -> " + r);
        }
        // cancelado
        assertTrue(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_1, 1, baixar(PLATFORM, ORG_A, VERSAO_1, bytes), sha,
                bytes.length, impressao, () -> true)).contains("AGT-PAC-099"));

        assertEquals(List.of(".parcial"), names(baixados), "nada fora da pasta de parciais");
        assertEquals(List.of(), names(baixados.resolve(".parcial")), "e nenhum parcial sobra");
        assertNadaGuardado();
    }

    private byte[] descritorDe(Path zip) throws Exception {
        Path into = Files.createTempDirectory(tmp, "descritor-");
        SafeZip.extract(zip, into, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
        return Files.readAllBytes(into.resolve("nekkora-external.json"));
    }

    @Test
    void duasVersoesDaMesmaAutomacaoConvivem() throws Exception {
        Path v2 = pacote("conciliacao", "print('versao 2')\n");
        Workshop.LocalItem um = instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 1, publicado);
        Map<String, String> versao1 = tree(pastaDaVersao(PLATFORM, ORG_A, VERSAO_1));
        Path guardado1 = workshop.packages().find(new PackageContext(ORG_A, WS_1), um.fingerprint()).orElseThrow().dir();
        Map<String, String> pacote1 = tree(guardado1);

        Workshop.LocalItem dois = instalar(PLATFORM, ORG_A, WS_1, VERSAO_2, 2, v2);
        assertNotEquals(um.fingerprint(), dois.fingerprint());
        assertEquals(2, dois.versionNumber());
        assertEquals(VERSAO_2, dois.automationVersionId());
        assertEquals(List.of(VERSAO_1.toString(), VERSAO_2.toString()), names(pastaDaVersao(PLATFORM, ORG_A, VERSAO_1).getParent()));
        assertEquals(versao1, tree(pastaDaVersao(PLATFORM, ORG_A, VERSAO_1)), "a versao anterior nao foi tocada");
        assertEquals(pacote1, tree(guardado1));
        List<Workshop.LocalItem> itens = workshop.list(ORG_A, WS_1);
        assertEquals(2, itens.size());
        assertEquals(List.of(1, 2), itens.stream().map(Workshop.LocalItem::versionNumber).sorted().toList());
        assertTrue(itens.stream().allMatch(i -> AUTOMACAO.equals(i.linkedAutomationId()) && i.origin() == LocalPackages.Origin.PLATFORM_DOWNLOAD));
        // a tela distingue a versao selecionada pela impressao digital publicada
        assertEquals(1, itens.stream().filter(i -> i.matches(impressao)).count());
    }

    @Test
    void organizacoesAmbientesEEnderecosDaPlatformNaoSeMisturam() throws Exception {
        instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 1, publicado);
        assertEquals(List.of(), workshop.list(ORG_A, WS_2), "outro ambiente nao ve o pacote");
        assertEquals(List.of(), workshop.list(ORG_B, WS_1), "outra organizacao nao ve o pacote");
        assertFalse(Files.exists(pastaDaVersao(PLATFORM, ORG_B, VERSAO_1)));
        assertFalse(Files.exists(pastaDaVersao(OUTRA_PLATFORM, ORG_A, VERSAO_1)));

        instalar(PLATFORM, ORG_B, WS_1, VERSAO_1, 1, publicado);
        instalar(OUTRA_PLATFORM, ORG_A, WS_2, VERSAO_1, 1, publicado);
        assertNotEquals(DownloadedPackages.originKey(PLATFORM), DownloadedPackages.originKey(OUTRA_PLATFORM));
        assertEquals(List.of(".parcial", DownloadedPackages.originKey(PLATFORM), DownloadedPackages.originKey(OUTRA_PLATFORM)).stream().sorted().toList(),
                names(baixados));
        for (Path p : List.of(pastaDaVersao(PLATFORM, ORG_A, VERSAO_1), pastaDaVersao(PLATFORM, ORG_B, VERSAO_1), pastaDaVersao(OUTRA_PLATFORM, ORG_A, VERSAO_1))) {
            assertEquals(List.of("pacote.json", "pacote.zip"), names(p));
        }
        assertEquals(1, workshop.list(ORG_A, WS_1).size());
        assertEquals(1, workshop.list(ORG_B, WS_1).size());
        assertEquals(1, workshop.list(ORG_A, WS_2).size());
        assertEquals(List.of(), workshop.list(ORG_B, WS_2));

        // os metadados de uma versao so valem no lugar a que pertencem
        DownloadedPackages lidos = new DownloadedPackages(baixados, AtomicWriter.DEFAULT, java.time.Clock.systemUTC());
        DownloadedPackages.Kept guardado = lidos.find(PLATFORM, ORG_A, AUTOMACAO, VERSAO_1).orElseThrow();
        assertEquals(impressao, guardado.fingerprint());
        assertEquals(Files.size(publicado), guardado.sizeBytes());
        assertTrue(lidos.find(PLATFORM, ORG_A, AUTOMACAO, VERSAO_2).isEmpty());
        Path copia = pastaDaVersao(OUTRA_PLATFORM, ORG_B, VERSAO_2);
        Files.createDirectories(copia);
        Files.copy(guardado.zip(), copia.resolve("pacote.zip"));
        Files.copy(guardado.zip().resolveSibling("pacote.json"), copia.resolve("pacote.json"));
        assertTrue(lidos.find(OUTRA_PLATFORM, ORG_B, AUTOMACAO, VERSAO_2).isEmpty(), "copiado de outro lugar nao vira versao instalada");
    }

    @Test
    void descartarSoApagaUmParcialDoAplicativo() throws Exception {
        String parcial = baixar(PLATFORM, ORG_A, VERSAO_1, new byte[] {1, 2, 3});
        assertTrue(Files.exists(Path.of(parcial)));
        workshop.discardDownload(parcial);
        assertFalse(Files.exists(Path.of(parcial)));
        workshop.discardDownload(parcial); // de novo: nada a fazer

        instalar(PLATFORM, ORG_A, WS_1, VERSAO_1, 1, publicado);
        Path instalado = pastaDaVersao(PLATFORM, ORG_A, VERSAO_1).resolve("pacote.zip");
        Path daPessoa = Files.writeString(tmp.resolve("documento.zip.parcial"), "arquivo da pessoa");
        Path subpasta = Files.createDirectories(baixados.resolve(".parcial/sub"));
        Path naSubpasta = Files.writeString(subpasta.resolve("x.zip.parcial"), "x");
        Path semSufixo = Files.writeString(baixados.resolve(".parcial/qualquer.txt"), "x");
        List<String> recusados = List.of(instalado.toString(), daPessoa.toString(), publicado.toString(), naSubpasta.toString(), semSufixo.toString(),
                subpasta.toString(), baixados.resolve(".parcial").toString(),
                baixados.resolve(".parcial").resolve("..").resolve("..").resolve("..").resolve("documento.zip.parcial").toString(),
                dados.resolve("pacotes").toString(), "", "  ");
        for (String caminho : recusados) {
            assertTrue(refused(() -> workshop.discardDownload(caminho)).contains("AGT-PAC-097"), caminho);
            assertTrue(refused(() -> workshop.downloadStream(caminho)).contains("AGT-PAC-097"), caminho);
            assertTrue(refused(() -> workshop.installDownloaded(PLATFORM, ORG_A, WS_1, AUTOMACAO, VERSAO_2, 2, caminho, "0".repeat(64), 3, impressao,
                    SafeZip.Cancel.NEVER)).contains("AGT-PAC-097"), caminho);
        }
        assertTrue(refused(() -> workshop.discardDownload(null)).contains("AGT-PAC-097"));
        for (Path intacto : List.of(instalado, daPessoa, publicado, naSubpasta, semSufixo)) assertTrue(Files.isRegularFile(intacto), intacto.toString());
        assertEquals("arquivo da pessoa", Files.readString(daPessoa));
        assertEquals(1, workshop.list(ORG_A, WS_1).size());
    }

    @Test
    void parciaisAntigosSaoLimposNoDownloadSeguinte() throws Exception {
        String velho = baixar(PLATFORM, ORG_A, VERSAO_1, new byte[] {1});
        String recente = baixar(PLATFORM, ORG_A, VERSAO_1, new byte[] {2});
        assertNotEquals(velho, recente, "cada download tem o seu parcial");
        Files.setLastModifiedTime(Path.of(velho), FileTime.from(Instant.now().minus(13, ChronoUnit.HOURS)));
        Files.setLastModifiedTime(Path.of(recente), FileTime.from(Instant.now().minus(11, ChronoUnit.HOURS)));
        String novo = workshop.downloadTarget(PLATFORM, ORG_A, AUTOMACAO, VERSAO_2);
        assertFalse(Files.exists(Path.of(velho)), "mais de 12 h: limpo");
        assertTrue(Files.exists(Path.of(recente)));
        assertTrue(Files.exists(Path.of(novo)));
        assertEquals(0, Files.size(Path.of(novo)));
        assertFalse(refused(() -> workshop.downloadTarget(" ", ORG_A, AUTOMACAO, VERSAO_2)).isBlank());
    }
}
