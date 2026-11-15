package com.nekkora.client.pack;

/**
 * Limites EXPLICITOS da leitura do ZIP importado, aplicados durante a extracao (bytes efetivamente produzidos), nunca so
 * pelos tamanhos declarados no cabecalho. Coerentes com o contrato de upload da Platform ({@code PackageInspector}: ZIP de
 * ate 50 MiB, ate 1000 itens, 50 MiB de conteudo no pacote). O projeto de origem pode ter mais conteudo que o pacote (dados
 * e ambiente ficam fora), entao o total descompactado da ORIGEM tem teto proprio, maior.
 *
 * @param zipBytes tamanho maximo do ZIP de origem
 * @param entries itens no diretorio central (arquivos e pastas)
 * @param totalBytes total descompactado da origem
 * @param fileBytes um arquivo descompactado
 * @param depth niveis de pasta
 * @param ratio expansao maxima de um arquivo acima de {@code ratioFloor} bytes
 * @param ratioFloor abaixo disto a razao nao e conferida (arquivos pequenos e muito repetitivos sao normais)
 */
public record ZipLimits(long zipBytes, int entries, long totalBytes, long fileBytes, int depth, int ratio, long ratioFloor) {

    public static final ZipLimits DEFAULT = new ZipLimits(50L * 1024 * 1024, 1000, 100L * 1024 * 1024, 50L * 1024 * 1024, 16, 200, 1024 * 1024);

    /** Limites do PACOTE gerado (contrato do Agent e da Platform). */
    public static final int PACKAGE_FILES = 500;
    public static final long PACKAGE_BYTES = 50L * 1024 * 1024;
}
