package io.github.tournique80.wifiguardian;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pantalla principal: estado, temperatura, ajustes y actividad reciente (estilo Material You). */
public class MainActivity extends Activity {

    // ---------------------------------------------------------------- paleta

    private boolean oscuro;
    private int cFondo, cTarjeta, cTexto, cTextoSec, cAcento, cBorde;

    private static final int VERDE = 0, AMBAR = 1, ROJO = 2, AZUL = 3, GRIS = 4;

    private int contenedor(int tono) {
        switch (tono) {
            case VERDE: return oscuro ? 0xFF1E4D2B : 0xFFC4EED0;
            case AMBAR: return oscuro ? 0xFF4A3B00 : 0xFFFFE08D;
            case ROJO:  return oscuro ? 0xFF5C1411 : 0xFFFFDAD6;
            case AZUL:  return getColor(oscuro ? android.R.color.system_accent1_800 : android.R.color.system_accent1_100);
            default:    return getColor(oscuro ? android.R.color.system_neutral2_800 : android.R.color.system_neutral2_100);
        }
    }

    private int sobreContenedor(int tono) {
        switch (tono) {
            case VERDE: return oscuro ? 0xFFC4EED0 : 0xFF0F5223;
            case AMBAR: return oscuro ? 0xFFFFE08D : 0xFF574500;
            case ROJO:  return oscuro ? 0xFFFFDAD6 : 0xFF8C1D18;
            case AZUL:  return getColor(oscuro ? android.R.color.system_accent1_100 : android.R.color.system_accent1_900);
            default:    return cTexto;
        }
    }

    private int punto(int tono) {
        switch (tono) {
            case VERDE: return 0xFF34A853;
            case AMBAR: return 0xFFF9AB00;
            case ROJO:  return 0xFFEA4335;
            case AZUL:  return cAcento;
            default:    return cTextoSec;
        }
    }

    // ---------------------------------------------------------------- vistas

    private LinearLayout heroe;
    private ImageView heroeIcono;
    private TextView heroeTitulo, heroeTexto;
    private TextView valTemp, valCaidas, valPaquetes, subTemp, subCaidas;
    private LinearLayout tarjTemp;
    private BarraTemperatura barra;
    private TextView consejoTemp;
    private Switch swActiva, swPantalla;
    private View tarjBateria;
    private LinearLayout listaEventos;
    private TextView pie;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresco = new Runnable() {
        @Override
        public void run() {
            refrescar();
            handler.postDelayed(this, 2000);
        }
    };

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().hide();

