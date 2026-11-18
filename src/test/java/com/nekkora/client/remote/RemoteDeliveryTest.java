package com.nekkora.client.remote;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import com.nekkora.client.remote.RemoteMachine.Status;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T02 e T03 - a tentativa nesta maquina, do recebimento ao desfecho: perguntar, gravar no
 * diario, confirmar, baixar o pacote, localizar o Python escolhido, iniciar so depois de a Platform reconferir, acompanhar,
 * obedecer ao cancelamento e informar o desfecho. Contra um canal simulado (HTTPS de verdade) e com a protecao REAL do
 * Windows. Quem executa e simulado aqui ({@link FakeWork}): o motor de verdade e provado em {@code ManagedRunTest} e a ponte
 * em {@code RemoteWorkTest}.
 */
@EnabledOnOs(OS.WINDOWS)
class RemoteDeliveryTest {

    private static final String NEXT = "/agent/api/v1/remote/next";
    private static final String LOCAL_PYTHON = "computador:c:\\python312\\python.exe";

    /** Quem executa, simulado: guarda o que recebeu e devolve o que o teste mandar. */
    static final class FakeWork implements RemoteMachine.Work {
        final Path root;
        final List<RemoteMachine.Job> jobs = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final List<String> discarded = new ArrayList<>();
        volatile boolean busy;
        volatile String refusal;
        volatile Throwable crash;
        volatile String state = "RUNNING";
        volatile Integer exitCode;
        volatile boolean known = true;
        volatile byte[] zipSeen;

        FakeWork(Path root) {
            this.root = root;
        }

        @Override
        public boolean busy() {
            return busy;
        }

        @Override
        public Optional<String> dir(String attemptId) {
            return Optional.of(root.resolve(attemptId).toString());
        }

        @Override
        public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
            jobs.add(job);
            try {
                zipSeen = Files.readAllBytes(Path.of(job.zip()));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
            if (crash instanceof Error err) {
                throw err;
            }
            if (crash instanceof RuntimeException re) {
                throw re;
            }
            if (refusal != null) {
                throw new RemoteMachine.WorkRefused(refusal);
            }
            busy = true;
            return "ex-" + "1".repeat(32);
        }

        @Override
        public Optional<RemoteMachine.Progress> progress(String run) {
            return known ? Optional.of(new RemoteMachine.Progress(state, exitCode)) : Optional.empty();
        }

        @Override
        public void cancel(String run) {
            cancelled.add(run);
            state = "CANCELLED";
            busy = false;
        }

        final List<String> outputsDiscarded = new ArrayList<>();
        /** tentativa -> motivo da pendencia. */
        final java.util.Map<String, String> outputsHeld = new java.util.LinkedHashMap<>();
        /** Roda depois da PRIMEIRA listagem dos resultados de uma tentativa (o envio), antes da conferencia final. */
        volatile Runnable afterListing;

        @Override
        public void holdOutputs(String attemptId, String reason) {
            outputsHeld.put(attemptId, reason);
        }
        final List<String> swept = new ArrayList<>();

        @Override
        public void discardOutputs(String attemptId) {
            outputsDiscarded.add(attemptId);
        }

        @Override
        public int sweep(String keepAttemptId) {
            swept.add(String.valueOf(keepAttemptId));
            return 0;
        }

