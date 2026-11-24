package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.env.ManagedEnvironments;
import com.nekkora.client.env.WheelIndex;
import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.PackFixtures;
import com.nekkora.client.pack.Workshop;
import com.nekkora.client.remote.RemoteMachine;
import com.nekkora.client.run.ApprovalLedger;
import com.nekkora.client.run.RunController;
import com.nekkora.client.run.RunStore;
import com.nekkora.client.run.Runner;
import com.nekkora.client.run.SessionProbe;

/**
 * AMBIENTE-PYTHON-001 - de ponta a ponta DESTE lado, com Python de verdade (exige {@code NEKKORA_TEST_PYTHON}) e um indice
 * local: a ponte ({@link RemoteWork}) resolve, prepara o ambiente da tentativa e a automacao roda NELE ({@code ambiente\Scripts\
 * python.exe -E -s -B -u}), importando um pacote que so existe no ambiente; no fim o ambiente sai. O Python do computador nao
 * recebe nada. A Platform fica de fora (ver {@code ManagedEnvironmentDeliveryTest}).
 */
class ManagedEnvironmentWorkTest {

    private static final String ENTRY = String.join("\n",
            "import os, pathlib, sys",
            "import nekkora_fixture_b",
            "out = pathlib.Path(os.environ['NEKKORA_SAIDA'])",
            "(out / 'resultado.txt').write_text(nekkora_fixture_b.VALUE, encoding='utf-8')",
            "(out / 'prefixo.txt').write_text(sys.prefix, encoding='utf-8')",
            "");

