package io.github.tournique80.wifiguardian;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.RouteInfo;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.InputStreamReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Servicio en primer plano que:
 *  1) envía un paquete UDP minúsculo por el Wi-Fi cada 200 ms, para que el driver (bcmdhd, idletime 500 ms)
 *     no ponga el enlace PCIe en reposo y no tenga que re-entrenarlo (ahí ocurre PCIE_RC_LINK_UP_FAIL);
 *  2) registra caídas del Wi-Fi, estado de batería/temperatura y los mensajes del sistema sobre el Wi-Fi.
 */
public class GuardService extends Service {
    static final String PREFS = "guardian";
    static final String PREF_ACTIVA = "activa";
    static final String PREF_PANTALLA_APAGADA = "pantalla_apagada";
    static final String PREF_CAIDAS = "caidas";
    static final String PREF_ULTIMA_CAIDA = "ultima_caida";
    static final String PREF_ESPERANDO_ENFRIAR = "esperando_enfriar";
    static final String PREF_AVISO_FRIO = "aviso_frio";

    static final String PREF_UMBRAL_REINICIO = "umbral_reinicio";
    static final String PREF_UMBRAL_RIESGO = "umbral_riesgo";

    /**
     * Valores por defecto medidos en un Pixel 8 Pro (03-10-2026): el chip falló al encender con la batería
     * a 30,8 / 31,8 / 34,1 °C y funcionó a 25,1 / 28,0 °C. El usuario puede ajustarlos.
     */
    static final float DEF_UMBRAL_REINICIO = 28f;
    static final float DEF_UMBRAL_RIESGO = 30f;

    static double umbralReinicio(Context c) {
        return prefs(c).getFloat(PREF_UMBRAL_REINICIO, DEF_UMBRAL_REINICIO);
    }

    static double umbralRiesgo(Context c) {
        return prefs(c).getFloat(PREF_UMBRAL_RIESGO, DEF_UMBRAL_RIESGO);
    }

    private static final String CANAL_ESTADO = "estado_v2";
    private static final String CANAL_ALERTA = "alerta";
    private static final int NOTIF_ESTADO = 1;
    private static final int NOTIF_ALERTA = 2;
    private static final int NOTIF_FRIO = 3;
    private static final long INTERVALO_MS = 200;
    private static final Pattern FILTRO_LOGCAT = Pattern.compile(
            "(?i)(wifi|wlan|dhd|supplicant|bthal|bluetooth_hal|coex|SelfRecovery|ActiveModeWarden|HalDevMgr|wificond|pcie)");
    private static final Pattern RUIDO_LOGCAT = Pattern.compile(
            "(netstats_wifi_sample|Wildlife_|NearbySharing|SST-|io.github.tournique80.wifiguardian)");
    private static final Pattern REC_RECUPERACION = Pattern.compile(
            "time=(\\d\\d-\\d\\d \\d\\d:\\d\\d:\\d\\d)\\.\\d+ .*what=(CMD_RECOVERY_RESTART_WIFI|CMD_RECOVERY_DISABLE_WIFI)");

    // Estado visible para la pantalla principal
    static volatile boolean corriendo;
    static volatile boolean wifiConectado;
    static volatile long enviados;
    static volatile long errores;
    static volatile String puerta = "";

    private ScheduledExecutorService exec;
    private ConnectivityManager cm;
    private PowerManager.WakeLock wakeLock;
    private volatile Network redWifi;
    private volatile InetAddress destino;
    private volatile DatagramSocket socket;
    private volatile boolean pantallaEncendida = true;
    private Process logcat;
    private long ultimoEstadoMs;
    private double ultimaTempRegistrada = Double.NaN;
    private int chequeosEncendido;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void iniciar(Context c) {
        prefs(c).edit().putBoolean(PREF_ACTIVA, true).apply();
        c.startForegroundService(new Intent(c, GuardService.class));
    }

