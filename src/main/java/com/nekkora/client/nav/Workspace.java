package com.nekkora.client.nav;

import java.util.Objects;
import java.util.UUID;

/**
 * Um ambiente da organizacao que a pessoa pode acessar. Na Platform o conceito se chama workspace; "Ambiente" e so o
 * rotulo da tela. A chave e {@code id}.
 */
public record Workspace(UUID id, String name) {

    public Workspace {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
    }
}
