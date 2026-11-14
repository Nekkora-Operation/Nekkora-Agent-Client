package com.nekkora.client.nav;

import java.util.List;

/**
 * O que a pessoa que entrou pode consultar na Platform. Porta: quem fala com a rede e a camada {@code auth}; daqui para
 * cima ninguem conhece token, endereco ou HTTP.
 * <p>
 * As chamadas BLOQUEIAM e sao feitas fora da fila de eventos por {@link Navigation}. Toda falha chega como
 * {@link Failed}, ja classificada; uma consulta que falhou nunca devolve lista vazia.
 */
public interface Directory {

    /** A propria identidade, como a Platform a conhece. */
    Profile profile() throws Failed;

    /** As organizacoes de que a pessoa participa agora, na ordem da Platform (nome e, no empate, identificador). */
    List<Organization> organizations() throws Failed;

    /** Por que uma consulta nao trouxe o resultado. */
    enum Failure {
        /** A sessao nao vale mais: o aplicativo volta para a entrada. */
        SESSION_ENDED,
        /** A Platform respondeu que esta conta nao pode ver isto. */
        DENIED,
        /** Fora do alcance da pessoa, ou nao existe mais. */
        NOT_FOUND,
        RATE_LIMITED,
        SERVER,
        NETWORK,
        TIMEOUT,
        TLS,
        /** Resposta que nao e a do contrato. */
        INVALID
    }

    /** Consulta que nao trouxe resultado. Sem mensagem tecnica nem pilha: so a classificacao. */
    final class Failed extends Exception {
        private static final long serialVersionUID = 1L;
        private final Failure failure;

        public Failed(Failure failure) {
            super(failure.name(), null, false, false);
            this.failure = failure;
        }

        public Failure failure() {
            return failure;
        }
    }
}