        oscuro = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        cFondo = getColor(oscuro ? android.R.color.system_neutral1_900 : android.R.color.system_neutral1_50);
        cTarjeta = getColor(oscuro ? android.R.color.system_neutral1_800 : android.R.color.system_neutral1_10);
        cTexto = getColor(oscuro ? android.R.color.system_neutral1_50 : android.R.color.system_neutral1_900);
        cTextoSec = getColor(oscuro ? android.R.color.system_neutral2_200 : android.R.color.system_neutral2_700);
        cAcento = getColor(oscuro ? android.R.color.system_accent1_200 : android.R.color.system_accent1_600);
        cBorde = getColor(oscuro ? android.R.color.system_neutral2_700 : android.R.color.system_neutral2_200);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);
        if (!oscuro && getWindow().getInsetsController() != null) {
            int claros = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            getWindow().getInsetsController().setSystemBarsAppearance(claros, claros);
        }

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(cFondo);
        scroll.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(col);
        final int lado = dp(20);
        scroll.setOnApplyWindowInsetsListener((v, ins) -> {
            android.graphics.Insets s = ins.getInsets(WindowInsets.Type.systemBars());
            col.setPadding(lado, s.top + dp(12), lado, s.bottom + dp(24));
            return ins;
        });

        // Encabezado
        LinearLayout cab = new LinearLayout(this);
        cab.setOrientation(LinearLayout.HORIZONTAL);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        TextView titulo = texto(getString(R.string.app_name), 30, cTexto, true);
        titulo.setPadding(dp(4), dp(8), 0, 0);
        cab.addView(titulo, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView acerca = texto(getString(R.string.about), 14, cAcento, true);
        acerca.setPadding(dp(12), dp(10), dp(4), dp(4));
        acerca.setOnClickListener(v -> mostrarAcerca());
        cab.addView(acerca);
        col.addView(cab);
        TextView subt = texto(getString(R.string.subtitle, android.os.Build.MODEL), 14, cTextoSec, false);
        subt.setPadding(dp(4), dp(2), 0, dp(18));
        col.addView(subt);

        // Tarjeta de estado
        heroe = new LinearLayout(this);
        heroe.setOrientation(LinearLayout.HORIZONTAL);
        heroe.setGravity(Gravity.CENTER_VERTICAL);
        heroe.setPadding(dp(20), dp(22), dp(20), dp(22));
        FrameLayout circulo = new FrameLayout(this);
        GradientDrawable fc = new GradientDrawable();
        fc.setShape(GradientDrawable.OVAL);
        fc.setColor(0x33FFFFFF);
        circulo.setBackground(fc);
        heroeIcono = new ImageView(this);
        heroeIcono.setImageResource(R.drawable.ic_stat);
        circulo.addView(heroeIcono, new FrameLayout.LayoutParams(dp(34), dp(34), Gravity.CENTER));
        heroe.addView(circulo, new LinearLayout.LayoutParams(dp(60), dp(60)));
        LinearLayout heroeTxt = new LinearLayout(this);
        heroeTxt.setOrientation(LinearLayout.VERTICAL);
        heroeTxt.setPadding(dp(16), 0, 0, 0);
        heroeTitulo = texto("", 22, cTexto, true);
        heroeTexto = texto("", 14, cTexto, false);
        heroeTexto.setPadding(0, dp(4), 0, 0);
        heroeTxt.addView(heroeTitulo);
        heroeTxt.addView(heroeTexto);
        heroe.addView(heroeTxt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        col.addView(heroe, margenAbajo(dp(12)));

        // Fila de indicadores
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        tarjTemp = indicador(getString(R.string.t_temp));
        valTemp = (TextView) tarjTemp.getChildAt(1);
        subTemp = (TextView) tarjTemp.getChildAt(2);
        LinearLayout tC = indicador(getString(R.string.t_drops));
        valCaidas = (TextView) tC.getChildAt(1);
        subCaidas = (TextView) tC.getChildAt(2);
        LinearLayout tP = indicador(getString(R.string.t_packets));
        valPaquetes = (TextView) tP.getChildAt(1);
        ((TextView) tP.getChildAt(2)).setText(getString(R.string.t_packets_sub));
        fila.addView(tarjTemp, pesoConMargen(dp(8)));
        fila.addView(tC, pesoConMargen(dp(8)));
        fila.addView(tP, pesoConMargen(0));
        col.addView(fila, margenAbajo(dp(12)));

        // Tarjeta de temperatura
        LinearLayout tTemp = tarjeta();
        tTemp.addView(texto(getString(R.string.temp_title), 16, cTexto, true));
        barra = new BarraTemperatura(this);
        LinearLayout.LayoutParams lpBarra = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lpBarra.topMargin = dp(10);
        tTemp.addView(barra, lpBarra);
        consejoTemp = texto("", 13, cTextoSec, false);
        consejoTemp.setPadding(0, dp(6), 0, 0);
        tTemp.addView(consejoTemp);
        TextView ajustar = texto(getString(R.string.temp_adjust), 14, cAcento, true);
        ajustar.setPadding(0, dp(10), 0, 0);
        ajustar.setOnClickListener(v -> editarUmbrales());
        tTemp.addView(ajustar);
        col.addView(tTemp, margenAbajo(dp(12)));

        // Ajustes
        LinearLayout tAj = tarjeta();
        tAj.addView(texto(getString(R.string.s_title), 16, cTexto, true));
        swActiva = interruptor(tAj, getString(R.string.s_active), getString(R.string.s_active_sub));
        swPantalla = interruptor(tAj, getString(R.string.s_screen), getString(R.string.s_screen_sub));
        col.addView(tAj, margenAbajo(dp(12)));

        // Permiso de batería (solo si falta)
        LinearLayout tBat = tarjeta();
        tBat.setBackground(fondoRedondeado(contenedor(AMBAR), dp(24)));
        TextView bt = texto(getString(R.string.bat_title), 15, sobreContenedor(AMBAR), true);
        TextView bd = texto(getString(R.string.bat_txt), 13, sobreContenedor(AMBAR), false);
        Button bb = new Button(this);
        bb.setText(getString(R.string.bat_btn));
        bb.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()))));
        tBat.addView(bt);
        tBat.addView(bd);
        tBat.addView(bb);
        tarjBateria = tBat;
        col.addView(tBat, margenAbajo(dp(12)));

        // Actividad reciente
        LinearLayout tEv = tarjeta();
        tEv.addView(texto(getString(R.string.a_title), 16, cTexto, true));
        listaEventos = new LinearLayout(this);
        listaEventos.setOrientation(LinearLayout.VERTICAL);
        listaEventos.setPadding(0, dp(6), 0, 0);
        tEv.addView(listaEventos);
        col.addView(tEv, margenAbajo(dp(12)));

        pie = texto("", 12, cTextoSec, false);
        pie.setGravity(Gravity.CENTER);
        pie.setPadding(0, dp(4), 0, 0);
        col.addView(pie);

        setContentView(scroll);

        swActiva.setChecked(GuardService.prefs(this).getBoolean(GuardService.PREF_ACTIVA, true));
        swPantalla.setChecked(GuardService.prefs(this).getBoolean(GuardService.PREF_PANTALLA_APAGADA, false));
        swActiva.setOnCheckedChangeListener((v, on) -> {
            if (on) GuardService.iniciar(this);
            else GuardService.detener(this);
            handler.postDelayed(this::refrescar, 300);
        });
        swPantalla.setOnCheckedChangeListener((v, on) -> {
            GuardService.prefs(this).edit().putBoolean(GuardService.PREF_PANTALLA_APAGADA, on).apply();
            Registro.evento(this, "AJUSTE", "pantalla_apagada=" + on);
        });

        if (checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
        if (swActiva.isChecked() && !GuardService.corriendo) GuardService.iniciar(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(refresco);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(refresco);
        super.onPause();
    }

    // ---------------------------------------------------------------- refresco

    private void refrescar() {
        boolean corriendo = GuardService.corriendo;
        boolean wifi = GuardService.wifiConectado;
        boolean esperando = GuardService.prefs(this).getBoolean(GuardService.PREF_ESPERANDO_ENFRIAR, false);
        boolean pantallaApagadaOk = GuardService.prefs(this).getBoolean(GuardService.PREF_PANTALLA_APAGADA, false);
        int caidas = GuardService.prefs(this).getInt(GuardService.PREF_CAIDAS, 0);
        String ultima = GuardService.prefs(this).getString(GuardService.PREF_ULTIMA_CAIDA, null);
        double t = Registro.temperaturaNum(this);
        double uReinicio = GuardService.umbralReinicio(this), uRiesgo = GuardService.umbralRiesgo(this);
        boolean caliente = !Double.isNaN(t) && t >= uRiesgo;

        // Estado principal
        int tono;
        String tit, txt;
        if (!corriendo) {
            tono = GRIS; tit = getString(R.string.h_stopped); txt = getString(R.string.h_stopped_txt);
        } else if (esperando) {
            tono = ROJO; tit = getString(R.string.h_down);
            txt = !Double.isNaN(t) && t <= uReinicio ? getString(R.string.h_down_cool)
                    : getString(R.string.h_down_hot, Registro.grados(uReinicio));
        } else if (!wifi) {
            tono = AZUL; tit = getString(R.string.h_no_wifi); txt = getString(R.string.h_no_wifi_txt);
        } else if (caliente) {
            tono = AMBAR; tit = getString(R.string.h_hot); txt = getString(R.string.h_hot_txt, Registro.grados(uRiesgo));
        } else {
            tono = VERDE; tit = getString(R.string.h_ok);
            txt = getString(R.string.h_ok_txt, GuardService.puerta);
            if (pantallaApagadaOk) txt = getString(R.string.h_ok_screen_off, txt);
        }
        heroe.setBackground(fondoRedondeado(contenedor(tono), dp(28)));
        heroeTitulo.setTextColor(sobreContenedor(tono));
        heroeTexto.setTextColor(sobreContenedor(tono));
        heroeIcono.setColorFilter(sobreContenedor(tono));
        heroeTitulo.setText(tit);
        heroeTexto.setText(txt);

        // Indicadores
        valTemp.setText(Double.isNaN(t) ? "—" : String.format(Locale.getDefault(), "%.1f°", t));
        int tonoT = Double.isNaN(t) ? GRIS : t >= uRiesgo ? ROJO : t > uReinicio ? AMBAR : VERDE;
        valTemp.setTextColor(tonoT == GRIS ? cTexto : punto(tonoT));
        subTemp.setText(tonoT == ROJO ? getString(R.string.z_risk) : tonoT == AMBAR ? getString(R.string.z_caution) : tonoT == VERDE ? getString(R.string.z_safe) : "");
        valCaidas.setText(String.valueOf(caidas));
        subCaidas.setText(ultima == null || ultima.length() < 16 ? getString(R.string.t_none)
                : ultima.substring(8, 10) + "/" + ultima.substring(5, 7) + " " + ultima.substring(11, 16));
        valPaquetes.setText(compacto(GuardService.enviados));

        barra.setUmbrales((float) uReinicio, (float) uRiesgo);
        barra.setTemperatura(t);
        consejoTemp.setText(getString(R.string.temp_hint)
                + (Registro.cargando(this) ? " " + getString(R.string.temp_charging) : ""));

        PowerManager pm = getSystemService(PowerManager.class);
        tarjBateria.setVisibility(pm.isIgnoringBatteryOptimizations(getPackageName()) ? View.GONE : View.VISIBLE);

        // Actividad
        listaEventos.removeAllViews();
        List<String> ult = Registro.ultimas(this, 200);
        Collections.reverse(ult);
        int mostrados = 0;
        for (String l : ult) {
            String[] p = l.split(";", -1);
            if (p.length < 2) continue;
            String[] ev = traducir(p[1], p.length > 2 ? p[2] : "");
            if (ev == null) continue;
            String hora = p[0].length() >= 16 ? p[0].substring(11, 16) : "";
            String dia = p[0].length() >= 10 ? p[0].substring(8, 10) + "/" + p[0].substring(5, 7) : "";
            listaEventos.addView(filaEvento(Integer.parseInt(ev[0]), ev[1], ev[2], hora, dia, mostrados > 0));
            if (++mostrados >= 25) break;
        }
        if (mostrados == 0) listaEventos.addView(texto(getString(R.string.a_empty), 14, cTextoSec, false));

        boolean detalle = checkSelfPermission("android.permission.READ_LOGS") == PackageManager.PERMISSION_GRANTED;
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        pie.setText(getString(detalle ? R.string.f_logs_on : R.string.f_logs_off) + "\n"
                + getString(R.string.f_version, version) + "\n🇨🇱 by Totihue");
    }

    /** {tono, título, detalle} en lenguaje simple, o null para ocultar el evento. */
    private String[] traducir(String ev, String det) {
        switch (ev) {
            case "CAIDA_CHIP_WIFI": {
                int m = det.startsWith("no_arranco") ? R.string.e_drop_boot
                        : det.startsWith("recuperacion") ? R.string.e_drop_recovery : R.string.e_drop_off;
                return new String[]{"" + ROJO, getString(R.string.e_drop), getString(m)};
            }
            case "WIFI_RECUPERADO":
            case "WIFI_SE_RECUPERO":    return new String[]{"" + VERDE, getString(R.string.e_recovered), ""};
            case "AVISO_YA_FRIO":       return new String[]{"" + AZUL, getString(R.string.e_ready), getString(R.string.e_ready_sub, det)};
            case "WIFI_CONECTADO":      return new String[]{"" + VERDE, getString(R.string.e_connected), ""};
            case "WIFI_DESCONECTADO":   return new String[]{"" + GRIS, getString(R.string.e_disconnected), getString(R.string.e_disconnected_sub)};
            case "WIFI_APAGADO_POR_USUARIO": return new String[]{"" + GRIS, getString(R.string.e_user_off), ""};
            case "SERVICIO_INICIADO":   return new String[]{"" + AZUL, getString(R.string.e_started), ""};
            case "SERVICIO_DETENIDO":   return new String[]{"" + GRIS, getString(R.string.e_stopped), ""};
            case "ARRANQUE_TELEFONO":
                return new String[]{"" + AZUL, getString(det.contains("BOOT") ? R.string.e_boot : R.string.e_updated), ""};
            case "TEMP": {
                double v = parsearGrados(det);
                double uRe = GuardService.umbralReinicio(this), uRi = GuardService.umbralRiesgo(this);
                int tono = Double.isNaN(v) ? GRIS : v >= uRi ? ROJO : v > uRe ? AMBAR : VERDE;
                return new String[]{"" + tono, getString(R.string.e_temp, det.replace(" cargando", "")),
                        det.contains("cargando") ? getString(R.string.e_charging) : ""};
            }
            case "AJUSTE":
                if (det.startsWith("umbrales")) {
                    return new String[]{"" + GRIS, getString(R.string.e_thresholds), det.substring(det.indexOf('=') + 1)};
                }
                return new String[]{"" + GRIS, getString(det.endsWith("true") ? R.string.e_screen_on : R.string.e_screen_off), ""};
            default:
                return null; // PANTALLA_*, ESTADO, WIFI_ESTADO, LOGCAT_ERROR: ruido para el usuario
        }
    }

    private static double parsearGrados(String s) {
        try {
            return Double.parseDouble(s.replace("°C", "").replace("cargando", "").replace(',', '.').trim());
        } catch (Exception e) {
            return Double.NaN;
        }
    }

    private static String compacto(long n) {
        Locale cl = Locale.getDefault();
        if (n >= 1_000_000) return String.format(cl, "%.1f M", n / 1e6);
        if (n >= 10_000) return String.format(cl, "%.0f mil", n / 1e3);
        if (n >= 1_000) return String.format(cl, "%.1f mil", n / 1e3);
        return String.valueOf(n);
    }

    // ---------------------------------------------------------------- diálogos

    private void mostrarAcerca() {
        TextView t = texto(getString(R.string.about_text), 15, cTexto, false);
        t.setPadding(dp(24), dp(12), dp(24), dp(8));
        t.setLineSpacing(0, 1.15f);
        ScrollView sv = new ScrollView(this);
        sv.addView(t);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.about_title)
                .setView(sv)
                .setPositiveButton(R.string.about_close, null)
                .setNeutralButton(R.string.about_github, (d, w) -> startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://github.com/Tournique80/wifi-guardian"))))
                .show();
    }

    private void editarUmbrales() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(24), dp(8), dp(24), 0);
        android.widget.NumberPicker pReinicio = selector(20, 40, (int) Math.round(GuardService.umbralReinicio(this)));
        android.widget.NumberPicker pRiesgo = selector(20, 45, (int) Math.round(GuardService.umbralRiesgo(this)));
        l.addView(texto(getString(R.string.th_restart), 14, cTextoSec, false));
        l.addView(pReinicio);
        l.addView(texto(getString(R.string.th_risk), 14, cTextoSec, false));
        l.addView(pRiesgo);
        new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.th_title)
                .setView(l)
                .setPositiveButton(R.string.th_save, (d, w) ->
                        guardarUmbrales(pReinicio.getValue(), Math.max(pRiesgo.getValue(), pReinicio.getValue() + 1)))
                .setNeutralButton(R.string.th_reset, (d, w) ->
                        guardarUmbrales(Math.round(GuardService.DEF_UMBRAL_REINICIO), Math.round(GuardService.DEF_UMBRAL_RIESGO)))
                .setNegativeButton(R.string.th_cancel, null)
                .show();
    }

    private android.widget.NumberPicker selector(int min, int max, int valor) {
        android.widget.NumberPicker p = new android.widget.NumberPicker(this);
        p.setMinValue(min);
        p.setMaxValue(max);
        p.setValue(Math.max(min, Math.min(max, valor)));
        p.setWrapSelectorWheel(false);
        return p;
    }

    private void guardarUmbrales(int reinicio, int riesgo) {
        GuardService.prefs(this).edit()
                .putFloat(GuardService.PREF_UMBRAL_REINICIO, reinicio)
                .putFloat(GuardService.PREF_UMBRAL_RIESGO, riesgo)
                .apply();
        Registro.evento(this, "AJUSTE", "umbrales=" + reinicio + "/" + riesgo + " °C");
        refrescar();
    }

    // ---------------------------------------------------------------- piezas de UI

    private TextView texto(String s, float sp, int color, boolean negrita) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(negrita ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable fondoRedondeado(int color, int radio) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radio);
        return g;
    }

    private LinearLayout tarjeta() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(20), dp(18), dp(20), dp(18));
        l.setBackground(fondoRedondeado(cTarjeta, dp(24)));
        return l;
    }

    private LinearLayout indicador(String etiqueta) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(dp(14), dp(14), dp(14), dp(14));
        l.setBackground(fondoRedondeado(cTarjeta, dp(20)));
        l.addView(texto(etiqueta, 12, cTextoSec, false));
        TextView v = texto("—", 24, cTexto, true);
        v.setPadding(0, dp(4), 0, dp(2));
        l.addView(v);
        TextView s = texto("", 11, cTextoSec, false);
        s.setSingleLine(true);
        l.addView(s);
        return l;
    }

    private Switch interruptor(LinearLayout padre, String titulo, String detalle) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(0, dp(14), 0, 0);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(texto(titulo, 15, cTexto, false));
        TextView d = texto(detalle, 13, cTextoSec, false);
        d.setPadding(0, dp(2), dp(8), 0);
        txt.addView(d);
        fila.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        fila.addView(sw);
        padre.addView(fila);
        return sw;
    }

    private View filaEvento(int tono, String titulo, String detalle, String hora, String dia, boolean separador) {
        LinearLayout fila = new LinearLayout(this);
        fila.setOrientation(LinearLayout.HORIZONTAL);
        fila.setGravity(Gravity.CENTER_VERTICAL);
        fila.setPadding(0, dp(10), 0, dp(10));
        View p = new View(this);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(punto(tono));
        p.setBackground(g);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(10), dp(10));
        lp.rightMargin = dp(14);
        fila.addView(p, lp);
        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.addView(texto(titulo, 14, cTexto, false));
        if (!detalle.isEmpty()) txt.addView(texto(detalle, 12, cTextoSec, false));
        fila.addView(txt, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout cuando = new LinearLayout(this);
        cuando.setOrientation(LinearLayout.VERTICAL);
        cuando.setGravity(Gravity.END);
        cuando.addView(texto(hora, 13, cTexto, false));
        cuando.addView(texto(dia, 11, cTextoSec, false));
        fila.addView(cuando);
        if (!separador) return fila;
        LinearLayout envoltura = new LinearLayout(this);
        envoltura.setOrientation(LinearLayout.VERTICAL);
        View div = new View(this);
        div.setBackgroundColor(cBorde);
        envoltura.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        envoltura.addView(fila);
        return envoltura;
    }

    private static LinearLayout.LayoutParams margenAbajo(int m) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = m;
        return lp;
    }

    private static LinearLayout.LayoutParams pesoConMargen(int derecha) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = derecha;
        return lp;
    }

    /** Barra horizontal 20–40 °C con zonas segura / precaución / riesgo y un marcador en la temperatura actual. */
    private final class BarraTemperatura extends View {
        private static final float MIN = 20f, MAX = 40f;
        private final Paint pintura = new Paint(Paint.ANTI_ALIAS_FLAG);
        private double temp = Double.NaN;
        private float uReinicio = GuardService.DEF_UMBRAL_REINICIO, uRiesgo = GuardService.DEF_UMBRAL_RIESGO;

        BarraTemperatura(Context c) {
            super(c);
        }

        void setUmbrales(float reinicio, float riesgo) {
            uReinicio = reinicio;
            uRiesgo = riesgo;
        }

        void setTemperatura(double t) {
            temp = t;
            invalidate();
        }

        private float x(float grados, float ancho) {
            float pad = dp(10);
            return pad + (Math.max(MIN, Math.min(MAX, grados)) - MIN) / (MAX - MIN) * (ancho - 2 * pad);
        }

        @Override
        protected void onDraw(Canvas c) {
            float w = getWidth();
            float yBarra = dp(18), alto = dp(10), r = alto / 2;
            float a = x(MIN, w), b = x(uReinicio, w), d = x(uRiesgo, w), e = x(MAX, w);
            pintura.setStyle(Paint.Style.FILL);
            pintura.setColor(punto(VERDE));
            c.drawRoundRect(new RectF(a, yBarra, b + r, yBarra + alto), r, r, pintura);
            pintura.setColor(punto(ROJO));
            c.drawRoundRect(new RectF(d - r, yBarra, e, yBarra + alto), r, r, pintura);
            pintura.setColor(punto(AMBAR));
            c.drawRect(b, yBarra, d, yBarra + alto, pintura);

            pintura.setColor(cTextoSec);
            pintura.setTextSize(dp(11));
            pintura.setTextAlign(Paint.Align.CENTER);
            float yTexto = yBarra + alto + dp(18);
            c.drawText("20°", a + dp(6), yTexto, pintura);
            c.drawText(Math.round(uReinicio) + "°", b, yTexto, pintura);
            c.drawText(Math.round(uRiesgo) + "°", d, yTexto, pintura);
            c.drawText("40°", e - dp(6), yTexto, pintura);

            if (!Double.isNaN(temp)) {
                float xm = x((float) temp, w);
                pintura.setColor(cTarjeta);
                c.drawCircle(xm, yBarra + r, dp(11), pintura);
                pintura.setColor(cTexto);
                c.drawCircle(xm, yBarra + r, dp(8), pintura);
                pintura.setColor(cTarjeta);
                c.drawCircle(xm, yBarra + r, dp(3.5f), pintura);
            }
        }
    }
}
