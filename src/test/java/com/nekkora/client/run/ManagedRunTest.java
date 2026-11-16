package com.nekkora.client.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.pack.AtomicWriter;
import com.nekkora.client.pack.ErrorCode;
import com.nekkora.client.pack.ExecRefused;
import com.nekkora.client.pack.ExecutionPackage;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T03 - a execucao GERENCIADA no motor: a que nao nasce de uma aprovacao da pessoa neste
 * aplicativo. Processos Python de verdade (exige {@code NEKKORA_TEST_PYTHON}). O que se prova: o mesmo controlador (uma por
 * vez, qualquer que seja o tipo), armazem proprio (nada entra no historico local, nem o contrario), aprovacoes locais nem
 * consultadas nem criadas, saida so dentro da raiz gerenciada, e as demais travas iguais as da execucao local.
 */
class ManagedRunTest {
    @TempDir
    Path tmp;

    Path python;
    Path dataDir;
    Path managedRoot;
    Path localOut;
    RunFixtures.ShiftClock clock;
    RunFixtures.FakeSession session;
    ApprovalLedger approvals;
    RunStore local;
    RunStore managed;
    RunController controller;

    @BeforeEach
    void setUp() throws Exception {
        python = RunFixtures.python();
        Assumptions.assumeTrue(python != null, RunFixtures.SKIP);
        dataDir = tmp.resolve("dados");
        managedRoot = Files.createDirectories(dataDir.resolve("remota").resolve("tentativas"));
        localOut = Files.createDirectories(tmp.resolve("saida"));
        clock = new RunFixtures.ShiftClock();
        session = new RunFixtures.FakeSession();
        approvals = new ApprovalLedger(ApprovalLedger.defaultFile(dataDir), AtomicWriter.DEFAULT);
        local = new RunStore(RunStore.defaultRoot(dataDir), AtomicWriter.DEFAULT);
        managed = RunStore.managed(dataDir.resolve("remota").resolve("execucoes"), AtomicWriter.DEFAULT);
        controller = controller();
    }

    private RunController controller() {
        RunController c = new RunController(local, approvals, session, clock, dataDir, Duration.ofMillis(100), Duration.ofMillis(200));
        c.enableManaged(managed, managedRoot);
        return c;
    }

    @AfterEach
    void tearDown() {
        if (controller != null) controller.close();
    }

