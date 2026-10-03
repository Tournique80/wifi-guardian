package io.github.tournique80.wifiguardian;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.io.File;
import java.io.FileWriter;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Escribe los eventos en archivos dentro de /sdcard/Android/data/io.github.tournique80.wifiguardian/files/ (legibles por ADB). */
final class Registro {
    static final String EVENTOS = "eventos.csv";
    static final String LOGCAT = "logcat_wifi.txt";
    private static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final SimpleDateFormat FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);

    private Registro() {}

    static File dir(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        d.mkdirs();
        return d;
    }

    static synchronized String ahora() {
        return FMT.format(new Date());
    }

    /** Agrega una línea a eventos.csv: fecha;evento;detalle;bateria%;temp°C;estado_termico */
    static synchronized void evento(Context c, String evento, String detalle) {
        String linea = ahora() + ";" + evento + ";" + (detalle == null ? "" : detalle.replace(';', ','))
                + ";" + bateria(c) + ";" + temperatura(c) + ";" + termico(c) + "\n";
        escribir(new File(dir(c), EVENTOS), linea);
    }

    static synchronized void escribir(File f, String texto) {
        try {
            if (f.length() > MAX_BYTES) {
                File viejo = new File(f.getPath() + ".1");
                viejo.delete();
                f.renameTo(viejo);
            }
            FileWriter w = new FileWriter(f, true);
            w.write(texto);
            w.close();
        } catch (Exception ignored) {
        }
    }

    static int bateria(Context c) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        return bm == null ? -1 : bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
    }

    static String temperatura(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return "";
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        return t < 0 ? "" : String.format(Locale.US, "%.1f", t / 10.0);
    }

    /** Temperatura de la batería en °C, o NaN si no se conoce. */
    static double temperaturaNum(Context c) {
        Intent i = c.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (i == null) return Double.NaN;
        int t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        return t < 0 ? Double.NaN : t / 10.0;
    }

    static boolean cargando(Context c) {
        BatteryManager bm = (BatteryManager) c.getSystemService(Context.BATTERY_SERVICE);
        return bm != null && bm.isCharging();
    }

    static String grados(double t) {
        return Double.isNaN(t) ? "?" : String.format(Locale.getDefault(), "%.1f °C", t);
    }

    static int termico(Context c) {
        PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
        return pm == null ? -1 : pm.getCurrentThermalStatus();
    }

    /** Últimas n líneas de eventos.csv, para mostrar en pantalla. */
    static List<String> ultimas(Context c, int n) {
        List<String> res = new ArrayList<>();
        File f = new File(dir(c), EVENTOS);
        if (!f.exists()) return res;
        try {
            RandomAccessFile r = new RandomAccessFile(f, "r");
            long len = r.length();
            long desde = Math.max(0, len - 16 * 1024);
            r.seek(desde);
            byte[] buf = new byte[(int) (len - desde)];
            r.readFully(buf);
            r.close();
            String[] lineas = new String(buf, "UTF-8").split("\n");
            for (int i = Math.max(0, lineas.length - n); i < lineas.length; i++) {
                if (!lineas[i].isEmpty()) res.add(lineas[i]);
            }
        } catch (Exception ignored) {
        }
        return res;
    }
}
