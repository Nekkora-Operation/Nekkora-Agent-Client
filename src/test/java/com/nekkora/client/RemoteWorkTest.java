package com.nekkora.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03 - a ponte entre o canal da maquina e o motor, de ponta a ponta DESTE lado: um
 * pacote ZIP de verdade (gerado pelo empacotador do aplicativo), conferido, extraido e executado por um Python de verdade
 * (exige {@code NEKKORA_TEST_PYTHON}) como execucao gerenciada. A Platform e o download ficam de fora (ver
 * {@code RemoteDeliveryTest}).
 */
class RemoteWorkTest {

    private static final String OK = "import os, pathlib\npathlib.Path(os.environ['NEKKORA_SAIDA'], 'resultado.txt').write_text('ok', encoding='utf-8')\n";
    private static final String SLOW = "import time\ntime.sleep(120)\n";
    private static final String EXIT_3 = "import sys\nsys.exit(3)\n";

    @TempDir
    Path tmp;
    Path python;
    Path data;
    Runner runner;
    RemoteWork work;
    final AtomicBoolean locked = new AtomicBoolean();
    final String org = UUID.randomUUID().toString();
    final String workspace = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() throws Exception {
        String configured = System.getenv("NEKKORA_TEST_PYTHON");
        Assumptions.assumeTrue(configured != null && !configured.isBlank(), "defina NEKKORA_TEST_PYTHON para os testes com Python de verdade");
        python = Path.of(configured).toRealPath();
        data = tmp.resolve("dados");
        Workshop workshop = new Workshop(data);
        SessionProbe session = () -> new SessionProbe.Session(true, 1, locked.get(), locked.get() ? ErrorCode.EXEC_SESSION_LOCKED : null);
        RunController controller = new RunController(new RunStore(RunStore.defaultRoot(data), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT), session, Clock.systemUTC(), data);
        runner = new Runner(workshop.packages(), controller);
        runner.enableManaged(data.resolve("remota"));
        work = new RemoteWork(runner);
    }

    @AfterEach
    void tearDown() {
        if (runner != null) {
            runner.close();
        }
    }

    private String pythonId() {
        return "computador:" + python.toString().toLowerCase(Locale.ROOT);
    }

