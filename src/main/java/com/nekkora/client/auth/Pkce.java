package com.nekkora.client.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Segredos de UMA tentativa de entrada (RFC 7636 e RFC 6749 10.12): {@code state} e {@code code_verifier}, cada um com
 * 32 bytes do gerador criptografico do sistema (43 caracteres base64url), e o {@code code_challenge} S256 do
 * verificador. Ficam so na memoria do processo; nunca vao para disco, log ou tela.
 */
public record Pkce(String state, String verifier, String challenge) {

    private static final SecureRandom RANDOM = new SecureRandom();

    public static Pkce generate() {
        String verifier = random();
        return new Pkce(random(), verifier, s256(verifier));
    }

    private static String random() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comparacao em tempo constante (o {@code state} do retorno contra o da tentativa). */
    public static boolean same(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String toString() {
        return "Pkce[...]"; // nunca os valores
    }
}
