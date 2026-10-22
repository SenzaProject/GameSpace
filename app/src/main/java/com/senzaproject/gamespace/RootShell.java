package com.senzaproject.gamespace;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;

/** Shell root persisten (satu proses su) supaya pembacaan FPS cepat. */
final class RootShell {
    /** 0 = belum dicek, 1 = root aktif, 2 = tidak ada root / ditolak */
    static volatile int state = 0;

    private static final String END = "__GS_END__";
    private static Process proc;
    private static OutputStream out;
    private static BufferedReader in;

    private RootShell() {}

    private static boolean ensure() {
        try {
            if (proc != null) {
                try {
                    proc.exitValue();
                    close();
                } catch (IllegalThreadStateException stillAlive) {
                    return true;
                }
            }
            proc = new ProcessBuilder("su").redirectErrorStream(true).start();
            out = proc.getOutputStream();
            in = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    private static void close() {
        try {
            if (proc != null) proc.destroy();
        } catch (Exception ignored) {
        }
        proc = null;
        out = null;
        in = null;
    }

    /** Jalankan perintah sebagai root. Return output, atau null jika root gagal. */
    static synchronized String run(String cmd) {
        if (!ensure()) return null;
        try {
            out.write((cmd + "\necho; echo " + END + "\n").getBytes("UTF-8"));
            out.flush();
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                if (line.equals(END)) return sb.toString();
                sb.append(line).append('\n');
            }
            close();
            return null;
        } catch (IOException e) {
            close();
            return null;
        }
    }

    static boolean isRoot() {
        String r = run("id");
        boolean ok = r != null && r.contains("uid=0");
        state = ok ? 1 : 2;
        return ok;
    }
}