    /** O pacote ja conferido e a pasta de saida de uma "tentativa", as duas dentro da raiz gerenciada. */
    private RunController.Request job(String name, String main, Duration timeout) throws Exception {
        Path attempt = Files.createDirectories(managedRoot.resolve(name));
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(attempt.resolve("pacote"), name, main));
        return new RunController.Request(p.dir(), RunFixtures.context(p), python, Files.createDirectories(attempt.resolve("saida")), timeout);
    }

    private ExecutionRecord waitEnd(RunController c) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (c.isRunning() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(c.isRunning(), "terminou no prazo do teste");
        return c.current().orElseThrow().record();
    }

    @Test
    void executaSemAprovacaoLocalEGuardaSoNoArmazemProprio() throws Exception {
        RunController.Request job = job("um", RunFixtures.SUCESSO, null);
        RunController.Snapshot s = controller.startManaged(job);
        assertTrue(controller.managed(s.record().id()));
        ExecutionRecord end = waitEnd(controller);
        assertEquals(ExecutionState.SUCCEEDED, end.state());
        // T05: a execucao gerenciada NAO grava a saida do processo em arquivo local (ela e entregue a quem pediu, ou desprezada)
        assertTrue(controller.outputText(end.id()).isEmpty());
        try (java.util.stream.Stream<Path> all = Files.walk(tmp)) {
            assertTrue(all.noneMatch(f -> f.getFileName().toString().equals(RunController.OUTPUT_TEXT)), "nenhum arquivo de saida do processo");
        }
        // a aprovacao local nao foi consultada nem criada: o mesmo pacote, pelo caminho local, continua nao aprovado
        assertFalse(approvals.approved(job.context().fingerprint()));
        assertFalse(Files.exists(ApprovalLedger.defaultFile(dataDir)));
        ExecRefused refused = assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(job.packageDir(), job.context(), python, localOut, null)));
        assertEquals(ErrorCode.EXEC_NOT_APPROVED, refused.code());
        // armazens separados: o gerenciado tem a execucao, com a origem escrita; o local so tem a recusa local
        assertEquals(1, managed.list().records().size());
        assertEquals(end.id(), managed.list().records().get(0).id());
        String state = Files.readString(managed.dir(end.id()).resolve("estado.json"), StandardCharsets.UTF_8);
        assertTrue(state.replaceAll("[ \t\r\n]", "").contains("\"origin\":\"PLATFORM\""), state);
        assertEquals(1, local.list().records().size());
        assertEquals(ExecutionState.BLOCKED, local.list().records().get(0).state());
        assertFalse(controller.managed(local.list().records().get(0).id()));
        assertTrue(Files.readString(local.dir(local.list().records().get(0).id()).resolve("estado.json"), StandardCharsets.UTF_8).replaceAll("[ \t\r\n]", "").contains("\"origin\":\"LOCAL_AGENT\""));
        // a saida ficou dentro da pasta da tentativa
        assertTrue(Files.isDirectory(job.outputRoot().resolve(end.id().value())));
    }

    @Test
    void umRegistroDeUmArmazemNaoELidoPeloOutro() throws Exception {
        ExecutionRecord end;
        controller.startManaged(job("dois", RunFixtures.SUCESSO, null));
        end = waitEnd(controller);
        // copiado para a pasta das execucoes locais, o registro "da Platform" nao vira execucao local
        Path planted = Files.createDirectories(local.dir(end.id()));
        Files.copy(managed.dir(end.id()).resolve("estado.json"), planted.resolve("estado.json"));
        assertEquals(0, local.list().records().size());
        assertEquals(1, local.list().unreadable());
        // e um registro local (ou sem origem) plantado no armazem gerenciado tambem nao
        ExecutionId other = ExecutionId.random();
        Files.createDirectories(managed.dir(other));
        Files.writeString(managed.dir(other).resolve("estado.json"), RunStore.json(end)
                .replace(end.id().value(), other.value()), StandardCharsets.UTF_8);
        assertEquals(1, managed.list().records().size());
        assertEquals(1, managed.list().unreadable());
    }

    @Test
    void umaExecucaoPorVezQualquerQueSejaOTipo() throws Exception {
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pkg-local"), "local", RunFixtures.LENTO));
        approvals.approve(p, clock.instant());
        controller.start(new RunController.Request(p.dir(), RunFixtures.context(p), python, localOut, null));
        // local rodando: a gerenciada e recusada sem registro
        ExecRefused busy = assertThrows(ExecRefused.class, () -> controller.startManaged(job("tres", RunFixtures.SUCESSO, null)));
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, busy.code());
        assertEquals(0, managed.list().records().size());
        assertTrue(controller.cancel());
        assertEquals(ExecutionState.CANCELLED, waitEnd(controller).state());
        // gerenciada rodando: a local e recusada sem registro novo
        RunController.Snapshot s = controller.startManaged(job("quatro", RunFixtures.LENTO, null));
        int before = local.list().records().size();
        ExecRefused busy2 = assertThrows(ExecRefused.class, () -> controller.start(new RunController.Request(p.dir(), RunFixtures.context(p), python, localOut, null)));
        assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, busy2.code());
        assertEquals(before, local.list().records().size());
        assertEquals(RunController.CancelResult.REQUESTED, controller.cancel(s.record().id(), ExecutionRecord.CancelSource.LOCAL_UI));
        ExecutionRecord end = waitEnd(controller);
        assertEquals(ExecutionState.CANCELLED, end.state());
        assertEquals(1, managed.list().records().size());
    }

    @Test
    void asDemaisTravasSaoAsMesmas() throws Exception {
        // saida fora da raiz gerenciada (a pasta que a pessoa escolheria numa execucao local): recusada
        RunController.Request ok = job("cinco", RunFixtures.SUCESSO, null);
        ExecRefused outside = assertThrows(ExecRefused.class,
                () -> controller.startManaged(new RunController.Request(ok.packageDir(), ok.context(), python, localOut, null)));
        assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, outside.code());
        // a propria raiz, ou uma pasta dentro do pacote: recusadas
        assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, assertThrows(ExecRefused.class,
                () -> controller.startManaged(new RunController.Request(ok.packageDir(), ok.context(), python, managedRoot, null))).code());
        assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, assertThrows(ExecRefused.class,
                () -> controller.startManaged(new RunController.Request(ok.packageDir(), ok.context(), python, ok.packageDir(), null))).code());
        // e a execucao local continua sem poder gravar dentro da pasta de dados (inclusive na raiz gerenciada)
        ExecutionPackage p = ExecutionPackage.inspect(RunFixtures.pkg(tmp.resolve("pkg-l2"), "l2", RunFixtures.SUCESSO));
        approvals.approve(p, clock.instant());
        assertEquals(ErrorCode.EXEC_OUTPUT_INVALID, assertThrows(ExecRefused.class,
                () -> controller.start(new RunController.Request(p.dir(), RunFixtures.context(p), python, ok.outputRoot(), null))).code());
        // impressao diferente da pedida: recusada
        RunContext wrong = new RunContext(ok.context().organizationId(), ok.context().workspaceId(), "sha256:" + "0".repeat(64), ok.context().localName(),
                ok.context().automationId(), ok.context().automationVersionId());
        assertEquals(ErrorCode.EXEC_PACKAGE_CHANGED, assertThrows(ExecRefused.class,
                () -> controller.startManaged(new RunController.Request(ok.packageDir(), wrong, python, ok.outputRoot(), null))).code());
        // sessao bloqueada: recusada, como na local
        session.locked.set(true);
        assertThrows(ExecRefused.class, () -> controller.startManaged(ok));
        session.locked.set(false);
        // codigo de saida diferente de zero e prazo: os mesmos desfechos
        controller.startManaged(job("seis", RunFixtures.CODIGO_3, null));
        ExecutionRecord failed = waitEnd(controller);
        assertEquals(ExecutionState.FAILED, failed.state());
        assertEquals(3, failed.exitCode());
        controller.startManaged(job("sete", RunFixtures.LENTO, Duration.ofSeconds(2)));
        assertEquals(ExecutionState.TIMED_OUT, waitEnd(controller).state());
        // sem a raiz gerenciada ligada, o controlador nao aceita execucao gerenciada
        RunController plain = new RunController(local, approvals, session, clock, dataDir, Duration.ofMillis(100), Duration.ofMillis(200));
        assertThrows(ExecRefused.class, () -> plain.startManaged(ok));
        plain.close();
    }

    @Test
    void depoisDeReabrirOAplicativoAGerenciadaEmAndamentoEAdotadaNoArmazemDela() throws Exception {
        RunController.Snapshot s = controller.startManaged(job("oito", RunFixtures.LENTO, null));
        ExecutionId id = s.record().id();
        // o aplicativo "cai": outro controlador, sem a lista em memoria, reconhece a execucao pela pasta do armazem
        RunController reopened = controller();
        assertTrue(reopened.managed(id));
        assertEquals(1, reopened.reconcile());
        assertTrue(reopened.isRunning(), "adotada: ocupa a vaga");
        assertThrows(ExecRefused.class, () -> reopened.startManaged(job("nove", RunFixtures.SUCESSO, null)));
        assertEquals(RunController.CancelResult.REQUESTED, reopened.cancel(id, ExecutionRecord.CancelSource.LOCAL_UI));
        ExecutionRecord end = waitEnd(reopened);
        assertEquals(ExecutionState.CANCELLED, end.state());
        assertEquals(0, local.list().records().size());
        assertEquals(id, managed.list().records().get(0).id());
        reopened.close();
    }

    private static final String DEIXA_FILHO = String.join("\n",
            "import json, os, pathlib, subprocess, sys, time",
            "from comum import saida, sinal",
            "filho = subprocess.Popen([sys.executable, '-E', '-s', '-B', str(pathlib.Path(__file__).with_name('filho.py'))],",
            "                         stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)",
            "(saida() / 'pids.json').write_text(json.dumps({'pai': os.getpid(), 'filho': filho.pid}), encoding='utf-8')",
            "time.sleep(1.5)",
            "sinal('BUSINESS_SUCCESS')",
            "");

    private ProcessHandle childOf(RunController.Request job, ExecutionId id) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"filho\": *(\\d+)")
                .matcher(Files.readString(job.outputRoot().resolve(id.value()).resolve("pids.json"), StandardCharsets.UTF_8));
        assertTrue(m.find());
        return ProcessHandle.of(Long.parseLong(m.group(1))).orElseThrow();
    }

    private void waitFree(RunController c) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (c.occupied() && System.nanoTime() < deadline) Thread.sleep(50);
        assertFalse(c.occupied(), "a vaga foi liberada no prazo do teste");
    }

    /**
     * Decisoes de 16/11: terminar o processo principal NAO libera o computador enquanto houver descendente da execucao vivo. A
     * automacao termina com sucesso (o desfecho e o codigo de saida sao os dela) e deixa um filho: a execucao fica "finalizando" -
     * a vaga continua ocupada, nada novo inicia, os processos sao dados como vivos. Quando o filho acaba, o Windows diz que o
     * grupo esvaziou: so entao ha prova, e a vaga e liberada.
     */
    @Test
    void terminarOPrincipalNaoLiberaAVagaEnquantoHouverDescendenteVivo() throws Exception {
        RunController.Request job = job("dez", DEIXA_FILHO, null);
        ExecutionId id = controller.startManaged(job).record().id();
        assertEquals(ProcessTree.Verdict.ALIVE, controller.processTree(id), "em andamento");
        ExecutionRecord end = waitEnd(controller);
        assertEquals(ExecutionState.SUCCEEDED, end.state());
        assertEquals(Integer.valueOf(0), end.exitCode(), "o codigo de saida do processo principal fica registrado");
        ProcessHandle child = childOf(job, id);
        try {
            assertTrue(child.isAlive(), "o filho sobreviveu ao processo principal");
            assertFalse(controller.isRunning(), "a execucao terminou");
            assertTrue(controller.occupied(), "mas o computador continua ocupado: finalizando");
            assertEquals(ProcessTree.Verdict.ALIVE, controller.processTree(id));
            ExecRefused busy = assertThrows(ExecRefused.class, () -> controller.startManaged(job("onze", RunFixtures.SUCESSO, null)));
            assertEquals(ErrorCode.EXEC_ALREADY_RUNNING, busy.code());
            // outro controlador (o aplicativo reaberto) nao alcanca mais o grupo, mas ve o filho anotado vivo
            RunController reopened = controller();
            assertEquals(ProcessTree.Verdict.ALIVE, reopened.processTree(id), "a anotacao esta no disco: vale depois de reabrir");
            reopened.close();
        } finally {
            // so o processo deste teste, pelo PID que ele mesmo gravou
            child.destroyForcibly();
        }
        waitFree(controller);
        assertEquals(ProcessTree.Verdict.GONE, controller.processTree(id), "o Windows disse que o grupo esvaziou");
        String facts = Files.readString(managed.dir(id).resolve(ProcessTree.FILE), StandardCharsets.UTF_8);
        for (String fact : List.of(ProcessTree.GROUP, ProcessTree.MARK, ProcessTree.END, ProcessTree.EMPTY)) assertTrue(facts.contains(fact + "\n"), fact);
        assertFalse(facts.contains(ProcessTree.BROKEN));
        assertEquals(Boolean.FALSE, WindowsSupervision.marked(WindowsSupervision.name(id)), "com a prova, a marca da sessao sai");
        // liberada: outra execucao inicia
        controller.startManaged(job("doze", RunFixtures.SUCESSO, null));
        waitEnd(controller);
        waitFree(controller);
    }

    /**
     * O aplicativo estava fechado quando o processo principal terminou, e restou um descendente: o grupo nao e mais alcancavel.
     * Enquanto o descendente anotado vive, ha algo em andamento; depois que ele acaba NADA e afirmado - a evidencia e
     * insuficiente - ate a sessao do Windows acabar (aqui, simulada tirando a marca da execucao).
     */
    @Test
    void comOAplicativoFechadoNoFimDoPrincipalSoASessaoEncerradaAcabaComADuvida() throws Exception {
        RunController.Request job = job("treze", DEIXA_FILHO, null);
        ExecutionId id = controller.startManaged(job).record().id();
        waitEnd(controller);
        ProcessHandle child = childOf(job, id);
        Path tree = managed.dir(id).resolve(ProcessTree.FILE);
        try {
            // o que o disco teria se o aplicativo tivesse fechado antes do fim: sem "fim" e sem "vazio"
            String seen = Files.readString(tree, StandardCharsets.UTF_8);
            Files.writeString(tree, seen.replace(ProcessTree.END + "\n", "").replace(ProcessTree.EMPTY + "\n", ""), StandardCharsets.UTF_8);
            RunController reopened = controller();
            assertEquals(ProcessTree.Verdict.ALIVE, reopened.processTree(id), "o descendente anotado continua vivo");
            child.destroyForcibly();
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (child.isAlive() && System.nanoTime() < deadline) Thread.sleep(50);
            // o controlador deste teste ainda acompanhava o grupo: anotou o fim e tirou a marca. O disco e a sessao voltam a ser os de
            // um aplicativo que estava FECHADO quando tudo terminou
            waitFree(controller);
            assertTrue(WindowsSupervision.mark(WindowsSupervision.name(id)));
            String after = Files.readString(tree, StandardCharsets.UTF_8);
            Files.writeString(tree, after.replace(ProcessTree.END + "\n", "").replace(ProcessTree.EMPTY + "\n", ""), StandardCharsets.UTF_8);
            assertEquals(ProcessTree.Verdict.UNCERTAIN, reopened.processTree(id), "nenhum anotado vivo NAO e prova");
            assertFalse(reopened.occupied(), "a vaga local nao fica presa; a duvida e dita a quem pergunta");
            // a sessao do Windows acabou (simulado): a marca da execucao nao existe mais
            WindowsSupervision.unmark(WindowsSupervision.name(id));
            assertEquals(ProcessTree.Verdict.GONE, reopened.processTree(id));
            reopened.close();
        } finally {
            child.destroyForcibly();
            WindowsSupervision.unmark(WindowsSupervision.name(id));
        }
    }

    /**
     * Parar a execucao encerra o GRUPO inteiro: tambem o neto cujo pai ja terminou, que nenhuma arvore pai-filho mostra mais.
     */
    @Test
    void cancelarEncerraTambemODescendenteCujoPaiJaTerminou() throws Exception {
        String main = String.join("\n",
                "import json, os, pathlib, subprocess, sys, time",
                "from comum import saida",
                "meio = subprocess.Popen([sys.executable, '-E', '-s', '-B', '-c',",
                "    'import subprocess, sys; n = subprocess.Popen([sys.executable, \"-c\", \"import time; time.sleep(900)\"], stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL); print(n.pid, flush=True)'],",
                "    stdout=subprocess.PIPE, text=True)",
                "neto = int(meio.stdout.readline().strip())",
                "meio.wait()",
                "(saida() / 'pids.json').write_text(json.dumps({'pai': os.getpid(), 'filho': neto}), encoding='utf-8')",
                "time.sleep(900)",
                "");
        RunController.Request job = job("catorze", main, null);
        ExecutionId id = controller.startManaged(job).record().id();
        Path pids = job.outputRoot().resolve(id.value()).resolve("pids.json");
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (!Files.exists(pids) && System.nanoTime() < deadline) Thread.sleep(50);
        ProcessHandle orphan = childOf(job, id);
        try {
            assertTrue(orphan.isAlive());
            assertTrue(orphan.parent().isEmpty(), "o pai dele ja terminou: o sistema nao diz mais de quem ele descende");
            assertEquals(RunController.CancelResult.REQUESTED, controller.cancel(id, ExecutionRecord.CancelSource.MANAGED_REQUEST));
            assertEquals(ExecutionState.CANCELLED, waitEnd(controller).state());
            waitFree(controller);
            assertFalse(orphan.isAlive(), "o grupo foi encerrado inteiro");
            assertEquals(ProcessTree.Verdict.GONE, controller.processTree(id));
        } finally {
            orphan.destroyForcibly();
        }
    }
}
