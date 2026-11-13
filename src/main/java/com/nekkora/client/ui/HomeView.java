package com.nekkora.client.ui;

import javax.swing.JPanel;

/**
 * Tela inicial depois da entrada. Vazia de proposito nesta etapa: so o fundo do tema, dentro da estrutura da janela.
 * Nenhum painel, cartao, menu ou atalho de funcionalidade futura.
 */
public final class HomeView extends JPanel {

    private static final long serialVersionUID = 1L;

    public HomeView() {
        setOpaque(true);
        getAccessibleContext().setAccessibleName("Início");
        Themes.addListener(t -> setBackground(Themes.color(t.bg())));
        setBackground(Themes.color(Themes.current().bg()));
    }
}
