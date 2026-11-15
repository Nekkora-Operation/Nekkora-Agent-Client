package com.nekkora.client.pack;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * O que entra no pacote gerado. As regras sao as do empacotador Python ({@code tools/external-package/nekkora_empacotar.py})
 * e do validador ({@link ExecutionPackage#forbidden}): as mesmas fixtures provam a equivalencia. Nada some em silencio: cada
 * arquivo fora do pacote tem um motivo mostrado a pessoa, e os dados de exemplo podem ser incluidos por escolha dela.
 */
public final class InclusionRules {
    private InclusionRules() {}

    /** Situacao de um arquivo do projeto. */
    public enum Rule {
        /** Entra no pacote. */
        INCLUDE("Incluído"),
        /** Entra, mas o nome sugere credencial: a pessoa confere (e pode deixar fora). */
        INCLUDE_WARNED("Incluído — confira: o nome sugere credencial ou configuração sensível"),
        /** Fora por padrao (dados, saidas de build); a pessoa pode incluir um exemplo SEM dados reais. */
        DATA("Fora por padrão: dados ou saída de build (pode incluir um exemplo sem dados reais)"),
        /** Nunca entra: ambiente virtual, cache, controle de versao, configuracao de editor. */
        ENVIRONMENT("Fora: ambiente virtual, cache ou controle de versão"),
        /** Nunca entra: credencial tipica (.env, chaves, certificados). */
        SECRET("Fora: possível credencial (.env, chave ou certificado)"),
        /**
         * Fora: arquivo ou pasta de ferramenta iniciado por ponto ({@code .gitignore}, {@code .python-version}, {@code .github/}).
         * O formato do pacote nao aceita esse nome; o empacotador Python recusa o projeto inteiro, o assistente deixa o
         * arquivo fora e mostra o motivo.
         */
        TOOLING("Fora: configuração de ferramenta iniciada por ponto (o formato do pacote não aceita esse nome)"),
        /** O descritor do pacote ja existente (nao e arquivo do projeto). */
        DESCRIPTOR("Descritor do pacote");

        private final String label;

        Rule(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        public boolean included() {
            return this == INCLUDE || this == INCLUDE_WARNED;
        }
    }

    private static final Set<String> DATA_SEGMENTS = Set.of("dados", "data", "dist", "build");
    private static final List<String> DATA_SUFFIXES = List.of(".csv", ".xlsx", ".xls", ".db", ".sqlite", ".parquet", ".log");
    private static final List<String> SECRET_SUFFIXES = List.of(".pem", ".key", ".pfx", ".p12");
    /** Nomes que costumam ter credencial e que o contrato nao proibe: entram, com aviso. */
    private static final List<String> SENSITIVE_HINTS = List.of("credential", "credencia", "secret", "segredo", "token", "password", "senha",
            "service-account", "service_account", ".kdbx", ".npmrc", ".git-credentials", "id_dsa", "id_ecdsa", ".ppk", ".jks", ".keystore");

    /** Regra padrao de um arquivo (caminho relativo a raiz do projeto, com {@code /}). */
    public static Rule of(String rel) {
        if (ExecutionPackage.forbidden(rel)) return secret(rel) ? Rule.SECRET : Rule.ENVIRONMENT;
        if (dataByDefault(rel)) return Rule.DATA;
        for (String seg : rel.split("/")) if (seg.startsWith(".")) return Rule.TOOLING;
        String low = rel.toLowerCase(Locale.ROOT);
        for (String h : SENSITIVE_HINTS) if (low.contains(h)) return Rule.INCLUDE_WARNED;
        return Rule.INCLUDE;
    }

    /** Proibido por ser credencial tipica (e nao ambiente ou cache): merece aviso, nao so a exclusao. */
    static boolean secret(String rel) {
        // so o NOME do arquivo: uma pasta ".env" e ambiente virtual, um arquivo ".env" e credencial tipica
        String[] segs = rel.split("/");
        for (int i = 0; i < segs.length - 1; i++) {
            if (ExecutionPackage.forbidden(segs[i])) return false;
        }
        String l = segs[segs.length - 1].toLowerCase(Locale.ROOT);
        if (l.startsWith(".env") || l.startsWith("id_rsa") || l.startsWith("id_ed25519") || l.equals(".netrc") || l.equals(".pypirc")) return true;
        for (String s : SECRET_SUFFIXES) if (l.endsWith(s)) return true;
        return false;
    }

    /** Igual ao {@code data_by_default} do empacotador Python. */
    static boolean dataByDefault(String rel) {
        String[] segs = rel.toLowerCase(Locale.ROOT).split("/");
        for (int i = 0; i < segs.length - 1; i++) if (DATA_SEGMENTS.contains(segs[i]) || segs[i].endsWith(".egg-info")) return true;
        String last = segs[segs.length - 1];
        for (String s : DATA_SUFFIXES) if (last.endsWith(s)) return true;
        return false;
    }
}
