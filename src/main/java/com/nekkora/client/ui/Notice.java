package com.nekkora.client.ui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;

import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;

import com.nekkora.client.theme.ClientTheme;

/**
 * Uma mensagem do formulario com a GRAVIDADE dita em palavras, nao so na cor: "Informação", "Aviso", "Erro" ou
 * "Concluído" vem escrito antes do texto, com a cor do token correspondente da lib ({@code --nk-color-*-text}). Um texto
 * de apoio comum ({@link Kind#PLAIN}) nao tem rotulo. O texto e alinhado a esquerda e quebra por palavras.
 *
 * <p>So um erro e um impedimento: informacao e aviso nunca pintam o formulario de vermelho.
 */
final class Notice extends JPanel {

    private static final long serialVersionUID = 1L;

    enum Kind {
        /** Texto de apoio, sem rotulo. */
        PLAIN(""),
        INFO("Informação"),
        WARNING("Aviso"),
        ERROR("Erro"),
        SUCCESS("Concluído");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private final JLabel tag = new JLabel();
    private final WrappedText body;
    private final JLabel detail = new JLabel();
    private Kind kind = Kind.PLAIN;
    private String message = "";

    Notice(int width) {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        body = new WrappedText(width).alignLeft();
        tag.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.setAlignmentX(Component.LEFT_ALIGNMENT);
        detail.setVisible(false);
        add(tag);
        add(body);
        add(detail);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setVisible(false);
        Themes.addListener(t -> restyle());
        restyle();
    }

    /** Mostra a mensagem com a gravidade; texto vazio ou nulo esconde o componente. */
    void show(Kind kind, String message) {
        show(kind, message, null);
    }

    /**
     * {@code technical}: o detalhe tecnico (codigo de suporte, por exemplo), numa linha propria e discreta, fora da frase
     * que explica o que aconteceu.
     */
    void show(Kind kind, String message, String technical) {
        detail.setText(technical == null ? "" : technical);
        detail.setVisible(technical != null && !technical.isBlank() && message != null && !message.isBlank());
        this.kind = kind == null ? Kind.PLAIN : kind;
        this.message = message == null ? "" : message.strip();
        tag.setText(this.kind.label());
        tag.setVisible(this.kind != Kind.PLAIN);
        body.setText(this.message);
        setVisible(!this.message.isEmpty());
        // leitor de tela: a gravidade vem junto do texto
        getAccessibleContext().setAccessibleName(this.kind == Kind.PLAIN ? this.message : this.kind.label() + ": " + this.message);
        body.getAccessibleContext().setAccessibleDescription(this.kind.label());
        restyle();
        revalidate();
        repaint();
    }

    void clear() {
        show(Kind.PLAIN, "");
    }

    Kind kind() {
        return kind;
    }

    /** O texto na tela (vazio se escondido), sem o rotulo. */
    String text() {
        return isVisible() ? message : "";
    }

    /** O rotulo da gravidade na tela ("Erro", "Aviso"...), ou vazio. */
    String label() {
        return isVisible() && tag.isVisible() ? tag.getText() : "";
    }

    /** O detalhe tecnico na tela (vazio se nao ha). */
    String detail() {
        return isVisible() && detail.isVisible() ? detail.getText() : "";
    }

    boolean isError() {
        return isVisible() && kind == Kind.ERROR;
    }

    Color color() {
        return body.getForeground();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private void restyle() {
        ClientTheme t = Themes.current();
        Color c = Themes.color(switch (kind) {
            case ERROR -> t.dangerText();
            case WARNING -> t.warningText();
            case SUCCESS -> t.successText();
            case INFO -> t.infoText();
            default -> t.textSecondary();
        });
        detail.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, false));
        detail.setForeground(Themes.color(t.textMuted()));
        tag.setFont(Themes.font(ClientTheme.FONT_SIZE_XS, true));
        tag.setForeground(c);
        body.setFont(Themes.font(ClientTheme.FONT_SIZE_SM, false));
        // o corpo de informacao e de sucesso fica na cor de texto comum: o rotulo e que diz a gravidade
        body.setForeground(kind == Kind.ERROR || kind == Kind.WARNING ? c : Themes.color(kind == Kind.PLAIN ? t.textSecondary() : t.text()));
    }
}
