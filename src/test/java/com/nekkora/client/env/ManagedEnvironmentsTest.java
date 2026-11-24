package com.nekkora.client.env;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * AMBIENTE-PYTHON-001 - a camada {@code env} com um Python de VERDADE (exige {@code NEKKORA_TEST_PYTHON}) e um indice local
 * ({@link WheelIndex}, em 127.0.0.1): resolucao com dependencia indireta, incompativel, nao encontrado, conflito, indice fora do ar;
 * preparo de ponta a ponta com conferencia e verificacao externa; falha de SHA-256; cancelamento; cache (limite 0 e poda); varredura
 * sem seguir juncao. NADA e instalado no Python do computador: so ambientes temporarios dentro da pasta do teste.
 */
class ManagedEnvironmentsTest {

    @TempDir
    Path tmp;
    Path python;
    WheelIndex index;
    final AtomicInteger cacheMiB = new AtomicInteger(ManagedEnvironments.DEFAULT_CACHE_MIB);

    @BeforeEach
    void setUp() throws Exception {
        String configured = System.getenv("NEKKORA_TEST_PYTHON");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "defina NEKKORA_TEST_PYTHON para os testes com Python de verdade");
        python = Path.of(configured).toRealPath();
        index = new WheelIndex();
    }

    @AfterEach
    void tearDown() {
        if (index != null) {
            index.close();
        }
    }

    private ManagedEnvironments environments(String indexUrl) {
        return new ManagedEnvironments(tmp.resolve("remota"), indexUrl, cacheMiB::get);
    }

    @Test
    void aSaidaDoPythonChegaEmUtf8MesmoComAcentos() throws Exception {
        EnvCommand.Ran r = EnvCommand.run(python, java.util.List.of("-c", "import sys; print(sys.flags.utf8_mode, sys.flags.isolated, 'conex\u00e3o for\u00e7ada')"),
                tmp.resolve("utf8"), "utf8", java.time.Duration.ofSeconds(60), () -> false, 4096);
        assertTrue(r.ok(), r.output());
        assertEquals("1 1 conexão forçada", r.output().strip());
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Test
    void aResolucaoTrazODependenteIndiretoComSha256EArquivoEApagaAPastaTemporaria() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        String rid = id();
        ManagedEnvironments.Resolution r = envs.resolve(rid, python, List.of("nekkora-fixture-b==2.0.0"), () -> false);
        assertEquals("RESOLVED", r.outcome(), String.valueOf(r.failure()));
        assertNull(r.failure());
        assertTrue(r.pythonVersion().matches("3\\.[0-9]+\\.[0-9]+"));
        assertTrue(ManagedEnvironments.atLeast(r.pipVersion(), new int[] {22, 2}), r.pipVersion());
        WheelIndex.Dist a = index.dist("nekkora_fixture_a-1.2.3-py3-none-any.whl");
        WheelIndex.Dist b = index.dist("nekkora_fixture_b-2.0.0-py3-none-any.whl");
        assertEquals(List.of(new ManagedEnvironments.LockItem("nekkora-fixture-a", "1.2.3", a.sha256(), a.file()),
                new ManagedEnvironments.LockItem("nekkora-fixture-b", "2.0.0", b.sha256(), b.file())), r.lock(), "a dependencia indireta entra no lock");
        assertFalse(Files.exists(envs.resolutionsDir().resolve(rid)), "a pasta da resolucao e apagada no fim");
        // o pip recebe o cache em remota\cache-pip (ele so guarda respostas HTTPS; o indice deste teste e HTTP)
        assertEquals(List.of("--cache-dir", envs.cacheDir().toString()), envs.cacheArgs());
        cacheMiB.set(0);
        assertEquals(List.of("--no-cache-dir"), envs.cacheArgs());
        assertFalse(envs.busy());
    }

    @Test
    void semWheelNaoEncontradoEConflitoSaoIncompativeisComAFaseCerta() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        ManagedEnvironments.Resolution sdist = envs.resolve(id(), python, List.of("nekkora-fixture-c==1.0.0"), () -> false);
        assertEquals("INCOMPATIBLE", sdist.outcome());
        assertEquals("NO_COMPATIBLE_WHEEL", sdist.failure().phase());
        assertEquals("nekkora-fixture-c==1.0.0", sdist.failure().requirement());
        assertTrue(sdist.failure().summary().length() <= 500 && !sdist.failure().summary().isBlank());
        assertTrue(sdist.lock().isEmpty());

        ManagedEnvironments.Resolution missing = envs.resolve(id(), python, List.of("nekkora-fixture-z==1.0.0"), () -> false);
        assertEquals("INCOMPATIBLE", missing.outcome());
        assertEquals("NOT_FOUND", missing.failure().phase());
        assertEquals("nekkora-fixture-z==1.0.0", missing.failure().requirement());

        ManagedEnvironments.Resolution conflict = envs.resolve(id(), python, List.of("nekkora-fixture-a==1.2.3", "nekkora-fixture-e==1.0.0"), () -> false);
        assertEquals("INCOMPATIBLE", conflict.outcome());
        assertEquals("RESOLUTION_CONFLICT", conflict.failure().phase());
        try (var s = Files.list(envs.resolutionsDir())) {
            assertEquals(0, s.count(), "nenhuma pasta de resolucao fica");
        }
    }

    @Test
    void indiceForaDoArNaoResolveComIndexUnreachable() throws Exception {
        // um "indice" que aceita a conexao e a derruba sem responder (sem depender de uma porta fechada, que outro teste poderia ocupar)
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByAddress(new byte[] {127, 0, 0, 1}))) {
            Thread drop = new Thread(() -> {
                while (!s.isClosed()) {
                    try {
                        s.accept().close();
                    } catch (java.io.IOException e) {
                        return;
                    }
                }
            }, "indice-fora-do-ar");
            drop.setDaemon(true);
            drop.start();
            ManagedEnvironments envs = environments("http://127.0.0.1:" + s.getLocalPort() + "/simple");
            ManagedEnvironments.Resolution r = envs.resolve(id(), python, List.of("nekkora-fixture-a==1.2.3"), () -> false);
            assertEquals("NOT_RESOLVED", r.outcome());
            assertEquals("INDEX_UNREACHABLE", r.failure().phase(), r.failure().summary());
        }
    }

    @Test
    void oPreparoInstalaExatamenteOLockConfereEVerificaONavegadorSemInstalarNada() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        ManagedEnvironments.Resolution r = envs.resolve(id(), python, List.of("nekkora-fixture-b==2.0.0", "playwright==1.0.0"), () -> false);
        assertEquals("RESOLVED", r.outcome(), String.valueOf(r.failure()));
        Path attempt = tmp.resolve("remota").resolve("tentativas").resolve(id());
        List<String> stages = new CopyOnWriteArrayList<>();
        ManagedEnvironments.Preparation p = envs.prepare(attempt, python, r.lock(), List.of("nekkora-fixture-b==2.0.0"), List.of("PLAYWRIGHT_CHROMIUM"),
                stages::add, () -> false);
        assertEquals("READY", p.outcome(), p.failure() + "\n" + p.log());
        assertEquals(List.of("ENV_CREATING", "DEPS_INSTALLING"), stages);
        assertEquals(r.lock(), p.installed());
        assertTrue(p.createMs() > 0 && p.installMs() > 0);
        assertEquals(attempt.resolve("ambiente").resolve("Scripts").resolve("python.exe").toAbsolutePath().normalize(), p.python());
        assertTrue(Files.isRegularFile(p.python()));
        assertTrue(p.log().contains("Ambiente pronto") && p.log().getBytes(StandardCharsets.UTF_8).length <= 64 * 1024);
        assertFalse(Files.exists(attempt.resolve("preparo")), "a pasta de trabalho do preparo sai");
        // o Python do computador nao recebeu nada: o pacote so existe no ambiente
        assertFalse(Files.exists(python.getParent().resolve("Lib").resolve("site-packages").resolve("nekkora_fixture_a")));
        // um navegador que nao esta na maquina: prerequisito externo, e o ambiente sai
        Path other = tmp.resolve("remota").resolve("tentativas").resolve(id());
        ManagedEnvironments.Preparation firefox = envs.prepare(other, python, r.lock(), List.of(), List.of("PLAYWRIGHT_FIREFOX"), s -> {
        }, () -> false);
        assertEquals("FAILED", firefox.outcome());
        assertEquals("EXTERNAL_PREREQUISITE_MISSING", firefox.refusal());
        assertEquals("PREREQUISITE", firefox.failure().phase());
        assertEquals("playwright==1.0.0", firefox.failure().requirement());
        assertTrue(firefox.failure().summary().contains("firefox"), firefox.failure().summary());
        assertFalse(Files.exists(other.resolve("ambiente")), "fora de READY o ambiente e apagado");
        assertTrue(envs.discard(attempt));
        assertFalse(Files.exists(attempt.resolve("ambiente")));
    }

    @Test
    void arquivoComOutroSha256NaoEInstaladoEOAmbienteSai() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        WheelIndex.Dist a = index.dist("nekkora_fixture_a-1.2.3-py3-none-any.whl");
        Path attempt = tmp.resolve("tentativas").resolve(id());
        ManagedEnvironments.Preparation p = envs.prepare(attempt, python, List.of(new ManagedEnvironments.LockItem("nekkora-fixture-a", "1.2.3", "0".repeat(64), a.file())),
                List.of(), List.of(), s -> {
                }, () -> false);
        assertEquals("FAILED", p.outcome());
        assertEquals("DEPENDENCY_INSTALL_FAILED", p.refusal());
        assertEquals("HASH_MISMATCH", p.failure().phase());
        assertEquals("nekkora-fixture-a==1.2.3", p.failure().requirement());
        assertTrue(p.log().contains("HASHES"), p.log());
        assertFalse(Files.exists(attempt.resolve("ambiente")));
        assertNull(p.python());
    }

    @Test
    void cancelarDuranteOPreparoEncerraOPipEApagaOAmbiente() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        WheelIndex.Dist a = index.dist("nekkora_fixture_a-1.2.3-py3-none-any.whl");
        index.slow = new CountDownLatch(1);
        Path attempt = tmp.resolve("tentativas").resolve(id());
        AtomicBoolean cancel = new AtomicBoolean();
        CountDownLatch installing = new CountDownLatch(1);
        AtomicReference<ManagedEnvironments.Preparation> out = new AtomicReference<>();
        Thread t = new Thread(() -> out.set(envs.prepare(attempt, python, List.of(new ManagedEnvironments.LockItem("nekkora-fixture-a", "1.2.3", a.sha256(), a.file())),
                List.of(), List.of(), s -> {
                    if (s.equals("DEPS_INSTALLING")) {
                        installing.countDown();
                    }
                }, cancel::get)));
        t.start();
        assertTrue(installing.await(120, java.util.concurrent.TimeUnit.SECONDS));
        Thread.sleep(1500);
        // durante o preparo: nem a poda nem a limpeza do cache mexem em nada
        assertTrue(envs.busy());
        assertTrue(envs.active(attempt.resolve("ambiente")));
        assertFalse(envs.clearCache());
        assertEquals(0, envs.pruneCache());
        assertFalse(envs.discard(attempt), "o ambiente em preparo nao e apagado por fora");
        long asked = System.nanoTime();
        cancel.set(true);
        t.join(30_000);
        assertFalse(t.isAlive());
        assertTrue(Duration.ofNanos(System.nanoTime() - asked).toSeconds() < 15, "o pip e encerrado logo");
        assertEquals("CANCELLED", out.get().outcome());
        assertFalse(Files.exists(attempt.resolve("ambiente")));
        assertFalse(envs.busy());
    }

    @Test
    void limiteZeroDesligaOCacheEAPodaTiraOsMaisAntigosAte80PorCento() throws Exception {
        cacheMiB.set(0);
        ManagedEnvironments envs = environments(index.url());
        assertEquals("RESOLVED", envs.resolve(id(), python, List.of("nekkora-fixture-a==1.2.3"), () -> false).outcome());
        assertFalse(Files.exists(envs.cacheDir()), "com o limite 0 o pip roda com --no-cache-dir");
        // a poda: limite de 1 MiB, 10 arquivos de 200 KiB (2000 KiB); saem os mais antigos ate ficar abaixo de 80% (819 KiB)
        cacheMiB.set(1);
        Path c = Files.createDirectories(envs.cacheDir().resolve("http-v2"));
        for (int k = 0; k < 10; k++) {
            Path f = c.resolve("f" + k);
            Files.write(f, new byte[200 * 1024]);
            Files.setLastModifiedTime(f, FileTime.from(Instant.now().minusSeconds(1000 - k)));
        }
        int removed = envs.pruneCache();
        assertEquals(6, removed, "sobram 4 arquivos (800 KiB, abaixo de 819 KiB)");
        for (int k = 0; k < 10; k++) {
            assertEquals(k >= 6, Files.exists(c.resolve("f" + k)), "os mais antigos saem primeiro: f" + k);
        }
        assertTrue(envs.cacheBytes() < 0.8 * 1024 * 1024);
        // limite 0 com a maquina parada: o cache inteiro sai
        cacheMiB.set(0);
        envs.pruneCache();
        assertFalse(Files.exists(envs.cacheDir()));
        // limite fora da faixa vale o padrao
        cacheMiB.set(99999);
        assertEquals(ManagedEnvironments.DEFAULT_CACHE_MIB, envs.cacheLimitMiB());
    }

    @Test
    void aVarreduraTiraSobrasDeResolucaoSemSeguirJuncao() throws Exception {
        ManagedEnvironments envs = environments(index.url());
        Path outside = Files.createDirectories(tmp.resolve("fora"));
        Files.writeString(outside.resolve("importante.txt"), "fica");
        Path leftover = Files.createDirectories(envs.resolutionsDir().resolve(id()).resolve("venv"));
        Files.writeString(leftover.resolve("x.txt"), "sobra");
        Path junction = leftover.resolve("juncao");
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J", junction.toString(), outside.toString()).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        Assumptions.assumeTrue(p.waitFor() == 0 && Files.exists(junction.resolve("importante.txt")), "juncao nao pode ser criada aqui");
        assertEquals(1, envs.sweep());
        try (var s = Files.list(envs.resolutionsDir())) {
            assertEquals(0, s.count());
        }
        assertEquals("fica", Files.readString(outside.resolve("importante.txt")), "a limpeza nunca segue juncao");
    }
}
