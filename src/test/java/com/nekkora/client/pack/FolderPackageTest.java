package com.nekkora.client.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AGENT-CLIENT-PACOTES-001 T01: gerar um pacote .zip a partir de uma PASTA de projeto, pelo que a tela chama
 * ({@link Workshop#openFolder}, {@link Workshop#summary}, {@link Workshop#generate}). Tudo em pastas temporarias; nada e
 * executado, nada vai para a rede e nada e guardado no contexto.
 */
class FolderPackageTest {

    static final UUID ORG = UUID.fromString("11111111-1111-4111-8111-111111111111");
    static final UUID WS = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    static final String PY = ">=3.10,<3.14";

    @TempDir
    Path tmp;
    Workshop workshop;
    Path dados;
    Path projeto;
    Path saida;

    @BeforeEach
    void setUp() throws Exception {
        dados = tmp.resolve("dados");
        workshop = new Workshop(dados);
        projeto = tmp.resolve("projeto");
        saida = Files.createDirectories(tmp.resolve("saida"));
        write(projeto, "src/app/main.py", "from util import soma\nprint(soma(1, 2))\n");
        write(projeto, "util.py", "def soma(a, b):\n    return a + b\n");
        write(projeto, "README.md", "# Projeto de exemplo\n");
        write(projeto, "requirements.txt", "requests==2.31.0\n");
    }

    @AfterEach
    void restore() {
        Packager.opener = Packager.Opener.FILES;
        Packager.beforeMove = () -> {};
    }

    private static Path write(Path root, String rel, String text) throws IOException {
        Path f = root.resolve(rel);
        Files.createDirectories(f.getParent());
        return Files.writeString(f, text);
    }

    /** O que nunca entra: credenciais, ambiente virtual, cache, controle de versao, dados, configuracao de ferramenta. */
    private void sujeira() throws IOException {
        write(projeto, ".env", "SENHA=nao-vai\n");
        write(projeto, "id_rsa", "chave privada\n");
        write(projeto, "certs/servidor.pem", "certificado\n");
        write(projeto, ".git/config", "[core]\n");
        write(projeto, ".venv/Lib/site-packages/x.py", "x = 1\n");
        write(projeto, "node_modules/a/index.js", "a\n");
        write(projeto, "__pycache__/util.cpython-312.pyc", "bytecode");
        write(projeto, "dados/clientes.csv", "nome;cpf\n");
        write(projeto, ".gitignore", ".venv\n");
    }

    /** Arvore inteira: caminho relativo -> SHA-256 dos bytes (pastas entram com "pasta"). */
    private static Map<String, String> tree(Path root) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path f : s.toList()) {
                String rel = root.relativize(f).toString().replace('\\', '/');
                out.put(rel, Files.isDirectory(f) ? "pasta" : HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(f))));
            }
        }
        return out;
    }

    private static List<String> names(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().collect(Collectors.toList());
        }
    }

    private String refused(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(Workshop.Refused.class, call).getMessage();
    }

    private ExecutionPackage validated(String zip) throws Exception {
        Path into = Files.createTempDirectory(tmp, "conferencia-");
        SafeZip.extract(Path.of(zip), into, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
        return ExecutionPackage.inspect(into);
    }

    private Workshop.Generated gerar(Workshop.Draft d, String destino, boolean overwrite) throws Exception {
        return workshop.generate(d, "conciliacao", null, "src/app/main.py", PY, null, true, destino, overwrite, SafeZip.Cancel.NEVER);
    }

    // ------------------------------------------------------------------ caminho feliz

    @Test
    void pastaValidaComEntradaEmSubpastaGeraUmZipAceitoPeloValidador() throws Exception {
        sujeira();
        Map<String, String> antes = tree(projeto);
        String destino = saida.resolve("meu pacote.zip").toString();
        Workshop.Generated g;
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertTrue(d.folder());
            assertTrue(d.code());
            assertEquals(projeto.toRealPath().toString(), d.sourceLabel());
            assertEquals(List.of(), d.blockers());
            assertEquals(List.of("src/app/main.py", "util.py"), d.pythonFiles(), "so arquivos da pasta que entram no pacote");
            assertEquals(java.util.Optional.of("util.py"), d.suggestedEntry(), "a regra de sugestao de sempre (unico .py da raiz); a escolha e da pessoa");
            assertEquals(List.of("requests==2.31.0"), d.dependencies());
            assertFalse(d.dependenciesNeedConfirmation());
            assertTrue(d.existingManifest().isEmpty());

            Workshop.PackageSummary s = workshop.summary(d, "conciliacao", null, "src/app/main.py", PY, null, false);
            assertEquals("conciliacao", s.name());
            assertEquals("1.0.0", s.version(), "sem manifesto e sem versao informada: 1.0.0");
            assertEquals("src/app/main.py", s.entry());
            assertEquals(List.of("requests==2.31.0"), s.dependencies());
            assertEquals(List.of("README.md", "requirements.txt", "src/app/main.py", "util.py"), s.included());
            Map<String, String> fora = new HashMap<>();
            s.excluded().forEach(x -> fora.put(x.path(), x.reason()));
            assertEquals(Set.of(".env", "id_rsa", "certs/servidor.pem", ".git/", ".venv/", "node_modules/", "__pycache__/", "dados/clientes.csv", ".gitignore"),
                    fora.keySet());
            assertEquals(InclusionRules.Rule.SECRET.label(), fora.get(".env"));
            assertEquals(InclusionRules.Rule.SECRET.label(), fora.get("id_rsa"));
            assertEquals(InclusionRules.Rule.SECRET.label(), fora.get("certs/servidor.pem"));
            assertEquals(InclusionRules.Rule.ENVIRONMENT.label(), fora.get(".git/"));
            assertEquals(InclusionRules.Rule.ENVIRONMENT.label(), fora.get(".venv/"));
            assertEquals(InclusionRules.Rule.ENVIRONMENT.label(), fora.get("node_modules/"));
            assertEquals(InclusionRules.Rule.DATA.label(), fora.get("dados/clientes.csv"));
            assertEquals(InclusionRules.Rule.TOOLING.label(), fora.get(".gitignore"));
            assertFalse(s.manifestReused());
            assertFalse(Files.exists(Path.of(destino)), "o resumo nao grava nada");

            // credenciais, ambiente e dados nem chegam a copia de trabalho
            Set<String> copiados = tree(dados.resolve("importacao")).keySet();
            assertTrue(copiados.stream().anyMatch(n -> n.endsWith("/fonte/util.py")), copiados.toString());
            assertTrue(copiados.stream().noneMatch(n -> n.contains(".env") || n.contains("id_rsa") || n.contains(".pem") || n.contains(".git")
                    || n.contains("site-packages") || n.contains("node_modules") || n.contains("clientes.csv")), copiados.toString());

            g = workshop.generate(d, "conciliacao", null, "src/app/main.py", PY, null, false, destino, false, SafeZip.Cancel.NEVER);
            assertEquals(s.fingerprint(), g.fingerprint(), "o que foi gerado e o que o resumo mostrou");
            assertEquals(s.included().size(), g.fileCount());
        }
        assertEquals(Path.of(destino).toRealPath().toString(), g.path());
        assertEquals("conciliacao", g.name());
        assertEquals("1.0.0", g.version());
        assertEquals("src/app/main.py", g.entry());
        assertEquals(Files.size(Path.of(destino)), g.sizeBytes());
        assertEquals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Path.of(destino)))), g.sha256());
        assertEquals(List.of("meu pacote.zip"), names(saida), "so o pacote, sem temporario");

        ExecutionPackage pk = validated(destino);
        assertEquals(g.fingerprint(), pk.fingerprint());
        assertEquals("src/app/main.py", pk.entry());
        assertEquals("1.0.0", pk.packageVersion());
        assertEquals(Set.of("README.md", "requirements.txt", "src/app/main.py", "util.py"), pk.files().keySet());
        assertEquals(List.of("requests==2.31.0"), pk.dependencies());

        assertEquals(antes, tree(projeto), "a pasta da pessoa nao muda um byte");
        assertEquals(List.of(), workshop.list(ORG, WS), "gerar nao guarda nada no contexto");
        assertEquals(List.of(), names(dados.resolve("importacao")), "fechar apaga a copia de trabalho");

        // ida e volta: o ZIP gerado e um pacote que o Importar aceita, com a mesma impressao
        Workshop.Imported in = workshop.importZip(ORG, WS, destino);
        assertEquals(g.fingerprint(), in.item().fingerprint());
        assertEquals("conciliacao", in.item().declaredName());
    }

    @Test
    void mesmaPastaMesmaImpressaoInclusiveComODestinoDentroDaPasta() throws Exception {
        Workshop.Generated a;
        Workshop.Generated b;
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            a = gerar(d, saida.resolve("a.zip").toString(), false);
        }
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            b = gerar(d, saida.resolve("b.zip").toString(), false);
        }
        assertEquals(a.fingerprint(), b.fingerprint());
        assertEquals(-1, Files.mismatch(Path.of(a.path()), Path.of(b.path())), "os mesmos bytes");

        // destino DENTRO da pasta de origem: o pacote nao se inclui
        Path dentro = projeto.resolve("pacote.zip");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertEquals(a.fingerprint(), gerar(d, dentro.toString(), false).fingerprint());
        }
        assertFalse(validated(dentro.toString()).files().containsKey("pacote.zip"));
        byte[] primeira = Files.readAllBytes(dentro);
        // de novo, agora com a primeira saida ja na pasta: ela fica fora (e o resumo diz por que)
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            Workshop.PackageSummary semDestino = workshop.summary(d, "conciliacao", null, "src/app/main.py", PY, null, true);
            assertTrue(semDestino.included().contains("pacote.zip"), "um .zip qualquer da pasta segue a regra de sempre");
            Workshop.PackageSummary s = workshop.summary(d, "conciliacao", null, "src/app/main.py", PY, null, true, dentro.toString());
            assertEquals(a.fingerprint(), s.fingerprint());
            assertFalse(s.included().contains("pacote.zip"));
            assertEquals(List.of(new Workshop.ExcludedFile("pacote.zip", "Fora: é o próprio arquivo de destino do pacote")), s.excluded());
            assertThrows(Workshop.DestinationExists.class, () -> gerar(d, dentro.toString(), false));
            Workshop.Generated c = gerar(d, dentro.toString(), true);
            assertEquals(a.fingerprint(), c.fingerprint());
            // o mesmo retrato gera de novo: o destino mudou na pasta, mas nao faz parte do pacote
            assertEquals(a.fingerprint(), gerar(d, dentro.toString(), true).fingerprint());
        }
        assertEquals(-1, Files.mismatch(dentro, Path.of(a.path())));
        assertEquals(primeira.length, Files.size(dentro));
        assertEquals(List.of("README.md", "pacote.zip", "requirements.txt", "src", "util.py"), names(projeto), "nenhum temporario ficou na pasta");
    }

    // ------------------------------------------------------------------ manifesto existente

    /** Uma pasta que ja e um pacote: o ZIP gerado do projeto, extraido. */
    private Path pastaDePacote(String versao) throws Exception {
        Path zip = saida.resolve("base-" + versao + ".zip");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            workshop.generate(d, "conciliacao", versao, "src/app/main.py", PY, null, true, zip.toString(), false, SafeZip.Cancel.NEVER);
        }
        Path pasta = Files.createDirectories(tmp.resolve("pacote-" + versao));
        SafeZip.extract(zip, pasta, ZipLimits.DEFAULT, SafeZip.Cancel.NEVER);
        return pasta;
    }

    @Test
    void manifestoValidoDaPastaEReaproveitadoEAVersaoNaoSobeSozinha() throws Exception {
        Path pasta = pastaDePacote("2.3.4");
        String impressao = ExecutionPackage.inspect(pasta).fingerprint();
        Map<String, String> antes = tree(pasta);
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertEquals(List.of(), d.blockers());
            Workshop.Manifest m = d.existingManifest().orElseThrow();
            assertEquals("conciliacao", m.name());
            assertEquals("2.3.4", m.version());
            assertEquals("src/app/main.py", m.entry());
            assertEquals(PY, m.python());
            assertEquals(List.of("requests==2.31.0"), m.dependencies());
            assertEquals(impressao, m.fingerprint());

            Workshop.PackageSummary s = workshop.summary(d, null, null, null, null, null, false);
            assertEquals("2.3.4", s.version(), "a versao do manifesto nao e aumentada");
            assertEquals("conciliacao", s.name());
            assertTrue(s.manifestReused());
            assertEquals(impressao, s.fingerprint());
            Workshop.Generated g = workshop.generate(d, null, " ", null, null, null, false, saida.resolve("igual.zip").toString(), false, SafeZip.Cancel.NEVER);
            assertEquals(impressao, g.fingerprint(), "o mesmo pacote, a mesma impressao digital");
            assertEquals("2.3.4", validated(g.path()).packageVersion());

            // a pessoa muda a versao: descritor novo SO no ZIP
            Workshop.PackageSummary novo = workshop.summary(d, null, "2.4.0", null, null, null, false);
            assertFalse(novo.manifestReused());
            assertEquals("2.4.0", novo.version());
            assertFalse(impressao.equals(novo.fingerprint()));
            Workshop.Generated g2 = workshop.generate(d, null, "2.4.0", null, null, null, false, saida.resolve("novo.zip").toString(), false, SafeZip.Cancel.NEVER);
            assertEquals("2.4.0", validated(g2.path()).packageVersion());
        }
        assertEquals(antes, tree(pasta), "o manifesto e os arquivos da pasta nao foram tocados");

        // um arquivo novo na pasta: pacote novo, e a versao continua a do manifesto ate a pessoa mudar
        write(pasta, "extra.py", "print('novo')\n");
        try (Workshop.Draft d = workshop.openFolder(pasta.toString())) {
            assertTrue(d.existingManifest().isPresent(), "os arquivos listados continuam conferindo");
            Workshop.PackageSummary s = workshop.summary(d, null, null, null, null, null, false);
            assertEquals("2.3.4", s.version());
            assertFalse(s.manifestReused());
            assertTrue(s.included().contains("extra.py"));
        }
    }

    @Test
    void manifestoInvalidoConflitanteOuDoStudioEExplicadoENadaEMisturado() throws Exception {
        Path invalido = pastaDePacote("1.0.0");
        Files.writeString(invalido.resolve("util.py"), "def soma(a, b):\n    return 0\n");
        Map<String, String> antes = tree(invalido);
        try (Workshop.Draft d = workshop.openFolder(invalido.toString())) {
            assertTrue(d.existingManifest().isEmpty());
            assertEquals(1, d.blockers().size());
            assertTrue(d.blockers().get(0).contains("AGT-PAC-081") && d.blockers().get(0).contains("AGT-EXE-004"), d.blockers().toString());
            assertEquals(d.blockers().get(0), refused(() -> workshop.summary(d, "x", null, "util.py", PY, List.of(), true)));
            assertEquals(d.blockers().get(0), refused(() -> gerar(d, saida.resolve("x.zip").toString(), false)));
        }
        assertEquals(antes, tree(invalido));

        Path esquemaErrado = Files.createDirectories(tmp.resolve("esquema"));
        write(esquemaErrado, "main.py", "print(1)\n");
        write(esquemaErrado, "nekkora-external.json", "{\"schema\": \"outra-coisa\", \"version\": \"9.0.0\"}");
        try (Workshop.Draft d = workshop.openFolder(esquemaErrado.toString())) {
            assertTrue(d.blockers().get(0).contains("AGT-PAC-081"), d.blockers().toString());
        }

        Path studio = Files.createDirectories(tmp.resolve("studio"));
        write(studio, "main.py", "print(1)\n");
        write(studio, "manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}");
        try (Workshop.Draft d = workshop.openFolder(studio.toString())) {
            assertTrue(d.blockers().get(0).contains("AGT-PAC-082"), d.blockers().toString());
            assertTrue(refused(() -> workshop.summary(d, "x", null, "main.py", PY, List.of(), true)).contains("AGT-PAC-082"));
        }
        Path lab = Files.createDirectories(tmp.resolve("lab"));
        write(lab, "main.py", "print(1)\n");
        write(lab, "nekkora-agent-lab.json", "{\"schema\":\"nekkora-agent/lab-package\",\"version\":\"1.0.0\"}");
        try (Workshop.Draft d = workshop.openFolder(lab.toString())) {
            assertTrue(d.blockers().get(0).contains("AGT-PAC-082"), d.blockers().toString());
        }

        Path conflito = pastaDePacote("1.1.0");
        write(conflito, "manifest.json", "{\"schema\":\"nekkora-studio/codegen-manifest\",\"version\":\"1.4.0\"}");
        try (Workshop.Draft d = workshop.openFolder(conflito.toString())) {
            assertTrue(d.blockers().get(0).contains("AGT-PAC-080"), d.blockers().toString());
            assertTrue(refused(() -> gerar(d, saida.resolve("c.zip").toString(), false)).contains("AGT-PAC-080"));
        }
        assertFalse(Files.exists(saida.resolve("x.zip")) || Files.exists(saida.resolve("c.zip")));
    }

    // ------------------------------------------------------------------ entrada, ligacoes

    @Test
    void entradaSoPodeSerUmArquivoDaListaDaPasta() throws Exception {
        sujeira();
        write(tmp, "fora.py", "print('fora')\n");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            for (String entrada : List.of("../fora.py", "src/../../fora.py", tmp.resolve("fora.py").toString(), projeto.resolve("util.py").toString(), "UTIL.PY",
                    "SRC/APP/MAIN.PY", "src\\app\\main.py", ".venv/Lib/site-packages/x.py", "nao-existe.py", "README.md", "./util.py", "/util.py")) {
                String m = refused(() -> workshop.summary(d, "x", null, entrada, PY, null, true));
                assertTrue(m.contains("AGT-PAC-034"), entrada + " -> " + m);
                assertTrue(refused(() -> workshop.generate(d, "x", null, entrada, PY, null, true, saida.resolve("e.zip").toString(), false, SafeZip.Cancel.NEVER))
                        .contains("AGT-PAC-034"));
            }
            // nome, versao e Python seguem as regras de sempre
            assertTrue(refused(() -> workshop.summary(d, "", null, "util.py", PY, null, true)).contains("AGT-PAC-035"));
            assertTrue(refused(() -> workshop.summary(d, "nome com espaço", null, "util.py", PY, null, true)).contains("AGT-PAC-035"));
            assertTrue(refused(() -> workshop.summary(d, "x", "um", "util.py", PY, null, true)).contains("AGT-PAC-036"));
            // Python: um texto que nao e um requisito e recusado; sem requisito (nulo ou em branco), o pacote e gerado
            assertTrue(refused(() -> workshop.summary(d, "x", null, "util.py", "três ponto doze", null, true)).contains("AGT-PAC-037"));
            assertTrue(refused(() -> workshop.summary(d, "x", null, "util.py", ">=3", null, true)).contains("AGT-PAC-037"));
            assertEquals(null, workshop.summary(d, "x", null, "util.py", null, null, true).python());
            assertEquals(null, workshop.summary(d, "x", null, "util.py", "  ", null, true).python());
            assertTrue(refused(() -> workshop.summary(d, "x", null, "util.py", PY, List.of("requests>=2"), true)).contains("AGT-PAC-038"));
        }
        assertEquals(List.of(), names(saida));
    }

    @Test
    void ligacaoSimbolicaNaPastaERecusada() throws Exception {
        Path fora = write(tmp, "segredo-de-fora.py", "print('de fora')\n");
        Path link = projeto.resolve("atalho.py");
        try {
            Files.createSymbolicLink(link, fora);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            assumeTrue(false, "este usuario nao pode criar ligacao simbolica: " + e);
        }
        String m = refused(() -> workshop.openFolder(projeto.toString()));
        assertTrue(m.contains("AGT-PAC-071") && m.contains("atalho.py"), m);
        Files.delete(link);

        // ligacao para uma PASTA de fora
        Path pastaFora = Files.createDirectories(tmp.resolve("pasta-de-fora"));
        write(pastaFora, "x.py", "x = 1\n");
        Path linkPasta = projeto.resolve("lib");
        Files.createSymbolicLink(linkPasta, pastaFora);
        assertTrue(refused(() -> workshop.openFolder(projeto.toString())).contains("AGT-PAC-071"));
        Files.delete(linkPasta);

        // dentro do que ja fica fora (ambiente virtual) a ligacao nem e lida
        Files.createDirectories(projeto.resolve(".venv"));
        Path interno = projeto.resolve(".venv/python.py");
        Files.createSymbolicLink(interno, fora);
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertEquals(List.of("src/app/main.py", "util.py"), d.pythonFiles());
        } finally {
            Files.delete(interno);
        }
        assertEquals(List.of(), names(dados.resolve("importacao")), "a recusa nao deixa copia de trabalho");
    }

    @Test
    void juncaoDoWindowsQueSaiDaPastaERecusada() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"), "juncao so existe no Windows");
        Path pastaFora = Files.createDirectories(tmp.resolve("pasta-de-fora"));
        write(pastaFora, "x.py", "x = 1\n");
        Path juncao = projeto.resolve("lib");
        boolean criada;
        try {
            Process pr = new ProcessBuilder("cmd", "/c", "mklink", "/J", juncao.toString(), pastaFora.toString()).redirectErrorStream(true).start();
            pr.getInputStream().readAllBytes();
            criada = pr.waitFor(20, TimeUnit.SECONDS) && pr.exitValue() == 0 && Files.exists(juncao.resolve("x.py"));
        } catch (IOException e) {
            criada = false;
        }
        assumeTrue(criada, "nao foi possivel criar a juncao");
        try {
            String m = refused(() -> workshop.openFolder(projeto.toString()));
            assertTrue(m.contains("AGT-PAC-071") && m.contains("lib"), m);
            assertEquals(List.of(), names(dados.resolve("importacao")));
        } finally {
            Files.delete(juncao); // remove so a juncao; a pasta de fora continua
        }
        assertTrue(Files.exists(pastaFora.resolve("x.py")));
    }

    // ------------------------------------------------------------------ dependencias, origem mudando

    @Test
    void dependenciasDesconhecidasPedemConfirmacao() throws Exception {
        Files.delete(projeto.resolve("requirements.txt"));
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertTrue(d.dependenciesNeedConfirmation());
            assertEquals(List.of(), d.dependencies());
            String m = refused(() -> workshop.summary(d, "x", null, "util.py", PY, null, false));
            assertTrue(m.contains("AGT-PAC-042"), m);
            assertTrue(refused(() -> workshop.generate(d, "x", null, "util.py", PY, null, false, saida.resolve("d.zip").toString(), false, SafeZip.Cancel.NEVER))
                    .contains("AGT-PAC-042"));
            assertEquals(List.of(), names(saida));
            assertEquals(List.of(), workshop.summary(d, "x", null, "util.py", PY, List.of(), true).dependencies(), "so com a confirmacao da pessoa");
        }
    }

    @Test
    void arquivoMudadoDepoisDeAbrirRecusaEPedeParaAbrirDeNovo() throws Exception {
        write(projeto, ".env", "A=1\n");
        Path destino = saida.resolve("m.zip");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            // o que nao entra no pacote pode mudar
            Files.writeString(projeto.resolve(".env"), "A=2 e mais\n");
            write(projeto, "novo-depois.txt", "nao estava no retrato\n");
            assertEquals(4, gerar(d, destino.toString(), false).fileCount());
            Files.delete(destino);

            // mesmo tamanho e mesma data, conteudo diferente: o conteudo e conferido
            Path util = projeto.resolve("util.py");
            FileTime data = Files.getLastModifiedTime(util);
            Files.writeString(util, "def soma(a, b):\n    return a - b\n");
            Files.setLastModifiedTime(util, data);
            String m = refused(() -> gerar(d, destino.toString(), false));
            assertTrue(m.contains("AGT-PAC-072") && m.contains("Abra a pasta de novo"), m);
            assertEquals(List.of(), names(saida), "nem destino nem temporario");
        }
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            Files.delete(projeto.resolve("README.md"));
            assertTrue(refused(() -> gerar(d, destino.toString(), false)).contains("AGT-PAC-072"));
        }
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            // mudou entre a conferencia do ZIP gravado e a troca de nome
            Packager.beforeMove = () -> {
                try {
                    Files.writeString(projeto.resolve("util.py"), "def soma(a, b):\n    return a * b * 1000\n");
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            };
            assertTrue(refused(() -> gerar(d, destino.toString(), false)).contains("AGT-PAC-072"));
        }
        assertEquals(List.of(), names(saida));
        Workshop.Draft fechado = workshop.openFolder(projeto.toString());
        fechado.close();
        Packager.beforeMove = () -> {};
        assertFalse(refused(() -> gerar(fechado, destino.toString(), false)).isBlank());
        assertEquals(List.of(), names(saida));
    }

    // ------------------------------------------------------------------ cancelamento, falha, destino

    @Test
    void cancelamentoEFalhaNaoDeixamDestinoNemTemporario() throws Exception {
        Path destino = saida.resolve("c.zip");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            String m = refused(() -> workshop.generate(d, "x", null, "util.py", PY, null, true, destino.toString(), false, () -> true));
            assertTrue(m.contains("AGT-PAC-084") && m.contains("a pasta do projeto não foi alterada"), m);
            assertEquals(List.of(), names(saida));
            // cancelado no meio da gravacao, em cada ponto possivel
            for (int ponto = 1; ponto <= 8; ponto++) {
                AtomicInteger chamadas = new AtomicInteger();
                int limite = ponto;
                try {
                    workshop.generate(d, "x", null, "util.py", PY, null, true, destino.toString(), false, () -> chamadas.incrementAndGet() > limite);
                    Files.delete(destino); // o cancelamento nao foi mais consultado: terminou inteiro
                } catch (Workshop.Refused e) {
                    assertTrue(e.getMessage().contains("AGT-PAC-084"), e.getMessage());
                }
                assertEquals(List.of(), names(saida), "ponto " + ponto);
            }
            // disco cheio ao gravar
            Packager.opener = t -> {
                throw new IOException("There is not enough space on the disk");
            };
            assertTrue(refused(() -> gerar(d, destino.toString(), false)).contains("AGT-PAC-051"));
            assertEquals(List.of(), names(saida));
            // gravacao cortada: o que ficou nao passa na conferencia e e descartado
            Packager.opener = t -> new java.io.FilterOutputStream(Packager.Opener.FILES.open(t)) {
                private int n;

                @Override
                public void write(byte[] b, int off, int len) throws IOException {
                    if (n++ == 0) out.write(b, off, Math.min(len, 40));
                }
            };
            assertTrue(refused(() -> gerar(d, destino.toString(), false)).contains("AGT-PAC-053"));
            assertEquals(List.of(), names(saida));
            Packager.opener = Packager.Opener.FILES;
            // um destino existente continua intacto quando a geracao falha, mesmo com o consentimento de substituir
            Files.writeString(destino, "arquivo anterior");
            Packager.opener = t -> {
                throw new IOException("em uso");
            };
            assertFalse(refused(() -> gerar(d, destino.toString(), true)).isBlank());
            assertEquals("arquivo anterior", Files.readString(destino));
            assertEquals(List.of("c.zip"), names(saida));
        }
    }

    @Test
    void destinoExistenteSoESubstituidoComConsentimento() throws Exception {
        Path destino = saida.resolve("existe.zip");
        Files.writeString(destino, "outro arquivo da pessoa");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertTrue(workshop.exists(destino.toString()));
            assertFalse(workshop.exists(saida.resolve("nao-existe.zip").toString()));
            assertFalse(workshop.exists(null));
            Workshop.DestinationExists e = assertThrows(Workshop.DestinationExists.class, () -> gerar(d, destino.toString(), false));
            assertTrue(e.getMessage().contains("AGT-PAC-086") && e.getMessage().contains("existe.zip"), e.getMessage());
            assertEquals("outro arquivo da pessoa", Files.readString(destino), "nada foi substituido");
            assertEquals(List.of("existe.zip"), names(saida));

            Workshop.Generated g = gerar(d, destino.toString(), true);
            assertEquals(g.fingerprint(), validated(destino.toString()).fingerprint());
            assertEquals(List.of("existe.zip"), names(saida));

            // destino que nao e um .zip numa pasta que existe
            for (String ruim : new String[] {null, "", saida.resolve("pacote.tar").toString(), saida.resolve("nao-ha/p.zip").toString(), saida.toString()}) {
                Workshop.Refused r = assertThrows(Workshop.Refused.class, () -> gerar(d, ruim, true));
                assertFalse(r instanceof Workshop.DestinationExists);
                assertTrue(r.getMessage().contains("AGT-PAC-085"), r.getMessage());
            }
            Path pastaZip = Files.createDirectories(saida.resolve("pasta.zip"));
            assertTrue(refused(() -> gerar(d, pastaZip.toString(), true)).contains("AGT-PAC-085"));
        }
    }

    // ------------------------------------------------------------------ limites, nomes, pasta invalida

    private Workshop comLimites(ZipLimits limites) {
        return new Workshop(new LocalPackages(LocalPackages.defaultRoot(dados), AtomicWriter.DEFAULT, Clock.systemUTC()),
                new ProjectImporter(ProjectImporter.defaultRoot(dados), limites));
    }

    @Test
    void limitesDaPastaSaoOsDoZipEOQueFicaForaNaoConta() throws Exception {
        ZipLimits d = ZipLimits.DEFAULT;
        // 4 arquivos no projeto: limite de 3
        String muitos = refused(() -> comLimites(new ZipLimits(d.zipBytes(), 3, d.totalBytes(), d.fileBytes(), d.depth(), d.ratio(), d.ratioFloor()))
                .openFolder(projeto.toString()));
        assertTrue(muitos.contains("AGT-PAC-073"), muitos);
        String grande = refused(() -> comLimites(new ZipLimits(d.zipBytes(), d.entries(), d.totalBytes(), 30, d.depth(), d.ratio(), d.ratioFloor()))
                .openFolder(projeto.toString()));
        assertTrue(grande.contains("AGT-PAC-074") && grande.contains("main.py"), grande);
        String total = refused(() -> comLimites(new ZipLimits(d.zipBytes(), d.entries(), 60, d.fileBytes(), d.depth(), d.ratio(), d.ratioFloor()))
                .openFolder(projeto.toString()));
        assertTrue(total.contains("AGT-PAC-075"), total);
        String fundo = refused(() -> comLimites(new ZipLimits(d.zipBytes(), d.entries(), d.totalBytes(), d.fileBytes(), 2, d.ratio(), d.ratioFloor()))
                .openFolder(projeto.toString()));
        assertTrue(fundo.contains("AGT-PAC-077"), fundo);
        assertEquals(List.of(), names(dados.resolve("importacao")));

        // ambiente virtual, cache, credenciais e dados nao contam: com eles a pasta continua dentro do limite de 4
        sujeira();
        for (int i = 0; i < 20; i++) write(projeto, ".venv/Lib/m" + i + ".py", "x".repeat(500));
        write(projeto, "dados/grande.csv", "y".repeat(5000));
        Workshop justo = comLimites(new ZipLimits(d.zipBytes(), 4, 200, 100, d.depth(), d.ratio(), d.ratioFloor()));
        try (Workshop.Draft draft = justo.openFolder(projeto.toString())) {
            assertEquals(4, justo.summary(draft, "x", null, "util.py", PY, null, true).included().size());
        }
    }

    @Test
    void limiteDoPacoteGeradoContinuaValendo() throws Exception {
        for (int i = 0; i < 500; i++) write(projeto, "modulos/m" + i + ".py", "x = " + i + "\n");
        try (Workshop.Draft d = workshop.openFolder(projeto.toString())) {
            assertTrue(refused(() -> workshop.summary(d, "x", null, "util.py", PY, null, true)).contains("AGT-PAC-040"));
        }
    }

    @Test
    void nomesQueOWindowsNaoDistingueOuNaoAceitaSaoRecusados() throws Exception {
        Map<String, Boolean> vistos = new HashMap<>();
        ProjectImporter.checkFolderName("src/Util.py", false, vistos);
        ProjectImporter.checkFolderName("src/outro.py", false, vistos);
        for (String ruim : List.of("src/util.py", "SRC/UTIL.PY", "con.py", "lib/aux", "a:b.py", "fim. ", "nome?.py")) {
            ImportProblem p = assertThrows(ImportProblem.class, () -> ProjectImporter.checkFolderName(ruim, false, vistos), ruim);
            assertEquals(ImportProblem.Code.FOLDER_NAME, p.code());
        }
    }

    @Test
    void soUmaPastaQueExisteComPythonViraPacote() throws Exception {
        assertTrue(refused(() -> workshop.openFolder(tmp.resolve("nao-existe").toString())).contains("AGT-PAC-070"));
        assertTrue(refused(() -> workshop.openFolder(projeto.resolve("util.py").toString())).contains("AGT-PAC-070"));
        assertTrue(refused(() -> workshop.openFolder(null)).contains("AGT-PAC-070"));
        assertTrue(refused(() -> workshop.openFolder("  ")).contains("AGT-PAC-070"));
        // uma pasta do proprio aplicativo nao e projeto
        Files.createDirectories(dados.resolve("importacao/qualquer"));
        assertTrue(refused(() -> workshop.openFolder(dados.resolve("importacao/qualquer").toString())).contains("AGT-PAC-083"));

        Path semPython = Files.createDirectories(tmp.resolve("sem-python"));
        write(semPython, "leia.txt", "nada de codigo\n");
        write(semPython, ".venv/x.py", "so no ambiente virtual\n");
        try (Workshop.Draft d = workshop.openFolder(semPython.toString())) {
            assertEquals(List.of(), d.pythonFiles());
            assertTrue(d.blockers().get(0).contains("AGT-PAC-079"), d.blockers().toString());
            assertTrue(refused(() -> workshop.summary(d, "x", null, "leia.txt", PY, List.of(), true)).contains("AGT-PAC-079"));
        }
        // o fluxo antigo do ZIP nao passa pelo resumo da pasta
        try (Workshop.Draft zip = workshop.open(PackFixtures.codeZip(tmp.resolve("zips"), "minimo").toString())) {
            assertFalse(zip.folder());
            assertFalse(refused(() -> workshop.summary(zip, "x", null, "main.py", PY, List.of(), true)).isBlank());
        }
    }
}
