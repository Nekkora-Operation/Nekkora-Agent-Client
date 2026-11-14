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

    /**
     * Os ambientes ATIVOS da organizacao que a pessoa pode acessar, na ordem da Platform. A organizacao e conferida pelo
     * servidor a cada chamada: fora do alcance da pessoa, {@link Failure#NOT_FOUND}.
     */
    List<Workspace> workspaces(java.util.UUID organization) throws Failed;

    /** As automacoes ativas do ambiente e o que a pessoa pode fazer com elas, na ordem da Platform. */
    Automation.Listing automations(java.util.UUID organization, java.util.UUID workspace) throws Failed;

    /**
     * Renomeia a automacao NA PLATFORM. Quem decide se a pessoa pode e o servidor, nesta chamada: sem a permissao,
     * {@link Failure#DENIED}; fora do alcance, {@link Failure#NOT_FOUND}; nome recusado, {@link Failure#REJECTED};
     * automacao ou ambiente inativo, {@link Failure#CONFLICT}. Nao toca em versoes, pacotes nem manifestos.
     *
     * @return o nome como ficou gravado
     */
    String rename(java.util.UUID organization, java.util.UUID automation, String name) throws Failed;

    /** Por que uma consulta nao trouxe o resultado. */
    enum Failure {
        /** A sessao nao vale mais: o aplicativo volta para a entrada. */
        SESSION_ENDED,
        /** A Platform respondeu que esta conta nao pode ver isto. */
        DENIED,
        /** Fora do alcance da pessoa, ou nao existe mais. */
        NOT_FOUND,
        /** A Platform nao aceitou o que foi enviado (um nome invalido, por exemplo). */
        REJECTED,
        /** O estado atual nao permite a acao (automacao ou ambiente inativo). */
        CONFLICT,
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