        @Override
        public void discard(String attemptId, String run) {
            discarded.add(attemptId);
            try {
                Files.deleteIfExists(root.resolve(attemptId).resolve("pacote.zip"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }

        /** Arquivos que a "execucao" deixou na pasta de saida: nome relativo -> conteudo. */
        final java.util.Map<String, byte[]> outputs = new java.util.LinkedHashMap<>();

        @Override
        public List<RemoteMachine.ResultFile> results(String attemptId, String run) {
            List<RemoteMachine.ResultFile> out = new ArrayList<>();
            try {
                Path dir = Files.createDirectories(root.resolve(attemptId).resolve("saida"));
                for (java.util.Map.Entry<String, byte[]> e : outputs.entrySet()) {
                    Path f = dir.resolve(e.getKey().replace('/', '_'));
                    Files.write(f, e.getValue());
                    out.add(new RemoteMachine.ResultFile(e.getKey(), f.toString(), e.getValue().length));
                }
            } catch (java.io.IOException x) {
                throw new IllegalStateException(x);
            }
            Runnable then = afterListing;
            afterListing = null;
            if (then != null) {
                then.run();
            }
            return out;
        }

        void ends(String how, Integer exit) {
            state = how;
            exitCode = exit;
            busy = false;
        }
    }

    @TempDir
    Path tmp;
    private FakeGateway gw;
    private Path dir;
    private FakeWork work;
    private volatile boolean pythonPresent = true;

    @BeforeEach
    void open() throws Exception {
        gw = new FakeGateway();
        dir = tmp.resolve("remota");
        work = new FakeWork(tmp.resolve("tentativas"));
    }

    @AfterEach
    void close() {
        gw.close();
    }

    private RemoteMachine machine(boolean executes) {
        RemoteMachine m = new RemoteMachine(new MachineVault(dir, new WindowsKeyring()), gw.channel(), Optional.of("0.1.0"));
        m.setPythonFinder(() -> pythonPresent ? List.of(new RemoteMachine.PythonSeen(LOCAL_PYTHON, false, "3.12.10")) : List.of());
        if (executes) {
            m.setWork(work);
        }
        return m;
    }

    /** Habilitada, com o inventario publicado; a entrega passa a indicar o Python escolhido pelo identificador opaco dele. */
    private RemoteMachine enabled(boolean executes) throws Exception {
        RemoteMachine m = machine(executes);
        m.enable(gw.code("remote"));
        m.beat();
        Matcher id = Pattern.compile("\"environmentId\":\"([A-Za-z0-9_-]{43})\"").matcher(gw.inventory);
        assertTrue(id.find(), "inventario publicado");
        gw.offerPython = id.group(1);
        assertFalse(gw.inventory.toLowerCase().contains("python312"), "o caminho nunca vai a Platform");
        return m;
    }

    private Path journal() {
        return dir.resolve(AttemptJournal.FILE);
    }

    private String journalText() throws Exception {
        return Files.readString(journal(), StandardCharsets.UTF_8);
    }

    private long acks() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/ack")).count();
    }

    private long downloads() {
        return gw.calls.stream().filter(c -> c.path().endsWith("/package")).count();
    }

    @Test
    void semQuemExecuteAMaquinaNaoDeclaraExecucaoNemPerguntaPelaEntrega() throws Exception {
        RemoteMachine m = enabled(false);
        gw.queue();
        m.pull();
        m.beat();
        assertFalse(gw.declaredRun, "sem quem execute, nao declara a capacidade");
        assertTrue(gw.lastHeartbeat.contains("\"capabilities\":[\"PY_INVENTORY_1_0\"]"));
        assertEquals(0, gw.count(NEXT));
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void recebeBaixaIniciaAcompanhaEInformaOSucesso() throws Exception {
        RemoteMachine m = enabled(true);
        assertTrue(gw.declaredRun, "com quem execute, declara REMOTE_RUN_1_0");
        String attempt = gw.queue();
        m.pull();
        // a ordem que a Platform viu: preparando, iniciando (reconferencia), em execucao
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING"), gw.reported);
        assertEquals("RUNNING", gw.attemptState);
        assertEquals(1, acks());
        assertEquals(1, downloads());
        // o que quem executa recebeu: o ZIP inteiro, o SHA-256 declarado, a impressao, as dependencias, o Python LOCAL e os ids
        assertEquals(1, work.jobs.size());
        RemoteMachine.Job job = work.jobs.get(0);
        assertArrayEquals(gw.packageBytes, work.zipSeen);
        assertEquals(FakeGateway.sha256(gw.packageBytes), job.sha256());
        assertEquals("c".repeat(64), job.fingerprint());
        assertEquals(List.of("requests==2.32.3"), job.dependencies());
        assertEquals(LOCAL_PYTHON, job.pythonLocalId());
        assertEquals(attempt, job.attemptId());
        assertEquals(gw.organizationId, job.organizationId());
        assertEquals(gw.workspaceId, job.workspaceId());
        assertEquals(gw.automationVersionId, job.automationVersionId());
        assertEquals(work.root.resolve(attempt).toString(), job.dir());
        // o diario: em execucao, com o identificador da execucao; o token nunca em claro; nenhum caminho do Python
        String file = journalText();
        assertTrue(file.contains("\"fase\":\"RUNNING\""));
        assertTrue(file.contains("\"execucao\":\"ex-"));
        assertFalse(file.contains(gw.attemptToken));
        assertFalse(file.toLowerCase().contains("python312"));
        // o token do download foi em cabecalho, nunca na URL
        assertTrue(gw.calls.stream().noneMatch(c -> c.path().contains(gw.attemptToken)));
        // enquanto roda: cada consulta repete o estado (renova o prazo), sem nova entrega nem novo inicio
        m.pull();
        m.pull();
        assertEquals(List.of("PREPARING", "STARTING", "RUNNING", "RUNNING", "RUNNING"), gw.reported);
        assertEquals(1, gw.count(NEXT));
        assertEquals(1, work.jobs.size());
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":true"));
        // terminou: o desfecho com o codigo de saida; so entao o diario some e o codigo da tentativa e apagado
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertEquals(0, gw.attemptExit);
        assertFalse(Files.exists(journal()));
        assertTrue(work.discarded.contains(attempt));
        assertFalse(Files.exists(Path.of(job.zip())));
        m.beat();
        assertTrue(gw.lastHeartbeat.contains("\"busy\":false"));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void depoisDoDesfechoOsResultadosSaoEnviadosEOEnvioEFechadoSemMudarODesfecho() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("relatorio.txt", "ok".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dados/relatório final.csv", "a;b\n".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState, "o desfecho e informado antes dos arquivos, e nao muda por causa deles");
        // o nome que vai a Platform e o RELATIVO a pasta de saida; o caminho deste computador nao vai
        assertEquals(2, gw.results.size());
        assertTrue(gw.results.get(1).startsWith("relatorio.txt|" + FakeGateway.sha256("ok".getBytes(StandardCharsets.UTF_8)) + "|2"));
        assertTrue(gw.results.get(2).startsWith("dados/relatório final.csv|"));
        assertTrue(gw.calls.stream().filter(c -> c.path().contains("/results/")).noneMatch(c -> c.path().contains(gw.attemptToken)), "token nunca na URL");
        assertEquals("2|0", gw.resultsClosure);
        assertFalse(Files.exists(journal()), "so depois do fechamento o diario some");
        // T04: com o recebimento de TUDO confirmado, a pasta de saida pode sair deste computador
        assertEquals(List.of(gw.attemptId), work.outputsDiscarded);
    }

    @Test
    void aPastaDeSaidaSoSaiQuandoAConfirmacaoCorrespondeATentativaEAosArquivosEnviados() throws Exception {
        RemoteMachine m = enabled(true);
        // (1) a Platform confirma um arquivo com OUTRO tamanho: nao e confirmacao; nada e fechado nem apagado, e o envio e retomado
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.wrongResultSize = true;
        m.pull();
        assertNull(gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertTrue(work.outputsHeld.isEmpty());
        assertTrue(Files.exists(journal()), "a tentativa continua pendente");
        gw.wrongResultSize = false;
        // (2) o fechamento volta com OUTRA contagem: nao e a confirmacao desta entrega; a pasta fica, marcada
        gw.wrongClosure = true;
        m.pull();
        assertEquals("1|0", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("NOT_CONFIRMED", work.outputsHeld.get(attempt));
        gw.wrongClosure = false;

        // (3) tudo confirmado, mas o arquivo MUDOU na pasta depois do envio: o que esta no disco nao e o que a Platform tem
        work.outputsHeld.clear();
        String second = gw.queue();
        work.outputs.clear();
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        work.afterListing = () -> work.outputs.put("dois.txt", "outro conteudo".getBytes(StandardCharsets.UTF_8));
        m.pull();
        assertEquals("1|0", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("CHANGED_AFTER_SEND", work.outputsHeld.get(second));

        // (4) apareceu um arquivo a mais depois do envio: tambem nao apaga
        work.outputsHeld.clear();
        String third = gw.queue();
        work.outputs.clear();
        work.outputs.put("tres.txt", "3".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        work.afterListing = () -> work.outputs.put("tardio.txt", "x".getBytes(StandardCharsets.UTF_8));
        m.pull();
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("CHANGED_AFTER_SEND", work.outputsHeld.get(third));

        // (5) o caso bom: confirmado arquivo a arquivo, fechamento igual, pasta igual ao confirmado - so entao sai
        work.outputsHeld.clear();
        String fourth = gw.queue();
        work.outputs.clear();
        work.outputs.put("quatro.txt", "4".getBytes(StandardCharsets.UTF_8));
        work.state = "RUNNING";
        m.pull();
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals(List.of(fourth), work.outputsDiscarded);
        assertTrue(work.outputsHeld.isEmpty());
    }

    @Test
    void resultadoPendenteNaoEApagadoAntesDeAPlatformConfirmarORecebimento() throws Exception {
        RemoteMachine m = enabled(true);
        // (1) a rede cai no meio do envio: nada confirmado, nada apagado; os arquivos continuam no disco
        String attempt = gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.failResults = 5;
        m.pull();
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertNull(gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty(), "sem confirmacao de recebimento, a pasta de saida fica");
        assertTrue(Files.exists(work.root.resolve(attempt).resolve("saida").resolve("um.txt")));
        assertTrue(Files.exists(journal()));
        // o aplicativo fecha e abre: a varredura de sobras preserva a tentativa pendente e nao apaga resultado nenhum
        m.close();
        RemoteMachine again = machine(true);
        again.sweep();
        assertEquals(List.of(attempt), work.swept);
        assertTrue(work.outputsDiscarded.isEmpty());
        gw.failResults = 0;
        again.pull();
        assertEquals("2|0", gw.resultsClosure);
        assertEquals(List.of(attempt), work.outputsDiscarded, "so agora, com o fechamento aceito");

        // (2) algum arquivo ficou de fora por limite: ele so existe neste computador, entao a pasta de saida NAO e apagada
        work.outputsDiscarded.clear();
        gw.queue();
        work.outputs.clear();
        work.outputs.put("a.txt", new byte[1]);
        work.outputs.put("grande-demais.bin", new byte[(int) MachineChannel.MAX_RESULT_BYTES + 1]);
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        again.pull();
        assertEquals("1|1", gw.resultsClosure);
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("OUT_OF_LIMIT", work.outputsHeld.get(gw.attemptId), "a pendencia fica marcada, com o motivo");

        // (3) a Platform diz que o envio ja foi fechado por outra entrega: sem confirmacao de que tudo chegou, nada e apagado
        gw.queue();
        work.outputs.clear();
        work.outputs.put("b.txt", new byte[1]);
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        gw.resultsClosure = "0|0";
        again.pull();
        assertTrue(work.outputsDiscarded.isEmpty());
        assertEquals("NOT_CONFIRMED", work.outputsHeld.get(gw.attemptId));
        assertFalse(Files.exists(journal()));
        // sem quem execute, a varredura nao faz nada
        assertEquals(0, machine(false).sweep());
    }

    @Test
    void oQuePassaDoLimiteNaoEEnviadoEEContadoNoFechamento() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("a.txt", new byte[10]);
        work.outputs.put("grande-demais.bin", new byte[(int) MachineChannel.MAX_RESULT_BYTES + 1]);
        work.outputs.put("recusado-pela-platform.bin", new byte[5]);
        work.outputs.put("nome-" + "x".repeat(200) + ".txt", new byte[1]);
        work.outputs.put("b.txt", new byte[3]);
        gw.resultOverLimit = "recusado-pela-platform.bin";
        work.ends("FAILED", 3);
        m.pull();
        assertEquals("FAILED", gw.attemptState, "o resultado do robo nao muda por causa dos arquivos");
        assertEquals(3, gw.attemptExit);
        assertEquals(2, gw.results.size());
        assertTrue(gw.results.get(1).startsWith("a.txt|"));
        assertTrue(gw.results.get(2).startsWith("b.txt|"), "o numero segue a ordem do que foi ENVIADO");
        assertEquals("2|3", gw.resultsClosure, "tres ficaram de fora por limite, e isso e dito");
        assertFalse(Files.exists(journal()));
    }

    @Test
    void semRedeNoMeioDoEnvioEleERetomadoSemRepetirODesfechoNemDuplicarArquivo() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.outputs.put("um.txt", "1".getBytes(StandardCharsets.UTF_8));
        work.outputs.put("dois.txt", "2".getBytes(StandardCharsets.UTF_8));
        work.ends("SUCCEEDED", 0);
        gw.failResults = 1; // o primeiro arquivo nao chega
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertTrue(journalText().contains("\"fase\":\"RESULTS\""), "o desfecho ja foi aceito; falta so o envio");
        assertNull(gw.resultsClosure);
        long finals = gw.reported.stream().filter("SUCCEEDED"::equals).count();
        // o aplicativo fecha e abre: retoma o envio, sem informar o desfecho de novo
        m.close();
        RemoteMachine again = machine(true);
        again.pull();
        assertEquals(finals, gw.reported.stream().filter("SUCCEEDED"::equals).count());
        assertEquals(2, gw.results.size());
        assertEquals("2|0", gw.resultsClosure);
        assertFalse(Files.exists(journal()));
        // execucao sem arquivo nenhum: o envio e fechado com zero
        gw.queue();
        work.outputs.clear();
        work.state = "RUNNING";
        again.pull();
        work.ends("SUCCEEDED", 0);
        again.pull();
        assertEquals("0|0", gw.resultsClosure);
        // tentativa que nao chegou a executar: nenhum envio, nenhum fechamento
        gw.queue();
        work.refusal = "DEPENDENCIES_MISSING";
        again.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertNull(gw.resultsClosure);
        assertTrue(gw.calls.stream().filter(c -> c.path().contains(gw.attemptId)).noneMatch(c -> c.path().contains("/results/")));
    }

    @Test
    void cadaDesfechoDoProcessoViraOEstadoCerto() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        work.ends("FAILED", 3);
        m.pull();
        assertEquals("FAILED", gw.attemptState);
        assertEquals(3, gw.attemptExit);

        gw.queue();
        gw.reported.clear();
        work.state = "RUNNING";
        m.pull();
        work.ends("TIMED_OUT", null);
        m.pull();
        assertEquals("TIMED_OUT", gw.attemptState);
        assertNull(gw.attemptExit);

        // o motor nao observou o fim (ou nao conhece mais a execucao): terminou sem prova do resultado, sem codigo de saida
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        work.ends("UNKNOWN", 7);
        m.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertNull(gw.attemptExit);
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        work.known = false;
        m.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void oQueNaoPodeIniciarERecusadoComOMotivoENadaEExecutado() throws Exception {
        RemoteMachine m = enabled(true);
        // quem executa recusa (dependencia ausente no Python escolhido): o motivo dele vai a Platform
        gw.queue();
        work.refusal = "DEPENDENCIES_MISSING";
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("DEPENDENCIES_MISSING", gw.attemptReason);
        assertFalse(Files.exists(journal()));
        assertFalse(work.busy);
        work.refusal = null;
        // o Python escolhido nao esta mais no computador: recusa; nenhum outro e escolhido e nada e iniciado
        gw.queue();
        pythonPresent = false;
        int started = work.jobs.size();
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("ENVIRONMENT_GONE", gw.attemptReason);
        assertEquals(started, work.jobs.size());
        pythonPresent = true;
        // pacote de um tipo que este aplicativo nao executa, ou entrega sem o Python ou o ambiente: nem baixa
        long before = downloads();
        gw.queue();
        gw.offerRule = "nekkora-agent/package-fingerprint/1";
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerRule = "nekkora-agent/external-python-fingerprint/1";
        gw.queue();
        String python = gw.offerPython;
        gw.offerPython = null;
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerPython = python;
        gw.queue();
        gw.offerWorkspace = false;
        m.pull();
        assertEquals("CLIENT_INCOMPATIBLE", gw.attemptReason);
        gw.offerWorkspace = true;
        // computador ocupado com outra execucao (a local, por exemplo): recusa sem preparar
        gw.queue();
        gw.reported.clear();
        work.busy = true;
        m.pull();
        assertEquals("BUSY", gw.attemptReason);
        assertEquals(List.of("REFUSED"), gw.reported);
        assertEquals(before, downloads());
        assertEquals(started, work.jobs.size());
    }

    @Test
    void antesDeIniciarAPlatformReconfereESeAlgoCaiuNadaEIniciado() throws Exception {
        RemoteMachine m = enabled(true);
        String attempt = gw.queue();
        gw.refuseStart = "AUTHORIZATION_REVOKED";
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("AUTHORIZATION_REVOKED", gw.attemptReason);
        assertEquals(List.of("PREPARING", "STARTING"), gw.reported);
        assertTrue(work.jobs.isEmpty(), "nada foi iniciado");
        assertFalse(Files.exists(journal()));
        assertTrue(work.discarded.contains(attempt), "o pacote baixado foi apagado");
        // cancelamento pedido durante a preparacao: idem
        gw.refuseStart = null;
        gw.queue();
        gw.failStates = 0;
        gw.reported.clear();
        gw.cancelRequested = false;
        // a pessoa cancela depois de a maquina receber
        RemoteMachine.Work real = work;
        m.setWork(new RemoteMachine.Work() {
            @Override
            public boolean busy() {
                return false;
            }

            @Override
            public Optional<String> dir(String a) {
                gw.cancelRequested = true; // o pedido chega enquanto a maquina prepara
                return real.dir(a);
            }

            @Override
            public String start(RemoteMachine.Job job) throws RemoteMachine.WorkRefused {
                return real.start(job);
            }

            @Override
            public Optional<RemoteMachine.Progress> progress(String run) {
                return real.progress(run);
            }

            @Override
            public void cancel(String run) {
                real.cancel(run);
            }

            @Override
            public void discard(String a, String run) {
                real.discard(a, run);
            }

            @Override
            public void discardOutputs(String a) {
                real.discardOutputs(a);
            }

            @Override
            public int sweep(String keep) {
                return real.sweep(keep);
            }

            @Override
            public List<RemoteMachine.ResultFile> results(String a, String run) {
                return real.results(a, run);
            }
        });
        m.pull();
        assertEquals("CANCELLED", gw.attemptState);
        assertTrue(work.jobs.isEmpty());
        assertFalse(Files.exists(journal()));
    }

    @Test
    void cancelamentoPedidoDuranteAExecucaoEObedecidoEInformado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        gw.cancelRequested = true;
        m.pull();
        assertEquals(1, work.cancelled.size(), "a maquina mandou parar");
        assertEquals("RUNNING", gw.attemptState, "so muda quando o processo parar de fato");
        m.pull();
        assertEquals("CANCELLED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void pacoteQueNaoConfereOuQueAPlatformNaoTemERecusadoSemExecutar() throws Exception {
        RemoteMachine m = enabled(true);
        // os bytes nao batem com o SHA-256 declarado: nada fica no disco, nada e iniciado
        String attempt = gw.queue();
        gw.declaredSha256 = "0".repeat(64);
        m.pull();
        assertEquals("REFUSED", gw.attemptState);
        assertEquals("PACKAGE_INVALID", gw.attemptReason);
        assertFalse(Files.exists(work.root.resolve(attempt).resolve("pacote.zip")));
        assertTrue(work.jobs.isEmpty());
        gw.declaredSha256 = null;
        // a Platform nao tem os bytes (ou eles nao conferem la)
        gw.queue();
        gw.packageUnavailable = true;
        m.pull();
        assertEquals("PACKAGE_INVALID", gw.attemptReason);
        assertTrue(work.jobs.isEmpty());
        assertFalse(Files.exists(journal()));
    }

    @Test
    void respostaPerdidaDaConfirmacaoRepeteAMesmaConfirmacaoSemNovaEntrega() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.dropAckResponses = 2;
        m.pull();
        assertEquals("RECEIVED", gw.attemptState, "o servidor processou; a resposta se perdeu");
        assertTrue(journalText().contains("\"confirmada\":false"));
        int epoch = gw.attemptEpoch;
        gw.dropAckResponses = 0; // o cliente do JDK pode ou nao ter repetido sozinho: dai em diante as respostas chegam
        m.pull();
        assertEquals(1, gw.count(NEXT), "nao houve nova entrega");
        assertEquals(epoch, gw.attemptEpoch);
        assertEquals("RUNNING", gw.attemptState);
    }

    @Test
    void aplicativoFechadoDepoisDeGravarAProximaAberturaSegueComOQueJaTinha() throws Exception {
        RemoteMachine first = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        first.pull();
        assertEquals("OFFERED", gw.attemptState);
        assertTrue(Files.exists(journal()), "gravada ANTES de confirmar");
        first.close();

        RemoteMachine second = machine(true);
        second.pull();
        assertEquals(1, gw.count(NEXT), "segue com a que ja tinha; nao pergunta por outra");
        assertEquals("RUNNING", gw.attemptState);
        // em execucao, o aplicativo fecha e abre: continua acompanhando a MESMA execucao, sem iniciar outra
        second.close();
        RemoteMachine third = machine(true);
        third.pull();
        assertEquals(1, work.jobs.size());
        work.ends("SUCCEEDED", 0);
        third.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void fechouEntrePedirOInicioEGravarAExecucaoTerminaSemProvaENadaELancadoDeNovo() throws Exception {
        RemoteMachine first = enabled(true);
        gw.queue();
        work.crash = new AssertionError("o aplicativo caiu aqui");
        assertThrows(AssertionError.class, first::pull);
        assertEquals("STARTING", gw.attemptState);
        assertTrue(journalText().contains("\"fase\":\"STARTING\""), "a intencao de iniciar foi gravada antes");
        first.close();
        work.crash = null;

        RemoteMachine second = machine(true);
        second.pull();
        assertEquals("ENDED_UNKNOWN", gw.attemptState);
        assertEquals(1, work.jobs.size(), "nada foi lancado de novo");
        assertFalse(Files.exists(journal()));
    }

    @Test
    void entregaQueNaoValeMaisParaOQueEstiverRodandoENaoAlteraNadaNaPlatform() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        // outra entrega tomou o lugar (token e epoca novos no servidor)
        gw.attemptToken = "Z".repeat(43);
        gw.attemptEpoch++;
        m.pull();
        assertEquals(1, work.cancelled.size(), "o que esta entrega iniciou aqui foi parado");
        assertEquals("RUNNING", gw.attemptState, "o consumidor obsoleto nao sobrescreve o estado");
        assertFalse(Files.exists(journal()));
        // a tentativa que a Platform nao tem mais: idem
        gw.queue();
        work.state = "RUNNING";
        m.pull();
        gw.attemptId = null;
        m.pull();
        assertEquals(2, work.cancelled.size());
        assertFalse(Files.exists(journal()));
        assertEquals(Status.RECEIVING, m.view().status());
    }

    @Test
    void semRedeAExecucaoEmAndamentoNaoEInterrompida() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        m.pull();
        gw.failStates = 3;
        m.pull();
        m.pull();
        assertTrue(work.cancelled.isEmpty(), "falta de comunicacao nao para o processo");
        assertTrue(Files.exists(journal()));
        // termina sem rede: o desfecho fica no diario e e informado quando a rede voltar
        work.ends("SUCCEEDED", 0);
        m.pull();
        assertEquals("RUNNING", gw.attemptState);
        assertTrue(journalText().contains("\"informar\":\"SUCCEEDED\""));
        m.pull();
        assertEquals("SUCCEEDED", gw.attemptState);
        assertFalse(Files.exists(journal()));
    }

    @Test
    void entregaForaDoContratoNaoEGravadaNemConfirmada() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.malformedOffer = true;
        m.pull();
        assertFalse(Files.exists(journal()));
        assertEquals(0, acks());
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void semConseguirGravarODiarioNadaEConfirmado() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        Files.createDirectories(journal().resolve("ocupado")); // o lugar do arquivo esta tomado: gravar falha
        m.pull();
        assertEquals(0, acks(), "sem diario, sem confirmacao: a Platform entrega de novo");
        assertEquals("OFFERED", gw.attemptState);
    }

