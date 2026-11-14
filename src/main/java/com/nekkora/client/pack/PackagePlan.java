package com.nekkora.client.pack;

import java.util.List;
import java.util.Set;

/**
 * As escolhas da pessoa para gerar um pacote Python externo a partir do ZIP importado. Nome e entrada sao os campos
 * principais; Python e dependencias so sao pedidos quando o projeto nao os declara no formato aceito.
 *
 * @param name nome do pacote ({@code name} do descritor)
 * @param version versao DECLARADA no pacote ({@code packageVersion}); sugestao local, nunca o numero de versao da Platform
 * @param entry o {@code .py} de entrada, escolhido explicitamente pela pessoa
 * @param python versao de Python no formato do descritor
 * @param dependencies dependencias com versao exata (a lista que vai para o descritor)
 * @param dependenciesConfirmed a pessoa conferiu a lista (obrigatorio quando o projeto nao a declara completa)
 * @param includeData arquivos fora por padrao (dados) que a pessoa decidiu incluir
 * @param exclude arquivos incluidos por padrao que a pessoa decidiu deixar fora
 * @param rebuild gerar um pacote NOVO a partir de um pacote externo existente (a pessoa mudou entrada ou requisitos)
 */
public record PackagePlan(String name, String version, String entry, String python, List<String> dependencies, boolean dependenciesConfirmed,
                          Set<String> includeData, Set<String> exclude, boolean rebuild) {

    public PackagePlan {
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        includeData = includeData == null ? Set.of() : Set.copyOf(includeData);
        exclude = exclude == null ? Set.of() : Set.copyOf(exclude);
    }

    /** Gerador declarado nos pacotes do assistente (declaracao, nao prova de origem). */
    public static final String GENERATOR = "nekkora-agent-assistente/1.0.0";
}
