package com.nekkora.client.pack;

import java.util.Objects;

/** Pre-condicao de execucao recusada, com um codigo do catalogo (nunca caminho, valor ou mensagem de excecao). */
public final class ExecRefused extends Exception {
    private static final long serialVersionUID = 1L;
    private final transient ErrorCode code;

    public ExecRefused(ErrorCode code) {
        super(code.code());
        this.code = Objects.requireNonNull(code);
    }

    public ErrorCode code() {
        return code;
    }
}
