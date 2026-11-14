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

import javax.imageio.ImageIO;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.nekkora.client.run.PythonEnvironments.Kind;
import com.nekkora.client.run.PythonEnvironments.State;
import com.nekkora.client.run.Runner;
import com.nekkora.client.theme.ClientTheme;

/**
 * Previas da secao "Ambiente Python" nos dois temas e em 100%, 150% e 200% (AGENT-CLIENT-PYTHON-001 T02), com dados
 * FICTICIOS (nenhum caminho desta maquina). E o formulario real, pintado ampliado; nao substitui o teste no executavel
 * empacotado, que mede a janela na escala de verdade. As imagens vao para {@code target/evidencias}.
 */
@Tag("desktop")
class PythonEnvironmentPreviewTest {

    private static final Path EVIDENCE = Path.of("target", "evidencias");

    private static Runner.PythonOption option(Kind kind, String folder, String path, String version, State state, String... pending) {
        return new Runner.PythonOption(kind + ":" + path, kind, folder, path, version, state, List.of(pending));
    }

    private static final Runner.PythonOption PROJETO = option(Kind.PROJECT, ".venv", "D:\\Projetos\\conciliacao\\.venv\\Scripts\\python.exe", "3.12.4", State.COMPATIBLE);
    private static final List<Runner.PythonOption> OPCOES = List.of(PROJETO,
            option(Kind.INSTALLED, null, "C:\\Python\\Python312\\python.exe", "3.12.4", State.DEPENDENCIES_PENDING, "requests==2.31.0 (não instalado)"),
            option(Kind.INSTALLED, null, "C:\\Python\\Python311\\python.exe", "3.11.9", State.DEPENDENCIES_PENDING, "requests==2.31.0 (instalada a versão 2.28.1)"),
            option(Kind.INSTALLED, null, "C:\\Python\\Python315\\python.exe", "3.15.0", State.VERSION_INCOMPATIBLE));

    private static RunForm form() {
        RunForm f = new RunForm(new RunForm.PythonEvents() {
            @Override
            public void chosen() {
            }

            @Override
            public void selectFolder() {
            }

            @Override
            public void searchAgain() {
            }
        }, () -> { }, () -> { }, () -> { }, () -> { }, () -> { });
        f.showPackage(new Runner.Checked("sha256:" + "ab".repeat(32), "abababab", "Conciliação bancária", "1.4.0", "main.py", ">=3.10,<3.14", List.of("requests==2.31.0"), false), null);
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

    private static void capture(String name, RunForm f) throws Exception {
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
                    RunForm escolhido = form();
                    escolhido.showPythons(OPCOES, PROJETO.id(), NavText.pythonDetail(PROJETO, ">=3.10,<3.14"), false);
                    capture(tema + "-ambiente-python-projeto-escolhido", escolhido);

                    RunForm escolher = form();
                    escolher.showPythons(List.of(OPCOES.get(0), option(Kind.INSTALLED, null, "C:\\Python\\Python312\\python.exe", "3.12.4", State.COMPATIBLE)), null, NavText.PY_CHOOSE, false);
                    capture(tema + "-ambiente-python-escolha", escolher);

                    RunForm pendente = form();
                    pendente.showPythons(OPCOES.subList(1, 4), OPCOES.get(2).id(), NavText.pythonDetail(OPCOES.get(2), ">=3.10,<3.14"), true);
                    capture(tema + "-ambiente-python-dependencias", pendente);

                    RunForm nenhum = form();
                    nenhum.showPythons(OPCOES.subList(1, 4), null, NavText.pythonNoneCompatible(">=3.10,<3.14", List.of("requests==2.31.0")), true);
                    capture(tema + "-ambiente-python-nenhum-compativel", nenhum);

                    RunForm buscando = form();
                    buscando.showPythonSearching(NavText.PY_SEARCHING);
                    capture(tema + "-ambiente-python-buscando", buscando);
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        }
    }
}
