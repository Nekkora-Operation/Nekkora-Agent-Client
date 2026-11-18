package com.nekkora.client.run;

import com.nekkora.client.pack.ErrorCode;
import java.time.Instant;
import java.util.Objects;

/**
 * O que o aplicativo registra de uma execucao ({@code nekkora-agent-client/execution} 1.0.0): identificador, CONTEXTO
 * (organizacao, ambiente, pacote, automacao), estado, codigo da lista fechada, tempos, posse do processo, codigo de saida
 * e quantas suspensoes e bloqueios de tela foram vistos. Nenhum caminho, valor, saida do processo ou argumento.
 *
 * <p>O {@link #context} e imutavel: nenhum metodo deste registro o troca (cada copia leva o mesmo valor) e o
 * {@link RunStore} recusa gravar, na pasta de uma execucao, um registro com outro contexto.
 *
 * <p>O registro e LOCAL: fica so no disco desta maquina. Este nucleo nao envia nada a Platform.
 *
 * <p><b>Origem</b> (AGENT-CLIENT-PACOTES-001 T04): toda execucao deste aplicativo e {@link #ORIGIN} - iniciada pela pessoa
 * NESTE aplicativo. A origem nao e um parametro: nenhum metodo de {@link RunController} ou {@code Runner} a recebe, e nao
 * existe aqui execucao "da Platform". O valor e gravado no registro para que ele diga, por si, de onde veio; um registro
 * no disco com OUTRA origem nao foi escrito por este aplicativo e e recusado na leitura ({@link RunStore#load}). Estar
 * logado, ter organizacao escolhida, estar online ou voltar a ter rede nao muda isso, e nada e enviado depois.
 */
public record ExecutionRecord(ExecutionId id, RunContext context, ExecutionState state, ErrorCode code, Instant created, Instant started, Instant ended,
                              ProcessOwner owner, Integer exitCode, int suspensions, int locks, boolean adopted, long outputBytes, Cancellation cancellation) {

    public static final String SCHEMA = "nekkora-agent-client/execution";
    public static final String VERSION = "1.0.0";
    /** A unica origem que este aplicativo produz e aceita: execucao iniciada nele, com registros so locais. */
    public static final String ORIGIN = "LOCAL_AGENT";
    /**
     * A origem das execucoes que nao nasceram de uma aprovacao da pessoa neste aplicativo (PLATFORM-CLIENT-EXECUCAO-REMOTA-001).
     * Ficam num armazem proprio ({@link RunStore#managed}); o armazem das execucoes locais nao as le.
     */
    public static final String MANAGED_ORIGIN = "PLATFORM";

    /** Quem pediu o cancelamento. */
    public enum CancelSource {
        /** A pessoa, pela janela. */
        LOCAL_UI,
        /** O aplicativo esta sendo encerrado. */
        APPLICATION_SHUTDOWN
    }

    /** Situacao do pedido de cancelamento: pedir nao e confirmar. */
    public enum CancelOutcome {
        /** Pedido registrado; a arvore do processo ainda nao foi encerrada. */
        PENDING,
        /** A arvore possuida foi encerrada: o estado e {@code CANCELLED}. */
        CONFIRMED,
        /** O processo terminou por conta propria antes do encerramento: o estado e o do desfecho, nao {@code CANCELLED}. */
        NOT_EFFECTIVE
    }

    public record Cancellation(Instant requested, CancelSource source, CancelOutcome outcome) {
        public Cancellation {
            Objects.requireNonNull(requested);
            Objects.requireNonNull(source);
            Objects.requireNonNull(outcome);
        }

        public Cancellation resolved(CancelOutcome o) {
            return new Cancellation(requested, source, o);
        }
    }

    public ExecutionRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(created, "created");
    }

    /** Registro novo, ainda nao lancado. */
    public static ExecutionRecord ready(ExecutionId id, RunContext context, Instant created) {
        return new ExecutionRecord(id, context, ExecutionState.READY, null, created, null, null, null, null, 0, 0, false, 0, null);
    }

    /** A impressao digital do pacote executado. */
    public String fingerprint() {
        return context.fingerprint();
    }

    /** Nova versao no estado {@code next}; transicao fora do ciclo de vida e recusada. */
    public ExecutionRecord moveTo(ExecutionState next, ErrorCode why, Instant at) {
        if (!state.canMoveTo(next)) throw new IllegalStateException("transição inválida: " + state + " → " + next);
        Instant s = next == ExecutionState.RUNNING ? at : started;
        Instant e = next.terminal() ? at : ended;
        return new ExecutionRecord(id, context, next, why, created, s, e, owner, exitCode, suspensions, locks, adopted, outputBytes, cancellation);
    }

    public ExecutionRecord withOwner(ProcessOwner o) {
        return new ExecutionRecord(id, context, state, code, created, started, ended, o, exitCode, suspensions, locks, adopted, outputBytes, cancellation);
    }

    public ExecutionRecord withExit(Integer exit, long bytes) {
        return new ExecutionRecord(id, context, state, code, created, started, ended, owner, exit, suspensions, locks, adopted, bytes, cancellation);
    }

    public ExecutionRecord withSuspension() {
        return new ExecutionRecord(id, context, state, code, created, started, ended, owner, exitCode, suspensions + 1, locks, adopted, outputBytes, cancellation);
    }

    public ExecutionRecord withLock() {
        return new ExecutionRecord(id, context, state, code, created, started, ended, owner, exitCode, suspensions, locks + 1, adopted, outputBytes, cancellation);
    }

    public ExecutionRecord adoptedAfterRestart() {
        return new ExecutionRecord(id, context, state, code, created, started, ended, owner, exitCode, suspensions, locks, true, outputBytes, cancellation);
    }

    public ExecutionRecord withCancellation(Cancellation c) {
        return new ExecutionRecord(id, context, state, code, created, started, ended, owner, exitCode, suspensions, locks, adopted, outputBytes, c);
    }
}
