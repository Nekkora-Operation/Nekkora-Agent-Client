package com.nekkora.client.nav;

import java.util.Objects;
import java.util.UUID;

/**
 * Uma organizacao de que a pessoa participa. A CHAVE e {@code id}: duas organizacoes podem ter o mesmo nome.
 * {@code handle} e o identificador legivel da Platform (pode faltar). {@code awaitingActivation}: a Nekkora ainda nao
 * ativou a organizacao; o aplicativo so informa, nunca ativa.
 */
public record Organization(UUID id, String name, String handle, boolean awaitingActivation) {

    public Organization {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
    }

    /** Identificador complementar mostrado no cartao: o handle, ou o comeco do id quando nao ha handle. */
    public String label() {
        return handle != null && !handle.isBlank() ? handle : id.toString().substring(0, 8);
    }
}
