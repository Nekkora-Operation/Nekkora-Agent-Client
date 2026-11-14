package com.nekkora.client.nav;

import java.util.Locale;

/**
 * A pessoa que entrou. {@code displayName} pode faltar; o contrato nao tem foto nem empresa, entao elas nao existem aqui.
 */
public record Profile(String email, String displayName) {

    public boolean hasName() {
        return displayName != null && !displayName.isBlank();
    }

    /**
     * Iniciais do NOME para o avatar: primeira letra do primeiro e do ultimo termo; um termo so, uma letra. Sem nome,
     * vazio (o avatar usa o desenho neutro). Nunca saem do e-mail.
     */
    public String initials() {
        if (!hasName()) {
            return "";
        }
        String[] terms = displayName.trim().split("\\s+");
        String first = letter(terms[0]);
        String last = terms.length > 1 ? letter(terms[terms.length - 1]) : "";
        return (first + last).toUpperCase(Locale.ROOT);
    }

    /** Primeira letra ou digito do termo (um caractere completo, mesmo fora do plano basico). */
    private static String letter(String term) {
        for (int i = 0; i < term.length();) {
            int cp = term.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) {
                return new String(Character.toChars(cp));
            }
            i += Character.charCount(cp);
        }
        return "";
    }
}
