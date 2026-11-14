package com.nekkora.client.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.run.PythonEnvironments.Kind;
import com.nekkora.client.run.PythonEnvironments.State;
import com.nekkora.client.run.Runner;
import com.nekkora.client.theme.ClientTheme;

/**
 * Previas da preparacao COMPACTA da execucao nos dois temas e em 100%, 150% e 200% (AGENT-CLIENT-PREPARACAO-002), com dados
 * FICTICIOS (nenhum caminho desta maquina). E o formulario real, pintado ampliado; nao substitui o teste no executavel
 * empacotado, que mede a janela na escala de verdade. As imagens vao para {@code target/evidencias/preparacao-002/previas}.
 */
@Tag("desktop")
class PreparationPreviewTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias", "preparacao-002", "previas");
    private static final String REQ = ">=3.10,<3.14";

    private static Runner.PythonOption option(Kind kind, String folder, String path, String version, State state, String... pending) {
        return new Runner.PythonOption(kind + ":" + path, kind, folder, path, version, state, List.of(pending));
    }

    private static final Runner.PythonOption VIRTUAL = option(Kind.PROJECT, ".venv", "D:\\Projetos\\conciliacao\\.venv\\Scripts\\python.exe", "3.12.4", State.COMPATIBLE);
    private static final List<Runner.PythonOption> OPCOES = List.of(VIRTUAL,
            option(Kind.INSTALLED, null, "C:\\Python\\Python312\\python.exe", "3.12.4", State.DEPENDENCIES_PENDING, "requests==2.31.0 (não instalado)"),
            option(Kind.INSTALLED, null, "C:\\Python\\Python311\\python.exe", "3.11.9", State.DEPENDENCIES_PENDING, "requests==2.31.0 (instalada a versão 2.28.1)"),
            option(Kind.INSTALLED, null, "C:\\Python\\Python315\\python.exe", "3.15.0", State.VERSION_INCOMPATIBLE));

    private static RunForm form(boolean approved) {
        RunForm f = new RunForm(new RunForm.Events() {
            @Override
            public void pythonChosen() {
            }

            @Override
            public void locateEnvironment() {
            }

            @Override
            public void searchAgain() {
            }

            @Override
            public void chooseOutput() {
            }

            @Override
            public void approve() {
            }

            @Override
            public void start() {
            }

            @Override
            public void cancel() {
            }

            @Override
            public void close() {
            }

            @Override
            public void openFolder(String folder) {
            }
        });
        f.showPackage(new Runner.Checked("sha256:" + "ab".repeat(32), "abababab", "Conciliação bancária", "1.4.0", "main.py", REQ, List.of("requests==2.31.0"), approved,
                "D:\\Nekkora\\dados\\pacotes\\organizacao\\ambiente\\abababababab", "conciliacao-bancaria"), null);
        return f;
    }

    private static void clipped(Container c, List<String> cut) {
        for (Component child : c.getComponents()) {
            if (!child.isVisible() || child instanceof javax.swing.JScrollPane) {
                continue;
            }
            if (child.getWidth() + 1 < child.getMinimumSize().width || child.getHeight() + 1 < child.getMinimumSize().height) {
                cut.add(child.getClass().getSimpleName());
            }
            if (child instanceof Container inner) {
                clipped(inner, cut);
            }
        }
    }

    private static void capture(String name, JPanel f) throws Exception {
        Files.createDirectories(EVIDENCE);
        JDialog d = new JDialog((java.awt.Frame) null, NavText.RUN_TITLE, false);
        d.setContentPane(f);
        d.pack();
        d.setLocation(30, 30);
        d.setVisible(true);
        try {
            List<String> cut = new ArrayList<>();
            clipped(f, cut);
            assertEquals(List.of(), cut, name + ": nenhum componente cortado");
            for (double scale : new double[] {1.0, 1.5, 2.0}) {
                BufferedImage img = new BufferedImage((int) Math.ceil(f.getWidth() * scale), (int) Math.ceil(f.getHeight() * scale), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.scale(scale, scale);
                f.printAll(g);
                g.dispose();
                Path file = EVIDENCE.resolve(name + "-" + (int) (scale * 100) + ".png");
                ImageIO.write(img, "png", file.toFile());
                assertTrue(Files.size(file) > 2_000);
            }
        } finally {
            d.dispose();
        }
    }

    @Test
    void previasNosDoisTemasENasTresEscalas() throws Exception {
        for (ClientTheme theme : List.of(ClientTheme.SAKURA_DARK, ClientTheme.NEKKORA_LIGHT)) {
            String tema = theme == ClientTheme.SAKURA_DARK ? "sakura-dark" : "nekkora-light";
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Themes.apply(theme);
                    // pronta para iniciar: ambiente virtual localizado e escolhido, saida escolhida, aprovada
                    RunForm pronta = form(true);
                    pronta.showPythons(OPCOES, VIRTUAL.id(), Notice.Kind.PLAIN, NavText.PY_COMPATIBLE, List.of());
                    pronta.showOutput("D:\\Saidas\\conciliacao", null);
                    capture(tema + "-preparacao-pronta", pronta);

                    // varias opcoes: a pessoa escolhe; aprovacao pendente
                    RunForm escolher = form(false);
                    escolher.showPythons(List.of(VIRTUAL, option(Kind.INSTALLED, null, "C:\\Python\\Python312\\python.exe", "3.12.4", State.COMPATIBLE)), null,
                            Notice.Kind.PLAIN, NavText.PY_CHOOSE, List.of());
                    capture(tema + "-preparacao-escolher", escolher);

                    // ambiente escolhido que nao serve: erro junto do campo, com o motivo em uma frase
                    RunForm incompativel = form(true);
                    incompativel.showPythons(OPCOES.subList(1, 4), OPCOES.get(2).id(), Notice.Kind.ERROR, NavText.pythonSummary(OPCOES.get(2), REQ), List.of());
                    incompativel.showOutput("D:\\Saidas\\conciliacao", null);
                    capture(tema + "-preparacao-ambiente-incompativel", incompativel);

                    // pasta sem ambiente virtual, com um Python que serve escolhido: informacao, nao erro
                    RunForm informacao = form(true);
                    informacao.showPythons(List.of(option(Kind.INSTALLED, null, "C:\\Python\\Python312\\python.exe", "3.12.4", State.COMPATIBLE)),
                            "INSTALLED:C:\\Python\\Python312\\python.exe", Notice.Kind.INFO, NavText.PY_NOT_AN_ENVIRONMENT, List.of());
                    informacao.showOutput("D:\\Saidas\\conciliacao", null);
                    capture(tema + "-preparacao-pasta-sem-ambiente", informacao);

                    // detalhes tecnicos abertos
                    RunForm detalhes = form(true);
                    detalhes.showPythons(OPCOES, VIRTUAL.id(), Notice.Kind.PLAIN, NavText.PY_COMPATIBLE, List.of());
                    detalhes.showOutput("D:\\Saidas\\conciliacao", null);
                    detalhes.setDetailsOpen(true);
                    capture(tema + "-preparacao-detalhes", detalhes);

                    // ultima execucao: area propria
                    RunForm ultima = form(true);
                    ultima.showPythons(OPCOES, VIRTUAL.id(), Notice.Kind.PLAIN, NavText.PY_COMPATIBLE, List.of());
                    ultima.showOutput("D:\\Saidas\\conciliacao", null);
                    ultima.showStatus(new Runner.Status("01HZX", UUID.randomUUID(), UUID.randomUUID(), "sha256:" + "ab".repeat(32), "Conciliação bancária", false, true, true,
                            "Concluída com sucesso", "", 65_000, "D:\\Saidas\\conciliacao\\01HZX", false, "D:\\Nekkora\\dados\\execucoes\\01HZX\\pacote"));
                    capture(tema + "-preparacao-ultima-execucao", ultima);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
        SwingUtilities.invokeAndWait(() -> Themes.apply(ClientTheme.SAKURA_DARK));
    }
}
