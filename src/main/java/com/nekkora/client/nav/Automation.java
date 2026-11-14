package com.nekkora.client.nav;

import java.util.Objects;
import java.util.UUID;

/**
 * Uma automacao do ambiente, como a Platform a lista: metadados, nunca o codigo. {@code published} e a ultima versao
 * PUBLICADA (nulo se so ha rascunho); listar a automacao nao quer dizer que o pacote dela esteja nesta maquina.
 */
public record Automation(UUID id, String name, Published published) {

    public Automation {
        Objects.requireNonNull(id);
        Objects.requireNonNull(name);
    }

    /**
     * Versao publicada. {@code number} e o numero da versao NA PLATFORM; {@code packageVersion} e a versao DECLARADA no
     * pacote (pode faltar). Sao coisas diferentes e a tela mostra as duas com os seus nomes. {@code fingerprint} e a
     * impressao digital do pacote publicado, para reconhecer um pacote local identico.
     */
    public record Published(UUID versionId, int number, String packageName, String packageVersion, String fingerprint, String sha256, long sizeBytes) {
        public Published {
            Objects.requireNonNull(versionId);
        }

        /** Sem o que conferir num download (Platform anterior ao contrato desktop-navigation/3). */
        public Published(UUID versionId, int number, String packageName, String packageVersion, String fingerprint) {
            this(versionId, number, packageName, packageVersion, fingerprint, null, -1);
        }
    }

    /**
     * Uma versao da automacao, como a Platform a lista (AGENT-CLIENT-PACOTES-001). {@code published} falso: rascunho, que
     * nunca e baixado. {@code sha256} e {@code sizeBytes} sao os do ZIP guardado pela Platform: o que o aplicativo confere
     * depois de baixar. Faltando qualquer um deles (versao sem pacote, ou Platform anterior), a versao nao e baixavel.
     */
    public record Version(UUID versionId, int number, boolean published, String packageName, String packageVersion, String fingerprint, String sha256,
                          long sizeBytes) {
        public Version {
            Objects.requireNonNull(versionId);
        }

        public boolean downloadable() {
            return published && fingerprint != null && sha256 != null && sizeBytes > 0;
        }
    }

    /** O que a pessoa pode fazer com as automacoes deste ambiente, segundo a Platform (o servidor confere de novo na acao). */
    public record Capabilities(boolean rename, boolean execute, boolean download) {
        public static final Capabilities NONE = new Capabilities(false, false, false);

        /** Sem a capacidade de baixar (Platform anterior ao contrato desktop-navigation/3, ou sessao sem o escopo). */
        public Capabilities(boolean rename, boolean execute) {
            this(rename, execute, false);
        }
    }

    /** As automacoes de um ambiente e as capacidades da pessoa nele. */
    public record Listing(java.util.List<Automation> items, Capabilities capabilities, boolean truncated) {
        public Listing {
            items = java.util.List.copyOf(items);
            Objects.requireNonNull(capabilities);
        }
    }
}
