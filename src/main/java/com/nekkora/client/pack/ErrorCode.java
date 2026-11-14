package com.nekkora.client.pack;

/**
 * Lista fechada de codigos de recusa do pacote local e da execucao local. O codigo e a descricao sao seguros para a
 * interface: nao carregam caminhos, enderecos, nomes nem mensagens de excecao.
 *
 * <p>Subconjunto da lista do Nekkora Agent (commit 9b1e7e1): os codigos e os textos mantidos sao os mesmos, letra por
 * letra. So {@link #EXEC_PACKAGE_UNSUPPORTED} e novo (pacote do Studio ou de laboratorio, que este aplicativo nao aceita).
 */
public enum ErrorCode {
    EXEC_PACKAGE_NOT_FOUND("AGT-EXE-001", "A pasta do pacote não existe ou não pôde ser lida."),
    EXEC_PACKAGE_UNKNOWN("AGT-EXE-002", "A pasta não é um pacote reconhecido (manifesto do Studio ou descritor de laboratório)."),
    EXEC_PACKAGE_INVALID("AGT-EXE-003", "O descritor do pacote é malformado ou de versão não suportada."),
    EXEC_PACKAGE_CHANGED("AGT-EXE-004", "Um arquivo do pacote difere do que o descritor registra (alterado depois da geração)."),
    EXEC_PACKAGE_UNSAFE("AGT-EXE-005", "O pacote tem caminho fora da pasta, ligação simbólica ou tamanho acima do limite."),
    EXEC_NOT_APPROVED("AGT-EXE-007", "O pacote não foi aprovado nesta máquina, ou mudou depois da aprovação."),
    EXEC_PYTHON_INVALID("AGT-EXE-008", "O Python escolhido não existe, não é executável ou não respondeu à verificação."),
    EXEC_OUTPUT_INVALID("AGT-EXE-011", "A pasta de saída não foi escolhida, não existe ou fica dentro do pacote ou do Agent."),
    EXEC_SESSION_UNAVAILABLE("AGT-EXE-012", "Não há sessão interativa do usuário disponível para executar."),
    EXEC_SESSION_LOCKED("AGT-EXE-013", "A sessão está bloqueada: a execução não começa com a tela bloqueada."),
    EXEC_ALREADY_RUNNING("AGT-EXE-014", "Já há uma execução em andamento neste usuário."),
    EXEC_LAUNCH_FAILED("AGT-EXE-015", "O processo não pôde ser iniciado."),
    EXEC_STORAGE_FAILED("AGT-EXE-016", "O registro da execução não pôde ser gravado."),
    EXEC_UNSUPPORTED_OS("AGT-EXE-017", "A execução local só é suportada no Windows nesta versão."),
    EXEC_TIMEOUT("AGT-EXE-023", "O prazo da execução terminou e o processo foi encerrado."),
    EXEC_CANCELLED("AGT-EXE-024", "A execução foi cancelada pela pessoa operadora."),
    EXEC_AGENT_RESTARTED("AGT-EXE-025", "O Agent reiniciou durante a execução; o fim não foi observado."),
    EXEC_SUSPENDED("AGT-EXE-026", "O Windows foi suspenso durante a execução."),
    /** NOVO neste aplicativo: o formato existe, mas nao e aceito aqui (nunca aceito em silencio). */
    EXEC_PACKAGE_UNSUPPORTED("AGT-EXE-030", "O pacote é do Studio ou de laboratório: este aplicativo só aceita pacotes Python externos "
            + "(nekkora-external.json)."),
    // pacote Python externo: codigo de saida 0 nao e sucesso de negocio
    EXT_EXIT_ZERO_NO_SIGNAL("AGT-EXT-001", "O processo terminou com código 0, sem erro sinalizado; o sucesso de negócio não foi sinalizado pelo script."),
    EXT_EXIT_NONZERO("AGT-EXT-002", "O processo terminou com código de saída diferente de zero."),
    EXT_BUSINESS_FAILURE("AGT-EXT-003", "O script sinalizou falha de negócio."),
    EXT_ENVIRONMENT_INCOMPATIBLE("AGT-EXT-022", "O ambiente Python preparado não atende à versão ou às dependências declaradas no pacote.");

    private final String code;
    private final String description;

    ErrorCode(String code, String description) {
        this.code = code;
        this.description = description;
    }

    /** Codigo estavel, por exemplo {@code AGT-EXE-004}. */
    public String code() {
        return code;
    }

    /** Descricao em portugues, sem dados do ambiente. */
    public String description() {
        return description;
    }

    /** Texto completo para a tela: descricao e codigo. */
    public String message() {
        return description + " [" + code + "]";
    }
}
