package com.nekkora.client.run;

import java.util.EnumSet;
import java.util.Set;

/**
 * Ciclo de vida de uma execucao local. As transicoes sao fechadas: qualquer outra e recusada ({@link #canMoveTo}).
 * {@link #SUCCEEDED} com o codigo {@code AGT-EXT-001} e "terminou sem erro sinalizado": codigo de saida 0 sozinho nao
 * prova sucesso de negocio; so o sinal do script o confirma. Portado do Nekkora Agent 9b1e7e1, sem mudanca.
 */
public enum ExecutionState {
    /** Pedido aceito, ainda nao lancado. */
    READY("Aguardando início"),
    /** Conferencias finais e lancamento do processo. */
    STARTING("Iniciando"),
    /** Processo lancado e acompanhado. */
    RUNNING("Em andamento"),
    SUCCEEDED("Concluída com sucesso"),
    FAILED("Falhou"),
    CANCELLED("Cancelada"),
    TIMED_OUT("Tempo esgotado"),
    /** Nada foi iniciado: uma pre-condicao falhou (sessao, pacote, aprovacao, ambiente). */
    BLOCKED("Bloqueada"),
    /** O processo terminou (ou foi perdido) sem prova do resultado: nada e presumido e nada e repetido. */
    OUTCOME_UNKNOWN("Resultado desconhecido");

    private final String label;

    ExecutionState(String label) {
        this.label = label;
    }

    /** Texto para a interface. */
    public String label() {
        return label;
    }

    public boolean terminal() {
        return this != READY && this != STARTING && this != RUNNING;
    }

    /** Transicoes permitidas a partir deste estado. */
    public Set<ExecutionState> next() {
        return switch (this) {
            case READY -> EnumSet.of(STARTING, BLOCKED, CANCELLED);
            // OUTCOME_UNKNOWN: o Agent caiu entre lancar e registrar o processo - pode ter comecado; nada e presumido
            case STARTING -> EnumSet.of(RUNNING, BLOCKED, FAILED, CANCELLED, OUTCOME_UNKNOWN);
            case RUNNING -> EnumSet.of(SUCCEEDED, FAILED, CANCELLED, TIMED_OUT, OUTCOME_UNKNOWN);
            default -> EnumSet.noneOf(ExecutionState.class);
        };
    }

    public boolean canMoveTo(ExecutionState target) {
        return next().contains(target);
    }
}
