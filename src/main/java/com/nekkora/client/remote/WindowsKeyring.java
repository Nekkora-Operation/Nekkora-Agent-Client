package com.nekkora.client.remote;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import com.sun.jna.platform.win32.Crypt32Util;
import com.sun.jna.platform.win32.WinCrypt;

/**
 * DPAPI do usuario do Windows ({@code CryptProtectData}), sem nenhuma janela do sistema. A entropia fixa so separa este
 * uso de outros do mesmo usuario; nao e segredo. A falha do sistema vira {@link IOException} sem nenhum detalhe do
 * conteudo. E o unico lugar do aplicativo que chama codigo nativo.
 */
public final class WindowsKeyring implements Keyring {

    private static final byte[] ENTROPY = "nekkora-agent-client/remote-machine/1".getBytes(StandardCharsets.US_ASCII);

    private volatile Boolean available;

    @Override
    public boolean available() {
        Boolean a = available;
        if (a == null) {
            try {
                byte[] probe = {7, 1, 9};
                a = Arrays.equals(probe, unprotect(protect(probe)));
            } catch (IOException | LinkageError | RuntimeException e) {
                a = false;
            }
            available = a;
        }
        return a;
    }

    @Override
    public byte[] protect(byte[] plain) throws IOException {
        try {
            return Crypt32Util.cryptProtectData(plain, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, "", null);
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("protecao indisponivel");
        }
    }

    @Override
    public byte[] unprotect(byte[] blob) throws IOException {
        try {
            return Crypt32Util.cryptUnprotectData(blob, ENTROPY, WinCrypt.CRYPTPROTECT_UI_FORBIDDEN, null);
        } catch (RuntimeException | LinkageError e) {
            throw new IOException("protecao indisponivel");
        }
    }
}
