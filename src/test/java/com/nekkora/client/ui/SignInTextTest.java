package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.nekkora.client.signin.SignInState.Notice;

/** As frases que o aplicativo mostra para cada resultado da entrada (AGENT-CLIENT-LOGIN-002 T01). */
class SignInTextTest {

    @Test
    void cadaResultadoConfirmadoTemAFraseCombinada() {
        assertEquals("Entrada não autorizada. Você pode tentar novamente quando quiser.", SignInText.of(Notice.DECLINED));
        assertEquals("Acesso não permitido. Sua conta não está autorizada a entrar neste aplicativo.", SignInText.of(Notice.ACCESS_DENIED));
        assertEquals("Não foi possível concluir a entrada. A Platform encontrou um problema. Tente novamente em instantes.", SignInText.of(Notice.SERVER));
        assertEquals("Não foi possível conectar à Platform. Verifique sua conexão e tente novamente.", SignInText.of(Notice.NETWORK));
        assertEquals("O tempo para entrar terminou. Clique em Entrar para iniciar uma nova tentativa.", SignInText.of(Notice.EXPIRED));
        assertEquals("Não foi possível validar a entrada com segurança. Inicie uma nova tentativa.", SignInText.of(Notice.INVALID_RETURN));
        assertEquals("Não foi possível estabelecer uma conexão segura com a Platform. Tente novamente mais tarde.", SignInText.of(Notice.TLS));
        assertEquals("Entrada cancelada.", SignInText.of(Notice.CANCELLED));
        assertEquals("Aguardando login no navegador...", SignInText.WAITING);
        assertEquals("", SignInText.of(Notice.NONE));
    }

    @Test
    void todoAvisoTemFraseProprioSemDetalheTecnico() {
        Set<String> frases = new HashSet<>();
        for (Notice n : EnumSet.complementOf(EnumSet.of(Notice.NONE))) {
            String t = SignInText.of(n);
            assertFalse(t.isBlank(), n + " sem frase");
            assertTrue(frases.add(t), n + " repete a frase de outro resultado");
            assertTrue(t.endsWith("."), n + " termina a frase");
            assertFalse(t.matches("(?is).*(exception|stack|\\bat \\w+\\.|https?://|localhost|127\\.0|\\d{3}|token|pkce|state|cookie|c[oó]digo|tls|ssl|certificado).*"),
                    n + " leva detalhe tecnico: " + t);
        }
    }

    @Test
    void soOQueAPessoaDecidiuOuOTempoCausouENeutro() {
        assertEquals(EnumSet.of(Notice.NONE, Notice.CANCELLED, Notice.DECLINED, Notice.EXPIRED, Notice.SESSION_EXPIRED, Notice.SIGNED_OUT),
                EnumSet.copyOf(java.util.Arrays.stream(Notice.values()).filter(SignInText::neutral).toList()));
        assertEquals("", SignInText.kind(Notice.NONE));
        assertEquals("Aviso da entrada", SignInText.kind(Notice.CANCELLED));
        assertEquals("Aviso da entrada", SignInText.kind(Notice.DECLINED));
        assertEquals("Erro na entrada", SignInText.kind(Notice.ACCESS_DENIED));
        assertEquals("Erro na entrada", SignInText.kind(Notice.SERVER));
        assertEquals("Erro na entrada", SignInText.kind(Notice.TLS));
    }
}
