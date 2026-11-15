package com.nekkora.client.pack;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Onde um pacote local vive: a organizacao e o ambiente (workspace) escolhidos pela pessoa. Os dois sao sempre UUID: o
 * texto deles vira nome de pasta, e um UUID nunca tem barra, ponto nem nome reservado.
 *
 * @param organizationId organizacao
 * @param workspaceId ambiente da organizacao
 */
public record PackageContext(UUID organizationId, UUID workspaceId) {

    private static final Pattern CANONICAL = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    public PackageContext {
        Objects.requireNonNull(organizationId, "organizationId");
        Objects.requireNonNull(workspaceId, "workspaceId");
    }

    /** Dos textos recebidos da Platform. Qualquer coisa que nao seja um UUID na forma canonica: {@link IllegalArgumentException}. */
    public static PackageContext parse(String organizationId, String workspaceId) {
        return new PackageContext(uuid(organizationId), uuid(workspaceId));
    }

    /** UUID na forma canonica (36 caracteres); a forma abreviada que {@link UUID#fromString} aceita e recusada. */
    public static UUID uuid(String text) {
        if (text == null || !CANONICAL.matcher(text).matches()) throw new IllegalArgumentException("identificador fora do formato UUID");
        return UUID.fromString(text);
    }
}
