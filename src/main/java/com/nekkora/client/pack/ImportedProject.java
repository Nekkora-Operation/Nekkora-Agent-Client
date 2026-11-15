package com.nekkora.client.pack;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * AGENT-PACOTES-001: o ZIP importado, ja conferido e extraido numa pasta PRIVADA da operacao (nunca na pasta da pessoa).
 * Tudo o que vem daqui e dado nao confiavel: nomes saneados so para a tela; nada foi executado.
 *
 * @param source o ZIP escolhido (nunca alterado)
 * @param sourceSize tamanho do ZIP quando foi copiado
 * @param sourceModified data de modificacao do ZIP quando foi copiado
 * @param sourceSha256 SHA-256 dos bytes copiados (os mesmos que foram lidos)
 * @param workDir pasta da operacao (apagada ao concluir ou cancelar)
 * @param root a raiz do projeto reconhecida, no disco
 * @param rootFolder nome da pasta externa unica do ZIP, tratada como raiz ({@code ""} se os arquivos estao na raiz do ZIP)
 * @param kind o que o ZIP e
 * @param existing o pacote existente conferido pelo validador ({@code EXTERNAL_PACKAGE})
 * @param refusal por que o pacote existente nao vale ({@code EXTERNAL_INVALID}), com o codigo do validador
 * @param files todos os arquivos do projeto, relativos a raiz, com a regra padrao
 * @param pythonFiles os {@code .py} que podem ser entrada (em ordem)
 * @param suggestedEntry sugestao por nome convencional ({@code main.py}, {@code __main__.py}); NUNCA confirmada sozinha
 * @param declarations o que o projeto declara sobre Python e dependencias
 * @param blockers impedimentos para gerar um pacote novo a partir deste ZIP (motivo e texto para a tela)
 * @param notes avisos que nao impedem (descritor em subpasta, arquivos ignorados...)
 * @param folder quando a origem e uma PASTA de projeto (e nao um ZIP): o retrato tirado na abertura; senao {@code null}.
 *        Nesse caso {@code source} e a pasta (caminho real), {@code sourceSize} o total copiado, {@code sourceModified} o
 *        instante da abertura e {@code sourceSha256} e {@code null}
 */
public record ImportedProject(Path source, long sourceSize, Instant sourceModified, String sourceSha256, Path workDir, Path root, String rootFolder, Kind kind,
                              ExecutionPackage existing, ExecRefused refusal, List<ProjectFile> files, List<String> pythonFiles, Optional<String> suggestedEntry,
                              Declarations declarations, List<ImportProblem> blockers, List<String> notes, FolderSource folder) {

    public ImportedProject {
        files = List.copyOf(files);
        pythonFiles = List.copyOf(pythonFiles);
        blockers = List.copyOf(blockers);
        notes = List.copyOf(notes);
    }

    /** O que o ZIP e. */
    public enum Kind {
        /** Codigo sem descritor de pacote: o assistente prepara um manifesto externo novo. */
        CODE("Projeto Python (código sem manifesto)"),
        /** Pacote Python externo valido ({@code nekkora-external.json}): reconhecido como esta, sem regravar o descritor. */
        EXTERNAL_PACKAGE("Pacote Python externo já preparado (nekkora-external.json)"),
        /** Pacote do Studio ({@code manifest.json}) ou de laboratorio ({@code nekkora-agent-lab.json}): NAO aceito por este aplicativo. */
        UNSUPPORTED_PACKAGE("Pacote do Studio ou de laboratório (não aceito por este aplicativo)"),
        /** Descritor externo presente, mas o pacote nao e valido. */
        EXTERNAL_INVALID("Pacote Python externo inválido"),
        /** Descritores de formatos diferentes na raiz. */
        CONFLICT("Formatos de pacote conflitantes");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** E um pacote pronto, que pode ser guardado como esta. */
        public boolean readyPackage() {
            return this == EXTERNAL_PACKAGE;
        }
    }

    /**
     * Um arquivo do projeto.
     *
     * @param path caminho relativo a raiz, com {@code /} (texto do ZIP; para a tela use {@link ZipNames#display})
     * @param size bytes
     * @param hash hash do conteudo pela regra do pacote ({@code sha256:...}, texto com LF)
     * @param rule regra padrao
     * @param contractSafe o nome cabe no formato do pacote
     */
    public record ProjectFile(String path, long size, String hash, InclusionRules.Rule rule, boolean contractSafe) {}

    /**
     * O retrato de uma pasta de projeto, tirado UMA vez na abertura. Tudo o que e conferido e empacotado depois vem da
     * copia privada; a pasta da pessoa so e lida de novo para saber se mudou.
     *
     * @param root a pasta escolhida (caminho real)
     * @param stamps arquivos COPIADOS (caminho relativo com {@code /}) e como estavam na origem quando foram lidos
     * @param skippedDirs subpastas que nao foram lidas nem copiadas (ambiente virtual, cache, controle de versao), com
     *        {@code /} no fim
     * @param unlisted quantos arquivos fora do pacote deixaram de ser listados um a um (pastas de dados muito grandes)
     */
    public record FolderSource(Path root, java.util.Map<String, Stamp> stamps, List<String> skippedDirs, int unlisted) {
        public FolderSource {
            stamps = java.util.Collections.unmodifiableMap(new java.util.TreeMap<>(stamps));
            skippedDirs = List.copyOf(skippedDirs);
        }
    }

    /** Tamanho e data de um arquivo da origem no momento em que foi lido. */
    public record Stamp(long size, Instant modified) {}

    /** A origem e uma pasta de projeto (e nao um ZIP). */
    public boolean fromFolder() {
        return folder != null;
    }

    public Optional<ProjectFile> file(String path) {
        return files.stream().filter(f -> f.path().equals(path)).findFirst();
    }
}
