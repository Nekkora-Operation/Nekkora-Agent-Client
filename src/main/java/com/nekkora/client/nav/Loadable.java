package com.nekkora.client.nav;

import com.nekkora.client.nav.Directory.Failure;

/**
 * Resultado de uma consulta, como a tela o mostra: ainda nao pedida, carregando, pronta ou falhou. "Pronta com lista
 * vazia" e "falhou" sao estados diferentes de proposito.
 */
public record Loadable<T>(Status status, T value, Failure failure) {

    public enum Status { IDLE, LOADING, READY, FAILED }

    public static <T> Loadable<T> idle() {
        return new Loadable<>(Status.IDLE, null, null);
    }

    public static <T> Loadable<T> loading() {
        return new Loadable<>(Status.LOADING, null, null);
    }

    public static <T> Loadable<T> ready(T value) {
        return new Loadable<>(Status.READY, value, null);
    }

    public static <T> Loadable<T> failed(Failure failure) {
        return new Loadable<>(Status.FAILED, null, failure);
    }

    public boolean isReady() {
        return status == Status.READY;
    }
}