    @TempDir
    Path tmp;
    Path python;
    Path data;
    Runner runner;
    RemoteWork work;
    ManagedEnvironments environments;
    WheelIndex index;
    final AtomicInteger saved = new AtomicInteger(-1);
    final String org = UUID.randomUUID().toString();
    final String workspace = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        String configured = System.getenv("NEKKORA_TEST_PYTHON");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "defina NEKKORA_TEST_PYTHON para os testes com Python de verdade");
        python = Path.of(configured).toRealPath();
        index = new WheelIndex();
        data = tmp.resolve("dados");
        Workshop workshop = new Workshop(data);
        SessionProbe session = () -> new SessionProbe.Session(true, 1, false, null);
        RunController controller = new RunController(new RunStore(RunStore.defaultRoot(data), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT), session, Clock.systemUTC(), data);
        runner = new Runner(workshop.packages(), controller);
        runner.enableManaged(data.resolve("remota"));
        environments = new ManagedEnvironments(data.resolve("remota"), index.url(), () -> ManagedEnvironments.DEFAULT_CACHE_MIB);
        work = new RemoteWork(runner, environments, mib -> {
            saved.set(mib);
            return true;
        });
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
        if (index != null) {
            index.close();
        }
    }

    private String pythonId() {
        return "computador:" + python.toString().toLowerCase(Locale.ROOT);
    }

    private RemoteMachine.Job job(String attempt, List<String> dependencies, String environmentPython) throws Exception {
        Path dir = Path.of(work.dir(attempt).orElseThrow());
        Files.createDirectories(dir);
        Path built = PackFixtures.packageZip(Files.createDirectories(tmp.resolve("gerado-" + attempt)), "robo-gerenciado", Map.of("main.py", ENTRY), "main.py",
                dependencies);
        Path zip = dir.resolve("pacote.zip");
        Files.copy(built, zip);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        String fingerprint = new Workshop(tmp.resolve("conferencia-" + attempt)).inspectLocal(built.toString()).fingerprint().replace("sha256:", "");
        return new RemoteMachine.Job(attempt, org, workspace, UUID.randomUUID().toString(), UUID.randomUUID().toString(), zip.toString(), sha, fingerprint,
                dependencies, pythonId(), dir.toString(), null, environmentPython);
    }

    private RemoteMachine.Progress waitEnd(String run) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            RemoteMachine.Progress p = work.progress(run).orElseThrow();
            if (!"RUNNING".equals(p.state()) && !"ALIVE".equals(work.processes(run))) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("nao terminou no prazo do teste");
    }

    @Test
    void resolvePreparaEExecutaNoAmbienteDaTentativaENoFimOAmbienteSai() throws Exception {
        assertTrue(work.managesEnvironments());
        List<String> deps = List.of("nekkora-fixture-b==2.0.0");
        RemoteMachine.Resolved resolved = work.resolveEnvironment(UUID.randomUUID().toString(), pythonId(), deps, () -> false);
        assertEquals("RESOLVED", resolved.outcome(), String.valueOf(resolved.failure()));
        assertEquals(List.of("nekkora-fixture-a", "nekkora-fixture-b"), resolved.lock().stream().map(RemoteMachine.LockItem::name).toList());

        String attempt = UUID.randomUUID().toString();
        String dir = work.dir(attempt).orElseThrow();
        List<String> stages = new CopyOnWriteArrayList<>();
        RemoteMachine.Prepared prepared = work.prepareEnvironment(new RemoteMachine.PrepareJob(attempt, dir, pythonId(), resolved.lock(), deps, List.of()),
                stages::add, () -> false);
        assertEquals("READY", prepared.outcome(), prepared.failure() + "\n" + prepared.log());
        assertEquals(List.of("ENV_CREATING", "DEPS_INSTALLING"), stages);
        Path env = Path.of(dir).resolve("ambiente");
        assertEquals(env.resolve("Scripts").resolve("python.exe").toString(), prepared.python());

        // a automacao roda com o interpretador do ambiente: o pacote so existe ali, e o pacote declara a mesma dependencia
        RemoteMachine.Job job = job(attempt, deps, prepared.python());
        String run = work.start(job);
        RemoteMachine.Progress end = waitEnd(run);
        assertEquals("SUCCEEDED", end.state());
        Path out = Path.of(dir).resolve("saida").resolve(run);
        assertEquals("b-2.0.0+a-1.2.3", Files.readString(out.resolve("resultado.txt")));
        assertEquals(env.toRealPath().toString().toLowerCase(Locale.ROOT), Path.of(Files.readString(out.resolve("prefixo.txt"))).toRealPath().toString()
                .toLowerCase(Locale.ROOT), "sys.prefix e o do ambiente da tentativa");
        // o Python do computador continua sem o pacote
        assertFalse(Files.exists(python.getParent().resolve("Lib").resolve("site-packages").resolve("nekkora_fixture_b")));
        // fim: o codigo e o ambiente saem; a saida fica
        work.discard(attempt, run);
        assertFalse(Files.exists(env), "o ambiente da tentativa e apagado no fim");
        assertTrue(Files.exists(out.resolve("resultado.txt")));
    }

    @Test
    void oInterpretadorPreparadoSoValeDentroDoAmbienteDaPropriaTentativa() throws Exception {
        String attempt = UUID.randomUUID().toString();
        RemoteMachine.Job outside = job(attempt, List.of(), python.toString());
        assertEquals("ENVIRONMENT_GONE", assertThrows(RemoteMachine.WorkRefused.class, () -> work.start(outside)).reason());
        String other = UUID.randomUUID().toString();
        RemoteMachine.Job missing = job(other, List.of(), Path.of(work.dir(other).orElseThrow()).resolve("ambiente").resolve("Scripts").resolve("python.exe").toString());
        assertEquals("ENVIRONMENT_GONE", assertThrows(RemoteMachine.WorkRefused.class, () -> work.start(missing)).reason());
        assertFalse(runner.busy());
        assertNotNull(ErrorCode.EXEC_PYTHON_INVALID);
    }

    @Test
    void depoisDeUmaQuedaODescarteDaTentativaEncerraOPipQueSobrouEApagaOAmbiente() throws Exception {
        // a tentativa em preparo quando o aplicativo caiu: o "pip" (um Python de verdade, parado) segue vivo, com a marca gravada
        String attempt = UUID.randomUUID().toString();
        Path dir = Path.of(work.dir(attempt).orElseThrow());
        Path env = Files.createDirectories(dir.resolve("ambiente").resolve("Lib"));
        Path prep = Files.createDirectories(dir.resolve("preparo"));
        Process sobra = new ProcessBuilder(python.toString(), "-I", "-c", "import time; time.sleep(120)").directory(prep.toFile()).start();
        try {
            Files.writeString(prep.resolve("pip.processo"), sobra.pid() + " " + sobra.toHandle().info().startInstant().orElseThrow().toEpochMilli());
            // o caminho da recuperacao (ENV_PREPARING -> recusa) e o da varredura: o descarte da tentativa pela ponte
            work.discard(attempt, null);
            assertTrue(sobra.waitFor(15, java.util.concurrent.TimeUnit.SECONDS), "o pip que sobrou da queda foi encerrado");
            assertFalse(Files.exists(env.getParent()), "o ambiente da tentativa saiu");
        } finally {
            sobra.destroyForcibly();
        }
    }

    @Test
    void aVarreduraTiraOAmbienteQueSobrouMasNuncaOQueEstaEmPreparoEOLimiteDoCacheEGravado() throws Exception {
        // sobra de uma queda: a pasta de ambiente de uma tentativa que nao e a pendente sai na varredura
        String leftover = UUID.randomUUID().toString();
        Path env = Files.createDirectories(Path.of(work.dir(leftover).orElseThrow()).resolve("ambiente").resolve("Lib"));
        Files.writeString(env.resolve("x.py"), "sobra");
        // e a de uma tentativa que so ficou com a pasta de trabalho do preparo (o registro do pip estava preso quando o ambiente saiu)
        String soPreparo = UUID.randomUUID().toString();
        Path prep = Files.createDirectories(Path.of(work.dir(soPreparo).orElseThrow()).resolve("preparo"));
        Files.writeString(prep.resolve("pip.saida"), "registro");
        assertTrue(work.sweep(null) >= 2);
        assertFalse(Files.exists(env.getParent()));
        assertFalse(Files.exists(prep));
        // a tentativa pendente (a do diario) nunca entra na varredura
        String kept = UUID.randomUUID().toString();
        Path keptEnv = Files.createDirectories(Path.of(work.dir(kept).orElseThrow()).resolve("ambiente"));
        work.sweep(kept);
        assertTrue(Files.exists(keptEnv));
        // o cache: limite gravado pela ponte, dentro da faixa
        assertTrue(work.environmentCacheLimit(512));
        assertEquals(512, saved.get());
        assertFalse(work.environmentCacheLimit(20481));
        assertFalse(work.environmentCacheLimit(-1));
        assertEquals(ManagedEnvironments.DEFAULT_CACHE_MIB, work.environmentCache()[1]);
        assertTrue(work.clearEnvironmentCache());
    }
}