    static void detener(Context c) {
        prefs(c).edit().putBoolean(PREF_ACTIVA, false).apply();
        c.stopService(new Intent(c, GuardService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        crearCanales();
        startForeground(NOTIF_ESTADO, notificacionEstado(getString(R.string.st_starting)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        corriendo = true;
        cm = getSystemService(ConnectivityManager.class);
        PowerManager pm = getSystemService(PowerManager.class);
        pantallaEncendida = pm.isInteractive();
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "wifiguardian:keepalive");
        wakeLock.setReferenceCounted(false);

        Registro.evento(this, "SERVICIO_INICIADO", "pantalla_apagada=" + prefs(this).getBoolean(PREF_PANTALLA_APAGADA, false)
                + " read_logs=" + tienePermiso("android.permission.READ_LOGS")
                + " dump=" + tienePermiso("android.permission.DUMP"));

        NetworkRequest req = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();
        cm.registerNetworkCallback(req, redCallback);

        IntentFilter f = new IntentFilter();
        f.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(receptor, f, Context.RECEIVER_NOT_EXPORTED);

        exec = Executors.newScheduledThreadPool(3);
        exec.scheduleAtFixedRate(this::latido, 0, INTERVALO_MS, TimeUnit.MILLISECONDS);
        exec.scheduleAtFixedRate(this::revisionPeriodica, 30, 30, TimeUnit.SECONDS);
        exec.schedule(this::revisarArranque, 90, TimeUnit.SECONDS);
        if (tienePermiso("android.permission.READ_LOGS")) {
            exec.execute(this::capturarLogcat);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        corriendo = false;
        Registro.evento(this, "SERVICIO_DETENIDO", "enviados=" + enviados + " errores=" + errores);
        try { cm.unregisterNetworkCallback(redCallback); } catch (Exception ignored) {}
        try { unregisterReceiver(receptor); } catch (Exception ignored) {}
        if (exec != null) exec.shutdownNow();
        if (logcat != null) logcat.destroy();
        cerrarSocket();
        if (wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------------------------------------------------------------- red

    private final ConnectivityManager.NetworkCallback redCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            redWifi = network;
            wifiConectado = true;
            cerrarSocket();
            Registro.evento(GuardService.this, "WIFI_CONECTADO", "");
            actualizarWakeLock();
        }

        @Override
        public void onLinkPropertiesChanged(Network network, LinkProperties lp) {
            InetAddress gw = null;
            for (RouteInfo r : lp.getRoutes()) {
                if (r.isDefaultRoute() && r.hasGateway() && r.getGateway() instanceof Inet4Address) {
                    gw = r.getGateway();
                    break;
                }
            }
            try {
                destino = gw != null ? gw : InetAddress.getByName("8.8.8.8");
            } catch (Exception ignored) {
            }
            puerta = destino == null ? "" : destino.getHostAddress();
        }

        @Override
        public void onLost(Network network) {
            if (network.equals(redWifi)) {
                redWifi = null;
                wifiConectado = false;
                cerrarSocket();
                Registro.evento(GuardService.this, "WIFI_DESCONECTADO", "enviados=" + enviados + " errores=" + errores);
                actualizarWakeLock();
            }
        }
    };

    /** Se ejecuta cada 200 ms: manda 1 byte por UDP al router (puerto 9, "discard"), solo por la red Wi-Fi. */
    private void latido() {
        Network red = redWifi;
        InetAddress dst = destino;
        if (red == null || dst == null) return;
        if (!pantallaEncendida && !prefs(this).getBoolean(PREF_PANTALLA_APAGADA, false)) return;
        try {
            DatagramSocket s = socket;
            if (s == null) {
                s = new DatagramSocket();
                red.bindSocket(s);
                socket = s;
            }
            s.send(new DatagramPacket(new byte[]{0}, 1, dst, 9));
            enviados++;
        } catch (Exception e) {
            errores++;
            cerrarSocket();
        }
    }

    private void cerrarSocket() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) s.close();
    }

    private void actualizarWakeLock() {
        boolean querer = redWifi != null && prefs(this).getBoolean(PREF_PANTALLA_APAGADA, false);
        if (querer && !wakeLock.isHeld()) wakeLock.acquire();
        if (!querer && wakeLock.isHeld()) wakeLock.release();
    }

    // ---------------------------------------------------------------- eventos del sistema

    private final BroadcastReceiver receptor = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            String a = i.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(a)) {
                pantallaEncendida = true;
                Registro.evento(c, "PANTALLA_ENCENDIDA", "");
            } else if (Intent.ACTION_SCREEN_OFF.equals(a)) {
                pantallaEncendida = false;
                Registro.evento(c, "PANTALLA_APAGADA", "");
            } else if (WifiManager.WIFI_STATE_CHANGED_ACTION.equals(a)) {
                int st = i.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1);
                int prev = i.getIntExtra(WifiManager.EXTRA_PREVIOUS_WIFI_STATE, -1);
                Registro.evento(c, "WIFI_ESTADO", nombreEstado(prev) + "->" + nombreEstado(st));
                if (st == WifiManager.WIFI_STATE_DISABLED || st == WifiManager.WIFI_STATE_UNKNOWN) {
                    exec.schedule(GuardService.this::analizarApagado, 25, TimeUnit.SECONDS);
                }
            }
        }
    };

    private static String nombreEstado(int s) {
        switch (s) {
            case WifiManager.WIFI_STATE_DISABLED: return "APAGADO";
            case WifiManager.WIFI_STATE_DISABLING: return "APAGANDO";
            case WifiManager.WIFI_STATE_ENABLED: return "ENCENDIDO";
            case WifiManager.WIFI_STATE_ENABLING: return "ENCENDIENDO";
            default: return "DESCONOCIDO";
        }
    }

    /** Tras un apagado del Wi-Fi: ¿fue el usuario o una caída del chip (recuperación fallida)? */
    private void analizarApagado() {
        WifiManager wm = getSystemService(WifiManager.class);
        if (wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED) {
            Registro.evento(this, "WIFI_SE_RECUPERO", "");
            return;
        }
        String dump = tienePermiso("android.permission.DUMP") ? ejecutar("dumpsys", "wifi") : "";
        boolean recuperacionReciente = false;
        Matcher m = REC_RECUPERACION.matcher(dump);
        long ahora = System.currentTimeMillis();
        while (m.find()) {
            long t = parsearHora(m.group(1));
            if (t > 0 && ahora - t < 5 * 60 * 1000) recuperacionReciente = true;
        }
        if (!dump.isEmpty()) guardarDump(dump);
        if (recuperacionReciente || ajusteWifiActivado()) {
            registrarCaida(recuperacionReciente ? "recuperacion_fallida" : "wifi_activado_pero_apagado");
        } else {
            Registro.evento(this, "WIFI_APAGADO_POR_USUARIO", "");
        }
    }

    /** 90 s después de arrancar: si el ajuste dice Wi-Fi activado pero no encendió, el chip no respondió al arrancar. */
    private void revisarArranque() {
        WifiManager wm = getSystemService(WifiManager.class);
        if (ajusteWifiActivado() && wm.getWifiState() != WifiManager.WIFI_STATE_ENABLED) {
            if (tienePermiso("android.permission.DUMP")) guardarDump(ejecutar("dumpsys", "wifi"));
            registrarCaida("no_arranco_al_encender");
        }
    }

    private boolean ajusteWifiActivado() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(), "wifi_on", 0) != 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void registrarCaida(String motivo) {
        SharedPreferences p = prefs(this);
        int n = p.getInt(PREF_CAIDAS, 0) + 1;
        double t = Registro.temperaturaNum(this);
        p.edit().putInt(PREF_CAIDAS, n).putString(PREF_ULTIMA_CAIDA, Registro.ahora())
                .putBoolean(PREF_ESPERANDO_ENFRIAR, true).putBoolean(PREF_AVISO_FRIO, false).apply();
        Registro.evento(this, "CAIDA_CHIP_WIFI", motivo + " total=" + n + " enviados=" + enviados + " errores=" + errores);

        String consejo;
        if (!Double.isNaN(t) && t <= umbralReinicio(this)) {
            consejo = getString(R.string.alert_cool, Registro.grados(t));
        } else {
            consejo = getString(R.string.alert_hot, Registro.grados(t),
                    Registro.cargando(this) ? getString(R.string.alert_unplug) : "",
                    Registro.grados(umbralReinicio(this)));
        }
        Notification n2 = new Notification.Builder(this, CANAL_ALERTA)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.alert_title))
                .setContentText(consejo)
                .setStyle(new Notification.BigTextStyle().bigText(consejo))
                .setContentIntent(abrirApp())
                .setAutoCancel(true)
                .build();
        getSystemService(NotificationManager.class).notify(NOTIF_ALERTA, n2);
    }

    /** Mientras se espera que se enfríe: avisa cuando ya conviene reiniciar, o limpia si el Wi-Fi volvió solo. */
    private void vigilarEnfriamiento() {
        SharedPreferences p = prefs(this);
        if (!p.getBoolean(PREF_ESPERANDO_ENFRIAR, false)) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        WifiManager wm = getSystemService(WifiManager.class);
        // Recuperado: conectado a una red, o encendido de forma estable (2 revisiones seguidas, ~1 min) si no hay red cerca.
        chequeosEncendido = wm.getWifiState() == WifiManager.WIFI_STATE_ENABLED ? chequeosEncendido + 1 : 0;
        if (wifiConectado || chequeosEncendido >= 2) {
            chequeosEncendido = 0;
            p.edit().putBoolean(PREF_ESPERANDO_ENFRIAR, false).putBoolean(PREF_AVISO_FRIO, false).apply();
            nm.cancel(NOTIF_ALERTA);
            nm.cancel(NOTIF_FRIO);
            Registro.evento(this, "WIFI_RECUPERADO", "");
            return;
        }
        double t = Registro.temperaturaNum(this);
        if (!p.getBoolean(PREF_AVISO_FRIO, false) && !Double.isNaN(t) && t <= umbralReinicio(this)) {
            p.edit().putBoolean(PREF_AVISO_FRIO, true).apply();
            Registro.evento(this, "AVISO_YA_FRIO", Registro.grados(t));
            nm.cancel(NOTIF_ALERTA);
            String txt = getString(R.string.ready_text, Registro.grados(t));
            nm.notify(NOTIF_FRIO, new Notification.Builder(this, CANAL_ALERTA)
                    .setSmallIcon(R.drawable.ic_stat)
                    .setContentTitle(getString(R.string.ready_title))
                    .setContentText(txt)
                    .setStyle(new Notification.BigTextStyle().bigText(txt))
                    .setContentIntent(abrirApp())
                    .setAutoCancel(true)
                    .build());
        }
    }

    private void revisionPeriodica() {
        actualizarWakeLock();
        vigilarEnfriamiento();
        getSystemService(NotificationManager.class).notify(NOTIF_ESTADO, notificacionEstado(textoEstado()));
        double t = Registro.temperaturaNum(this);
        if (!Double.isNaN(t) && (Double.isNaN(ultimaTempRegistrada) || Math.abs(t - ultimaTempRegistrada) >= 1.0)) {
            ultimaTempRegistrada = t;
            Registro.evento(this, "TEMP", Registro.grados(t) + (Registro.cargando(this) ? " cargando" : ""));
        }
        long ahora = System.currentTimeMillis();
        if (ahora - ultimoEstadoMs > 15 * 60 * 1000) {
            ultimoEstadoMs = ahora;
            Registro.evento(this, "ESTADO", "wifi=" + (wifiConectado ? "conectado" : "sin_wifi")
                    + " pantalla=" + (pantallaEncendida ? "on" : "off")
                    + " enviados=" + enviados + " errores=" + errores + " destino=" + puerta);
        }
    }

    String textoEstado() {
        int caidas = prefs(this).getInt(PREF_CAIDAS, 0);
        double t = Registro.temperaturaNum(this);
        String base;
        if (prefs(this).getBoolean(PREF_ESPERANDO_ENFRIAR, false)) base = getString(R.string.st_waiting_cool);
        else if (!wifiConectado) base = getString(R.string.st_no_wifi);
        else if (!pantallaEncendida && !prefs(this).getBoolean(PREF_PANTALLA_APAGADA, false)) base = getString(R.string.st_paused);
        else base = getString(R.string.st_protecting);
        String temp = Registro.grados(t) + (!Double.isNaN(t) && t >= umbralRiesgo(this) ? " ⚠" : "");
        return getString(R.string.st_format, base, temp, caidas);
    }

    // ---------------------------------------------------------------- logcat y dumpsys

    private void capturarLogcat() {
        File f = new File(Registro.dir(this), Registro.LOGCAT);
        try {
            logcat = Runtime.getRuntime().exec(new String[]{"logcat", "-v", "threadtime", "-T", "1"});
            BufferedReader r = new BufferedReader(new InputStreamReader(logcat.getInputStream()));
            StringBuilder lote = new StringBuilder();
            long ultimoVolcado = System.currentTimeMillis();
            String linea;
            while (corriendo && (linea = r.readLine()) != null) {
                if (FILTRO_LOGCAT.matcher(linea).find() && !RUIDO_LOGCAT.matcher(linea).find()) {
                    lote.append(linea).append('\n');
                }
                if (lote.length() > 8192 || System.currentTimeMillis() - ultimoVolcado > 5000) {
                    if (lote.length() > 0) Registro.escribir(f, lote.toString());
                    lote.setLength(0);
                    ultimoVolcado = System.currentTimeMillis();
                }
            }
        } catch (Exception e) {
            Registro.evento(this, "LOGCAT_ERROR", e.toString());
        }
    }

    private String ejecutar(String... cmd) {
        StringBuilder sb = new StringBuilder();
        try {
            Process p = Runtime.getRuntime().exec(cmd);
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String l;
            while ((l = r.readLine()) != null) sb.append(l).append('\n');
            p.waitFor(20, TimeUnit.SECONDS);
        } catch (Exception e) {
            sb.append("ERROR ").append(e);
        }
        return sb.toString();
    }

    private void guardarDump(String dump) {
        File dir = Registro.dir(this);
        String nombre = "dumpsys_wifi_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        try {
            FileWriter w = new FileWriter(new File(dir, nombre));
            w.write(dump);
            w.close();
        } catch (Exception ignored) {
        }
        // conservar solo los 10 más recientes
        File[] dumps = dir.listFiles((d, n) -> n.startsWith("dumpsys_wifi_"));
        if (dumps != null && dumps.length > 10) {
            Arrays.sort(dumps);
            for (int i = 0; i < dumps.length - 10; i++) dumps[i].delete();
        }
    }

    private static long parsearHora(String mmddhms) {
        try {
            Calendar c = Calendar.getInstance();
            Date d = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).parse(mmddhms);
            Calendar t = Calendar.getInstance();
            t.setTime(d);
            t.set(Calendar.YEAR, c.get(Calendar.YEAR));
            return t.getTimeInMillis();
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean tienePermiso(String p) {
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    // ---------------------------------------------------------------- notificaciones

    private void crearCanales() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel estado = new NotificationChannel(CANAL_ESTADO, getString(R.string.ch_status), NotificationManager.IMPORTANCE_DEFAULT);
        estado.setSound(null, null);
        estado.enableVibration(false);
        estado.setShowBadge(false);
        nm.createNotificationChannel(estado);
        nm.createNotificationChannel(new NotificationChannel(CANAL_ALERTA, getString(R.string.ch_alert), NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification notificacionEstado(String texto) {
        return new Notification.Builder(this, CANAL_ESTADO)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(texto)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(abrirApp())
                .build();
    }

    private PendingIntent abrirApp() {
        return PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