    @Test
    void pausadaNadaEEntregueEDesabilitarApagaODiario() throws Exception {
        RemoteMachine m = enabled(true);
        m.pause();
        gw.queue();
        m.pull();
        assertEquals("QUEUED", gw.attemptState);
        assertFalse(Files.exists(journal()));
        m.resume();
        gw.failAcks = 1;
        m.pull();
        assertTrue(Files.exists(journal()));
        m.disable();
        assertFalse(Files.exists(journal()), "o diario sai junto com o vinculo");
        assertEquals(Status.NOT_ENABLED, m.view().status());
        assertNull(gw.credential);
    }

    @Test
    void oDiarioDeOutraMaquinaOuIlegivelNaoVale() throws Exception {
        RemoteMachine m = enabled(true);
        gw.queue();
        gw.failAcks = 1;
        m.pull();
        AttemptJournal j = new AttemptJournal(dir, new WindowsKeyring());
        assertTrue(j.load(gw.agentId).isPresent());
        assertTrue(j.load(gw.workerId).isEmpty(), "outra maquina (vinculo anterior)");
        assertFalse(j.load(gw.agentId).orElseThrow().toString().contains(gw.attemptToken));
        Files.writeString(journal(), "{\"versao\":2,", StandardCharsets.UTF_8);
        assertTrue(j.load(gw.agentId).isEmpty());
    }
}
