package com.nekkora.client;

import java.util.List;

/**
 * PLATFORM-CLIENT-EXECUCAO-REMOTA-001, T05 - os mesmos vetores para os dois lugares que removem segredos de uma linha da
 * saida do processo: a fila da tentativa remota e o arquivo da execucao local. As regras estao escritas duas vezes (as
 * camadas nao se conhecem); estes vetores prendem as duas ao mesmo comportamento.
 */
public final class RedactionVectors {

    private RedactionVectors() {
    }

    /** Entrada e saida esperada. */
    public record Vector(String in, String out) {
    }

    public static final List<Vector> ALL = List.of(
            new Vector("linha comum, sem segredo: autorizado em 3 passos", "linha comum, sem segredo: autorizado em 3 passos"),
            new Vector("cred nka1." + "A".repeat(20) + ".x fim", "cred [removido] fim"),
            new Vector("codigo nkp1.org.enr." + "B".repeat(43), "codigo [removido]"),
            new Vector("Authorization: Bearer abcdefghijklmnop e o resto", "Authorization: [removido] e o resto"),
            new Vector("proxy-authorization=Basic dXNlcjpzZW5oYQ== fim", "proxy-authorization=[removido] fim"),
            new Vector("chamando com bearer abcdefghijklmnop agora", "chamando com bearer [removido] agora"),
            new Vector("cabecalho Authorization: [removido] e o resto", "cabecalho Authorization: [removido] e o resto"),
            new Vector("ação concluída: 3 notas lançadas", "ação concluída: 3 notas lançadas"));
}
