package com.nekkora.client.run;

import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

/**
 * SUPERVISAO dos processos de uma execucao gerenciada no Windows - a UNICA excecao de codigo nativo da camada de execucao
 * (decisao da titular de 16/11). Nao executa comando nenhum, nao abre shell e nao le nada de outros processos: usa so
 * estas funcoes do sistema, com argumentos fixos, sobre o processo que o proprio aplicativo acabou de lancar:
 *
 * <ul>
 * <li><b>Job Object</b> ({@code CreateJobObject}, {@code OpenJobObject}, {@code AssignProcessToJobObject},
 * {@code QueryInformationJobObject}, {@code TerminateJobObject}, {@code SetHandleInformation}, {@code OpenProcess},
 * {@code CloseHandle}): o processo lancado entra num grupo do proprio Windows, e TODO descendente dele nasce dentro do grupo -
 * inclusive o neto de um filho que ja terminou, que nenhuma arvore "pai-filho" mostra mais. O Windows diz quantos processos
 * do grupo estao vivos; nao ha identificador de processo a comparar, entao a reutilizacao de PID nao engana a contagem. Um
 * processo do grupo nao consegue sair dele (o grupo nao permite).</li>
 * <li><b>Marca da sessao do Windows</b> ({@code GlobalAddAtom}, {@code GlobalFindAtom}, {@code GlobalDeleteAtom}): um nome
 * gravado na tabela da sessao, que so deixa de existir quando a sessao acaba (saida do usuario ou reinicio do Windows) - e
 * quando ela acaba, nenhum processo dela continua vivo. Nao depende do relogio nem de PID.</li>
 * </ul>
 *
 * <p>O que sobrevive ao encerramento do aplicativo: enquanto o processo PRINCIPAL da execucao viver, o grupo continua
 * alcancavel pelo nome (ele herda o acesso no lancamento) e o aplicativo reaberto volta a supervisiona-lo com a mesma
 * exatidao. Se o processo principal terminar com o aplicativo fechado, o grupo continua existindo no Windows enquanto houver
 * processo nele, mas nao e mais alcancavel: ai a evidencia e INSUFICIENTE ate a sessao do Windows acabar.
 *
 * <p>Fora do Windows, ou se o sistema recusar qualquer chamada, nao ha supervisao: quem chama trata como "sem como saber".
 */
final class WindowsSupervision {

    private interface Api extends StdCallLibrary {
        Pointer CreateJobObjectW(Pointer attributes, WString name);

        Pointer OpenJobObjectW(int access, boolean inherit, WString name);

        boolean AssignProcessToJobObject(Pointer job, Pointer process);

        boolean QueryInformationJobObject(Pointer job, int infoClass, Pointer info, int length, IntByReference returned);

        boolean TerminateJobObject(Pointer job, int exitCode);

        boolean SetHandleInformation(Pointer handle, int mask, int flags);

        Pointer OpenProcess(int access, boolean inherit, int pid);

        boolean CloseHandle(Pointer handle);

        short GlobalAddAtomW(WString name);

        short GlobalFindAtomW(WString name);

        short GlobalDeleteAtom(short atom);
    }

    private static final int JOB_ASSIGN = 0x0001;
    private static final int JOB_QUERY = 0x0004;
    private static final int JOB_TERMINATE = 0x0008;
    private static final int PROCESS_TERMINATE = 0x0001;
    private static final int PROCESS_SET_QUOTA = 0x0100;
    private static final int HANDLE_FLAG_INHERIT = 0x0001;
    private static final int ACCOUNTING = 1;
    private static final int PID_LIST = 3;
    private static final int MAX_PIDS = 4096;

    private static volatile Api api;
    private static volatile boolean unavailable;

    private WindowsSupervision() {
    }