    /** Uma tentativa pronta para iniciar: a pasta dela e o ZIP "baixado" dentro, com o SHA-256 e a impressao verdadeiros. */
    private RemoteMachine.Job job(String script, List<String> dependencies) throws Exception {
        String attempt = UUID.randomUUID().toString();
        Path dir = Path.of(work.dir(attempt).orElseThrow());
        Files.createDirectories(dir);
        Path built = PackFixtures.packageZip(Files.createDirectories(tmp.resolve("gerado-" + attempt)), "robo-sintetico", Map.of("main.py", script), "main.py",
                List.of());
        Path zip = dir.resolve("pacote.zip");
        Files.copy(built, zip);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(zip)));
        String fingerprint = new Workshop(tmp.resolve("conferencia-" + attempt)).inspectLocal(built.toString()).fingerprint().replace("sha256:", "");
        return new RemoteMachine.Job(attempt, org, workspace, UUID.randomUUID().toString(), UUID.randomUUID().toString(), zip.toString(), sha, fingerprint,
                dependencies, pythonId(), dir.toString());
    }

    private RemoteMachine.Progress waitEnd(String run) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            RemoteMachine.Progress p = work.progress(run).orElseThrow();
            if (!"RUNNING".equals(p.state())) {
                return p;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("nao terminou no prazo do teste");
    }

    private String refusal(RemoteMachine.Job job) {
        return assertThrows(RemoteMachine.WorkRefused.class, () -> work.start(job)).reason();
    }

    @Test
    void doZipBaixadoAoResultadoSemAprovacaoLocalEForaDoHistoricoLocal() throws Exception {
        RemoteMachine.Job job = job(OK, List.of());
        assertFalse(work.busy());
        String run = work.start(job);
        RemoteMachine.Progress end = waitEnd(run);
        assertEquals("SUCCEEDED", end.state());
        assertEquals(0, end.exitCode());
        assertFalse(work.busy());
        // o que a automacao gravou esta na pasta de saida DA TENTATIVA
        Path out = Path.of(job.dir()).resolve("saida").resolve(run).resolve("resultado.txt");
        assertEquals("ok", Files.readString(out));
        // os resultados: so o que a automacao gravou, com o nome relativo a pasta de saida; o sinal do aplicativo fica de fora
        java.util.List<RemoteMachine.ResultFile> results = work.results(job.attemptId(), run);
        assertEquals(java.util.List.of("resultado.txt"), results.stream().map(RemoteMachine.ResultFile::name).toList());
        assertEquals(out.toString(), results.get(0).file());
        assertEquals(2, results.get(0).size());
        assertTrue(work.results(job.attemptId(), "ex-" + "0".repeat(32)).isEmpty(), "execucao que nao e desta tentativa");
        // nenhuma aprovacao local foi criada, e o historico das execucoes locais continua vazio
        assertFalse(Files.exists(ApprovalLedger.defaultFile(data)));
        assertFalse(Files.exists(RunStore.defaultRoot(data)), "nada em <dados>/execucoes");
        assertTrue(runner.current().isEmpty(), "a tela da execucao local nao ve a execucao gerenciada");
        assertTrue(Files.isDirectory(data.resolve("remota").resolve("execucoes").resolve(run)));
        // fim: o codigo da tentativa e apagado; a saida fica
        work.discard(job.attemptId(), run);
        assertFalse(Files.exists(Path.of(job.zip())));
        assertFalse(Files.exists(Path.of(job.dir()).resolve("pacote")));
        assertTrue(Files.exists(out));
    }

    @Test
    void aplicativoReabertoComAExecucaoEmAndamentoAdotaOProcessoENaoLancaOutro() throws Exception {
        RemoteMachine.Job job = job(SLOW, List.of());
        String run = work.start(job);
        assertEquals("RUNNING", work.progress(run).orElseThrow().state());
        // o aplicativo "cai" (o primeiro motor nao e encerrado) e abre de novo: outro motor sobre a MESMA pasta de dados
        Workshop workshop = new Workshop(data);
        SessionProbe session = () -> new SessionProbe.Session(true, 1, false, null);
        RunController reopened = new RunController(new RunStore(RunStore.defaultRoot(data), AtomicWriter.DEFAULT),
                new ApprovalLedger(ApprovalLedger.defaultFile(data), AtomicWriter.DEFAULT), session, Clock.systemUTC(), data);
        Runner second = new Runner(workshop.packages(), reopened);
        second.enableManaged(data.resolve("remota"));
        second.reconcile();
        RemoteWork again = new RemoteWork(second);
        try {
            // a execucao que estava rodando e reconhecida e ocupa a vaga: nada e lancado de novo
            assertEquals("RUNNING", again.progress(run).orElseThrow().state());
            assertTrue(again.busy());
            assertEquals("BUSY", assertThrows(RemoteMachine.WorkRefused.class, () -> again.start(job(OK, List.of()))).reason());
            try (java.util.stream.Stream<Path> s = Files.list(data.resolve("remota").resolve("execucoes"))) {
                assertEquals(1, s.count(), "uma execucao so: a interrompida nao foi duplicada");
            }
            // a varredura de sobras nao mexe em nada com uma execucao em andamento
            assertEquals(0, again.sweep(null));
            assertTrue(Files.exists(Path.of(job.zip())));
            // quem pediu manda parar: o processo adotado e encerrado e o fim e informado
            again.cancel(run);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(60).toNanos();
            while ("RUNNING".equals(again.progress(run).orElseThrow().state()) && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            // o processo parou. Neste teste o primeiro motor continua vivo (numa queda de verdade ele nao existiria) e pode gravar
            // o fim antes do segundo, como "falhou" (processo encerrado); o que importa aqui: terminou, e nao como sucesso
            String end = again.progress(run).orElseThrow().state();
            assertTrue(List.of("CANCELLED", "FAILED", "UNKNOWN").contains(end), end);
            assertFalse(again.busy());
        } finally {
            second.close();
        }
    }

    @Test
    void limpezaOCodigoSaiSempreASaidaSoQuandoPedidaEAVarreduraNuncaApagaResultado() throws Exception {
        RemoteMachine.Job job = job(OK, List.of());
        String run = work.start(job);
        assertEquals("SUCCEEDED", waitEnd(run).state());
        Path dir = Path.of(job.dir());
        Path copy = data.resolve("remota").resolve("execucoes").resolve(run).resolve("pacote");
        Path out = dir.resolve("saida").resolve(run).resolve("resultado.txt");
        assertTrue(Files.isDirectory(copy), "a copia que foi executada");
        // fim da tentativa: o ZIP, a copia extraida e a copia executada saem; o registro da execucao e a saida ficam
        work.discard(job.attemptId(), run);
        assertFalse(Files.exists(Path.of(job.zip())));
        assertFalse(Files.exists(dir.resolve("pacote")));
        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(copy.resolveSibling("estado.json")));
        assertTrue(Files.exists(out), "a saida nao e apagada pela limpeza do codigo");
        assertEquals("SUCCEEDED", work.progress(run).orElseThrow().state());
        // a saida so sai quando quem conduz a tentativa pede (com o recebimento confirmado); a pasta vazia some junto
        work.discardOutputs(job.attemptId());
        assertFalse(Files.exists(dir));

        // sobra de uma queda entre o desfecho e a limpeza: a varredura tira o codigo e deixa os resultados; a pendente fica intacta
        RemoteMachine.Job leftover = job(OK, List.of());
        Path old = Path.of(leftover.dir());
        Files.createDirectories(old.resolve("pacote"));
        Files.writeString(old.resolve("pacote").resolve("main.py"), "print(1)");
        Files.createDirectories(old.resolve("saida").resolve("ex-" + "2".repeat(32)));
        Files.writeString(old.resolve("saida").resolve("ex-" + "2".repeat(32)).resolve("nao-confirmado.txt"), "x");
        RemoteMachine.Job pending = job(OK, List.of());
        assertEquals(1, work.sweep(pending.attemptId()));
        assertFalse(Files.exists(Path.of(leftover.zip())));
        assertFalse(Files.exists(old.resolve("pacote")));
        assertTrue(Files.exists(old.resolve("saida").resolve("ex-" + "2".repeat(32)).resolve("nao-confirmado.txt")), "resultado sem confirmacao fica");
        assertTrue(Files.exists(Path.of(pending.zip())), "a tentativa pendente nao e tocada");
        // pasta que nao e de tentativa (nome fora do formato) nao e tocada
        Path foreign = Files.createDirectories(data.resolve("remota").resolve("tentativas").resolve("outra-coisa"));
        Files.writeString(foreign.resolve("pacote.zip"), "x");
        assertEquals(0, work.sweep(pending.attemptId()));
        assertTrue(Files.exists(foreign.resolve("pacote.zip")));
    }

    @Test
    void codigoDeSaidaCancelamentoEUmaPorVez() throws Exception {
        String failed = work.start(job(EXIT_3, List.of()));
        RemoteMachine.Progress end = waitEnd(failed);
        assertEquals("FAILED", end.state());
        assertEquals(3, end.exitCode());

        String slow = work.start(job(SLOW, List.of()));
        assertTrue(work.busy());
        assertEquals("RUNNING", work.progress(slow).orElseThrow().state());
        assertEquals("BUSY", refusal(job(OK, List.of())));
        // a tela local nao cancela o que a Platform pediu; quem pediu, sim
        assertFalse(runner.cancel());
        assertTrue(runner.running().isPresent(), "mas a salvaguarda de sair enxerga que o computador esta executando");
        work.cancel(slow);
        assertEquals("CANCELLED", waitEnd(slow).state());
        assertTrue(work.progress("ex-" + "0".repeat(32)).isEmpty(), "execucao que este computador nao conhece");
    }

    @Test
    void cadaConferenciaQueFalhaTemOSeuMotivoENadaEExecutado() throws Exception {
        // SHA-256 que nao e o do arquivo, ou impressao que nao e a do pacote: pacote invalido
        RemoteMachine.Job good = job(OK, List.of());
        assertEquals("PACKAGE_INVALID", refusal(new RemoteMachine.Job(good.attemptId(), org, workspace, good.automationId(), good.automationVersionId(),
                good.zip(), "0".repeat(64), good.fingerprint(), List.of(), pythonId(), good.dir())));
        RemoteMachine.Job other = job(OK, List.of());
        assertEquals("PACKAGE_INVALID", refusal(new RemoteMachine.Job(other.attemptId(), org, workspace, other.automationId(), other.automationVersionId(),
                other.zip(), other.sha256(), "0".repeat(64), List.of(), pythonId(), other.dir())));
        // o Python indicado nao existe (mais) neste computador: nenhum outro e usado
        RemoteMachine.Job gone = job(OK, List.of());
        assertEquals("ENVIRONMENT_GONE", refusal(new RemoteMachine.Job(gone.attemptId(), org, workspace, gone.automationId(), gone.automationVersionId(),
                gone.zip(), gone.sha256(), gone.fingerprint(), List.of(), "computador:c:\\nao-existe\\python.exe", gone.dir())));
        // dependencia indicada pela Platform que nao esta no Python escolhido: nada e instalado, nada e executado
        assertEquals("DEPENDENCIES_MISSING", refusal(job(OK, List.of("nekkora-pacote-que-nao-existe==9.9.9"))));
        // sessao bloqueada: nao inicia
        locked.set(true);
        assertEquals("SESSION_UNAVAILABLE", refusal(job(OK, List.of())));
        locked.set(false);
        // pasta de trabalho fora da area gerenciada: recusada
        RemoteMachine.Job outside = job(OK, List.of());
        Path elsewhere = Files.createDirectories(tmp.resolve("fora"));
        Files.copy(Path.of(outside.zip()), elsewhere.resolve("pacote.zip"));
        assertEquals("DISK_PRESSURE", refusal(new RemoteMachine.Job(outside.attemptId(), org, workspace, outside.automationId(), outside.automationVersionId(),
                elsewhere.resolve("pacote.zip").toString(), outside.sha256(), outside.fingerprint(), List.of(), pythonId(), elsewhere.toString())));
        assertFalse(work.busy());
        assertFalse(Files.exists(ApprovalLedger.defaultFile(data)));
    }
}
