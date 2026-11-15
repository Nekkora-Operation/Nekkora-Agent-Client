package com.nekkora.client.remote;

import java.io.IOException;

/**
 * Protecao do segredo da maquina pelo sistema operacional. A unica implementacao do aplicativo e a do Windows
 * ({@link WindowsKeyring}): o que ela devolve so e legivel pelo mesmo usuario do Windows, neste computador. Sem essa
 * protecao disponivel, o aplicativo NAO habilita a maquina: o segredo nunca e guardado em texto claro.
 */
public interface Keyring {

    /** {@code true} so se uma ida e volta de teste funcionou de fato neste computador. */
    boolean available();

    byte[] protect(byte[] plain) throws IOException;

    byte[] unprotect(byte[] blob) throws IOException;
}