    private static Api api() {
        if (unavailable) return null;
        Api a = api;
        if (a != null) return a;
        try {
            if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) {
                unavailable = true;
                return null;
            }
            a = Native.load("kernel32", Api.class);
            api = a;
            return a;
        } catch (Throwable t) {
            unavailable = true;
            return null;
        }
    }

    /** O nome do grupo e da marca de uma execucao: fixo, derivado so do identificador dela. */
    static String name(ExecutionId id) {
        return "NekkoraClient-" + id.value();
    }

    /** Cria o grupo da execucao (vazio). Nulo: sem supervisao. */
    static Group create(String name) {
        Api a = api();
        if (a == null) return null;
        try {
            Pointer h = a.CreateJobObjectW(null, new WString(name));
            return h == null ? null : new Group(a, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Reabre, pelo nome, o grupo de uma execucao cujo processo principal continua vivo. Nulo: nao e mais alcancavel. */
    static Group open(String name) {
        Api a = api();
        if (a == null) return null;
        try {
            Pointer h = a.OpenJobObjectW(JOB_ASSIGN | JOB_QUERY | JOB_TERMINATE, false, new WString(name));
            return h == null ? null : new Group(a, h);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Grava a marca da execucao na sessao do Windows. Falso: nao gravou (a marca nao podera provar nada). */
    static boolean mark(String name) {
        Api a = api();
        try {
            return a != null && a.GlobalAddAtomW(new WString(name)) != 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /** A marca ainda existe nesta sessao do Windows? Nulo: nao ha como saber. */
    static Boolean marked(String name) {
        Api a = api();
        if (a == null) return null;
        try {
            return a.GlobalFindAtomW(new WString(name)) != 0;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Tira a marca (a execucao ja foi dada por encerrada com prova). */
    static void unmark(String name) {
        Api a = api();
        try {
            if (a != null) {
                short atom = a.GlobalFindAtomW(new WString(name));
                if (atom != 0) a.GlobalDeleteAtom(atom);
            }
        } catch (Throwable t) {
            // fica ate a sessao acabar
        }
    }

    /** O grupo de processos de uma execucao. */
    static final class Group {
        private final Api a;
        private volatile Pointer handle;

        private Group(Api a, Pointer handle) {
            this.a = a;
            this.handle = handle;
        }

        /**
         * O acesso ao grupo pode ser herdado pelo processo que sera lancado em seguida ({@code true}, so durante o lancamento)
         * ou nao ({@code false}, logo depois). E o que mantem o grupo alcancavel pelo nome enquanto o processo principal viver.
         */
        boolean inheritable(boolean on) {
            Pointer h = handle;
            try {
                return h != null && a.SetHandleInformation(h, HANDLE_FLAG_INHERIT, on ? HANDLE_FLAG_INHERIT : 0);
            } catch (Throwable t) {
                return false;
            }
        }

        /** Poe o processo (lancado pelo aplicativo, ou descendente dele) no grupo. */
        boolean assign(long pid) {
            Pointer h = handle;
            if (h == null || pid <= 0 || pid > Integer.MAX_VALUE) return false;
            Pointer p = null;
            try {
                p = a.OpenProcess(PROCESS_SET_QUOTA | PROCESS_TERMINATE, false, (int) pid);
                return p != null && a.AssignProcessToJobObject(h, p);
            } catch (Throwable t) {
                return false;
            } finally {
                if (p != null) a.CloseHandle(p);
            }
        }

        /** Quantos processos do grupo estao vivos agora. -1: o sistema nao respondeu. */
        int active() {
            Pointer h = handle;
            if (h == null) return -1;
            try {
                Memory m = new Memory(48);
                return a.QueryInformationJobObject(h, ACCOUNTING, m, 48, null) ? m.getInt(40) : -1;
            } catch (Throwable t) {
                return -1;
            }
        }

        /** Os PIDs vivos do grupo agora (para anotacao; a contagem de {@link #active} e a que decide). Nulo: sem resposta. */
        List<Long> pids() {
            Pointer h = handle;
            if (h == null) return null;
            try {
                int size = 8 + 8 * MAX_PIDS;
                Memory m = new Memory(size);
                if (!a.QueryInformationJobObject(h, PID_LIST, m, size, null)) return null;
                int n = Math.min(m.getInt(4), MAX_PIDS);
                List<Long> out = new ArrayList<>(n);
                for (int i = 0; i < n; i++) out.add(m.getLong(8 + 8L * i));
                return out;
            } catch (Throwable t) {
                return null;
            }
        }

        /** Encerra TODOS os processos do grupo. */
        boolean terminate() {
            Pointer h = handle;
            try {
                return h != null && a.TerminateJobObject(h, 1);
            } catch (Throwable t) {
                return false;
            }
        }

        void close() {
            Pointer h = handle;
            handle = null;
            try {
                if (h != null) a.CloseHandle(h);
            } catch (Throwable t) {
                // nada a fazer
            }
        }
    }
}
