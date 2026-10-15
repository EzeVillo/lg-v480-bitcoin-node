package ar.eze.v480wifiautoconnect;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Base64;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Panel de una sola vista: no carga Chromium ni un motor de JavaScript. */
public final class NativePanelActivity extends Activity {
    private static final String API = "http://localhost:8080/cgi-bin/api";
    private static final Locale DISPLAY_LOCALE = new Locale("es", "AR");
    private final Handler handler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
        @Override public Thread newThread(Runnable task) { return new Thread(task, "node-panel"); }
    });
    private final NumberFormat integerFormat = NumberFormat.getIntegerInstance(DISPLAY_LOCALE);
    private final NumberFormat[] decimalFormats = makeDecimalFormats();
    private volatile HttpURLConnection connection;
    private volatile boolean active, destroyed;
    private boolean inFlight, detailInFlight;
    private boolean details;
    private JSONObject data;
    private String clockText = "—";
    private String error = "Consultando el nodo…";
    private Dashboard dashboard;

    // Un despertar por minuto; no depende del RPC ni de su tiempo de respuesta.
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!active) return;
            clockText = android.text.format.DateFormat.format("HH:mm", System.currentTimeMillis()).toString();
            dashboard.invalidate();
            handler.removeCallbacks(this);
            handler.postDelayed(this, 60000 - System.currentTimeMillis() % 60000);
        }
    };

    private static NumberFormat[] makeDecimalFormats() {
        NumberFormat[] formats = new NumberFormat[3];
        for (int i = 0; i < formats.length; i++) {
            formats[i] = NumberFormat.getNumberInstance(DISPLAY_LOCALE);
            formats[i].setGroupingUsed(false);
            formats[i].setMinimumFractionDigits(i);
            formats[i].setMaximumFractionDigits(i);
        }
        return formats;
    }

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (!active || inFlight) return;
            inFlight = true;
            worker.execute(new Runnable() {
                @Override public void run() {
                    JSONObject response = null;
                    try { response = readJson(API); } catch (Exception ignored) { }
                    final JSONObject result = response;
                    handler.post(new Runnable() {
                        @Override public void run() {
                            inFlight = false;
                            if (destroyed) return;
                            if (result != null) {
                                data = result;
                                error = null;
                            } else error = "Sin respuesta del panel · reintentando…";
                            if (active) {
                                dashboard.invalidate();
                                handler.removeCallbacks(refresh);
                                handler.postDelayed(refresh, 10000);
                            }
                        }
                    });
                }
            });
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        startService(new Intent(this, WifiReconnectService.class));
        if (state != null) details = state.getBoolean("details");
        dashboard = new Dashboard();
        setContentView(dashboard);
        immersive();
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putBoolean("details", details);
        super.onSaveInstanceState(state);
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            details = false;
            dashboard.invalidate();
        }
    }
    @Override protected void onStart() {
        super.onStart();
        active = true;
        handler.removeCallbacks(clockTick);
        handler.post(clockTick);
        handler.removeCallbacks(refresh);
        handler.post(refresh);
    }
    @Override protected void onStop() {
        active = false;
        handler.removeCallbacks(refresh);
        handler.removeCallbacks(clockTick);
        HttpURLConnection pending = connection;
        if (pending != null) pending.disconnect();
        super.onStop();
    }
    @Override protected void onDestroy() {
        destroyed = true;
        active = false;
        handler.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        HttpURLConnection pending = connection;
        if (pending != null) pending.disconnect();
        super.onDestroy();
    }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) immersive();
    }
    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }
    @Override public void onBackPressed() {
        if (details) { details = false; dashboard.invalidate(); }
        // Es la pantalla de inicio del equipo: Atrás conserva el panel visible.
    }
    private JSONObject readJson(String url) throws Exception {
        if (!active || destroyed || Thread.currentThread().isInterrupted()) {
            throw new java.io.InterruptedIOException("Panel fuera de pantalla");
        }
        HttpURLConnection request = (HttpURLConnection) new URL(url).openConnection();
        connection = request;
        try {
            request.setConnectTimeout(2000);
            request.setReadTimeout(30000);
            request.setUseCaches(false);
            if (!active || destroyed) throw new java.io.InterruptedIOException("Panel fuera de pantalla");
            if (request.getResponseCode() != 200) throw new java.io.IOException("HTTP");
            try (InputStream input = request.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 262144) throw new java.io.IOException("Respuesta demasiado grande");
                    output.write(buffer, 0, count);
                }
                return new JSONObject(output.toString("UTF-8"));
            }
        } finally { connection = null; request.disconnect(); }
    }
    private void rawDetails() {
        if (detailInFlight || destroyed) return;
        detailInFlight = true;
        dashboard.invalidate();
        worker.execute(new Runnable() {
            @Override public void run() {
                String report;
                try {
                    report = new String(Base64.decode(readJson(API + "?detail=1")
                            .getString("detail_b64"), Base64.DEFAULT), "UTF-8");
                } catch (Exception e) { report = "No se pudo obtener el informe. Volvé a intentarlo."; }
                final String text = report;
                handler.post(new Runnable() {
                    @Override public void run() {
                        detailInFlight = false;
                        if (destroyed || !active) return;
                        TextView content = new TextView(NativePanelActivity.this);
                        content.setText(text);
                        content.setTextSize(14);
                        content.setTypeface(Typeface.MONOSPACE);
                        content.setTextIsSelectable(true);
                        content.setPadding(24, 20, 24, 20);
                        ScrollView scroll = new ScrollView(NativePanelActivity.this);
                        scroll.addView(content);
                        new AlertDialog.Builder(NativePanelActivity.this).setTitle("Información técnica")
                                .setView(scroll).setPositiveButton("Cerrar", null).show();
                        dashboard.invalidate();
                    }
                });
            }
        });
    }

    private String s(String key) { return data == null ? "—" : data.optString(key, "—"); }
    private double n(String key) { return data == null ? 0 : data.optDouble(key, 0); }
    private boolean b(String key) { return data != null && data.optBoolean(key); }
    private boolean ready() { return "activo".equals(s("bitcoin")) && n("headers") > 0; }
    private String number(double value) { return integerFormat.format(value); }
    private String decimal(double value, int places) {
        return decimalFormats[places].format(value);
    }
    private String bytes(double value) {
        if (value >= 1073741824) return decimal(value / 1073741824, 1) + " GB";
        if (value >= 1048576) return decimal(value / 1048576, 1) + " MB";
        if (value >= 1024) return decimal(value / 1024, 0) + " kB";
        return number(value) + " B";
    }
    private String duration(double seconds) {
        long total = Math.max(0, (long) seconds);
        if (total >= 86400) return total / 86400 + " d " + total % 86400 / 3600 + " h";
        if (total >= 3600) return total / 3600 + " h " + total % 3600 / 60 + " min";
        if (total >= 60) return total / 60 + " min";
        return total + " s";
    }
    private String ago() { return n("last_block_time") == 0 ? "—" : "hace " + duration(
            System.currentTimeMillis() / 1000 - n("last_block_time")); }
    private String version() {
        int value = (int) n("core_version");
        return value == 0 ? "—" : value / 10000 + "." + value % 10000 / 100 + "." + value % 100;
    }
    private String state() {
        if (error != null) return data == null ? "CONECTANDO CON EL NODO" : "SIN RESPUESTA DEL PANEL";
        if (n("storage_available") == 0) return "ERROR DE ALMACENAMIENTO";
        if (!"conectado".equals(s("wifi"))) return "SIN CONEXIÓN";
        if (!"activo".equals(s("tor"))) return "ESPERANDO TOR";
        if ("iniciando".equals(s("bitcoin"))) return "INICIANDO BITCOIN CORE";
        if (!"activo".equals(s("bitcoin"))) return "BITCOIN CORE DETENIDO";
        if (b("initial_block_download") || n("blocks") < n("headers")) return "SINCRONIZANDO";
        return "NODO ACTIVO";
    }

    private final class Dashboard extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Drawable logo = getDrawable(R.drawable.ic_bitcoin);
        private final int background = Color.rgb(11, 14, 18), surface = Color.rgb(20, 26, 33);
        private final int foreground = Color.rgb(244, 246, 248), muted = Color.rgb(147, 161, 178);
        private final int orange = Color.rgb(247, 147, 26), green = Color.rgb(84, 214, 146);
        private final int yellow = Color.rgb(241, 184, 75), red = Color.rgb(255, 107, 107);
        private float scale, offsetX, offsetY;
        private boolean landscape;
        private boolean pressed;
        Dashboard() { super(NativePanelActivity.this); setClickable(true); setFocusable(true); }
        private void rect(Canvas c, float x, float y, float w, float h, int color, float radius) {
            paint.setColor(color); paint.setStyle(Paint.Style.FILL);
            c.drawRoundRect(x, y, x + w, y + h, radius, radius, paint);
        }
        private void card(Canvas c, float x, float y, float w, float h) {
            rect(c, x, y, w, h, Color.rgb(41, 53, 68), 14);
            rect(c, x + 1, y + 1, w - 2, h - 2, surface, 13);
        }
        private void text(Canvas c, String text, float x, float y, float size, int color, boolean bold, float width) {
            paint.setColor(color); paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            paint.setTextSize(size);
            float measured = paint.measureText(text);
            if (measured > width && width > 0) paint.setTextSize(size * width / measured);
            c.drawText(text, x, y, paint);
        }
        private void rightText(Canvas c, String value, float right, float y, float size, int color,
                boolean bold, float width) {
            paint.setColor(color); paint.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            paint.setTextSize(size);
            float measured = paint.measureText(value);
            if (measured > width) paint.setTextSize(size * width / measured);
            c.drawText(value, right - paint.measureText(value), y, paint);
        }
        private void label(Canvas c, String text, float x, float y) { text(c, text, x, y, 13, muted, true, 690); }
        private void centeredText(Canvas c, String text, float centerX, float centerY,
                float size, int color, float width) {
            paint.setTypeface(Typeface.DEFAULT);
            paint.setTextSize(size);
            float measured = paint.measureText(text);
            if (measured > width) paint.setTextSize(size * width / measured);
            paint.setColor(color);
            c.drawText(text, centerX - paint.measureText(text) / 2,
                    centerY - (paint.ascent() + paint.descent()) / 2, paint);
        }
        private void bar(Canvas c, float x, float y, float w, double percent) {
            rect(c, x, y, w, 7, Color.rgb(41, 53, 68), 4);
            double value = Math.max(0, Math.min(100, percent));
            int shown = shownPercent(value);
            if (value > 0) rect(c, x, y, (float) (w * value / 100), 7,
                    shown >= 90 ? red : shown >= 75 ? yellow : green, 4);
        }
        private void batteryBar(Canvas c, float x, float y, float w) {
            rect(c, x, y, w, 7, Color.rgb(41, 53, 68), 4);
            double value = Math.max(0, Math.min(100, n("battery_percent")));
            if (value > 0) rect(c, x, y, (float) (w * value / 100), 7,
                    value <= 15 ? red : value <= 30 ? yellow : green, 4);
        }
        private double percent(String used, String total) { return n(total) > 0 ? 100 * n(used) / n(total) : 0; }
        private int shownPercent(double value) {
            return (int) Math.round(Math.max(0, Math.min(100, value)));
        }
        @Override protected void onDraw(Canvas c) {
            c.drawColor(background);
            landscape = getWidth() > getHeight();
            float designWidth = landscape ? 1280f : 800f;
            float designHeight = landscape ? 800f : 1280f;
            scale = Math.min(getWidth() / designWidth, getHeight() / designHeight);
            offsetX = (getWidth() - designWidth * scale) / 2;
            offsetY = (getHeight() - designHeight * scale) / 2;
            c.save(); c.translate(offsetX, offsetY); c.scale(scale, scale);
            logo.setBounds(30, 43, 76, 89);
            logo.draw(c);
            text(c, details ? "DETALLES DEL NODO" : "NODO BITCOIN", 89, 75, 27, foreground, false, 490);
            text(c, clockText, landscape ? 1175 : 710, 75, 24, foreground, false, 65);
            rect(c, 30, 103, landscape ? 1215 : 740, 1, Color.rgb(41, 53, 68), 0);
            if (landscape) {
                if (details) drawDetailsLandscape(c); else drawMainLandscape(c);
                if (error != null) text(c, error, 34, 750, 15, yellow, false, 850);
                card(c, 1080, 710, 165, 55);
                centeredText(c, details ? "VOLVER" : "DETALLES", 1162.5f, 737.5f, 15, orange, 140);
            } else {
                if (details) drawDetails(c); else drawMain(c);
                if (error != null) text(c, error, 34, details ? 1132 : 1092, 15, yellow, false, 545);
                float buttonY = details ? 1100 : 1060;
                card(c, 605, buttonY, 164, 53);
                centeredText(c, details ? "VOLVER" : "DETALLES", 687, buttonY + 26.5f, 15, orange, 140);
            }
            setContentDescription(state() + ". " + (ready() ? "Bloque " + number(n("blocks")) : "Esperando Core")
                    + ". Memoria " + shownPercent(percent("memory_used_kib", "memory_total_kib")) + " por ciento. "
                    + (details ? "Activar para volver." : "Activar para detalles."));
            c.restore();
        }
        private void drawMain(Canvas c) {
            String state = state();
            int tone = "NODO ACTIVO".equals(state) ? green : state.startsWith("ERROR") || state.startsWith("SIN ") ? red : yellow;
            card(c, 30, 118, 740, 135);
            rect(c, 30, 122, 5, 127, tone, 2);
            label(c, "ESTADO GENERAL", 57, 151);
            text(c, state, 57, 200, 31, foreground, false, 682);
            text(c, "Core " + s("bitcoin") + " · Tor " + s("tor"), 57, 235, 17, muted, false, 690);
            card(c, 30, 266, 740, 185);
            label(c, "SINCRONIZACIÓN", 53, 296);
            rightText(c, "BLOQUES / CABECERAS", 747, 296, 13, muted, true, 191);
            double progress = Math.max(0, Math.min(100, n("verification_progress") * 100));
            text(c, ready() ? decimal(progress, progress >= 100 ? 0 : 2) + " %" : "—", 53, 355, 42, foreground, true, 280);
            rightText(c, ready() ? number(n("blocks")) + " / " + number(n("headers")) : "— / —", 747, 331, 24, foreground, false, 262);
            rightText(c, ready() ? n("blocks") < n("headers") ? "Validando bloques" : "Cadena al día" : "Esperando datos", 747, 355, 14, muted, false, 167);
            rect(c, 53, 385, 694, 12, Color.rgb(41, 53, 68), 6);
            if (ready() && progress > 0) rect(c, 53, 385, (float) (694 * progress / 100), 12, green, 6);
            text(c, ready() ? n("blocks") < n("headers") ? "Validando la cadena" : "Sincronización completa" : "Esperando RPC de Bitcoin Core", 53, 429, 17, muted, false, 455);
            rightText(c, "Último bloque: " + (ready() ? ago() : "—"), 747, 429, 16, muted, false, 257);
            metric(c, 30, 463, "PARES BITCOIN", ready() ? (int)n("connections_out") + " sal. · " + (int)n("connections_in") + " ent." : "—",
                    ready() ? (int)(n("connections_out") + n("connections_in")) + " conexiones en total" : "esperando Core");
            metric(c, 408, 463, "ÚLTIMO BLOQUE", ready() ? ago() : "—", ready() ? "punta " + number(n("blocks")) : "esperando Core");
            metric(c, 30, 598, "MEMPOOL", ready() ? number(n("mempool_size")) + " tx · " + bytes(n("mempool_bytes")) : "—",
                    ready() ? decimal(percent("mempool_bytes", "mempool_max"), 0) + "% de " + bytes(n("mempool_max")) : "esperando Core");
            metric(c, 408, 598, "CORE ACTIVO", n("core_uptime") > 0 ? duration(n("core_uptime")) : "—", "desde el último inicio");
            resource(c, 30, "MICROSD", "storage_used_kib", "storage_total_kib");
            resource(c, 281, "MEMORIA", "memory_used_kib", "memory_total_kib");
            card(c, 532, 731, 238, 178);
            label(c, "TABLET", 550, 760);
            text(c, (int)n("battery_percent") + "% · " + decimal(n("battery_temp_tenths") / 10, 0) + " °C", 550, 818, 29, foreground, false, 200);
            String charging = s("battery_status");
            String battery = "Charging".equals(charging) ? "Cargando" : "Discharging".equals(charging) ? "En batería"
                    : "Full".equals(charging) ? "Carga completa" : "Not charging".equals(charging) ? "Conectada" : "Estado desconocido";
            text(c, battery, 550, 856, 15, muted, false, 200);
            batteryBar(c, 551, 891, 200);
            connection(c, 30, "TOR", "activo".equals(s("tor")), "activo".equals(s("tor")) ? "Activo" : "No disponible");
            connection(c, 281, "WI-FI", "conectado".equals(s("wifi")), "conectado".equals(s("wifi")) ? "Conectado" : "Desconectado");
            connection(c, 532, "SSH", n("ssh_connections") > 0, n("ssh_connections") > 0 ? (int)n("ssh_connections") + " conexiones" : "Sin conexiones");
        }
        private void drawMainLandscape(Canvas c) {
            String state = state();
            int tone = "NODO ACTIVO".equals(state) ? green : state.startsWith("ERROR") || state.startsWith("SIN ") ? red : yellow;
            card(c, 30, 115, 590, 155);
            rect(c, 30, 119, 5, 147, tone, 2);
            label(c, "ESTADO GENERAL", 54, 145);
            text(c, state, 54, 205, 31, foreground, false, 540);
            text(c, "Core " + s("bitcoin") + " · Tor " + s("tor"), 54, 257, 14, muted, false, 540);

            card(c, 635, 115, 610, 155);
            label(c, "SINCRONIZACIÓN", 659, 145);
            rightText(c, "BLOQUES / CABECERAS", 1221, 145, 13, muted, true, 194);
            double progress = Math.max(0, Math.min(100, n("verification_progress") * 100));
            text(c, ready() ? decimal(progress, progress >= 100 ? 0 : 2) + " %" : "—", 659, 205, 42, foreground, true, 240);
            rightText(c, ready() ? number(n("blocks")) + " / " + number(n("headers")) : "— / —", 1221, 194, 23, foreground, false, 257);
            rightText(c, ready() ? n("blocks") < n("headers") ? "Validando bloques" : "Cadena al día" : "Esperando datos", 1221, 215, 14, muted, false, 170);
            rect(c, 659, 226, 562, 11, Color.rgb(41, 53, 68), 6);
            if (ready() && progress > 0) rect(c, 659, 226, (float) (562 * progress / 100), 11, green, 6);
            text(c, ready() ? n("blocks") < n("headers") ? "Validando la cadena" : "Sincronización completa" : "Esperando RPC de Bitcoin Core", 659, 257, 14, muted, false, 280);
            rightText(c, "Último bloque: " + (ready() ? ago() : "—"), 1221, 257, 14, muted, false, 242);

            landscapeMetric(c, 30, "PARES BITCOIN", ready() ? (int)n("connections_out") + " sal. · " + (int)n("connections_in") + " ent." : "—",
                    ready() ? (int)(n("connections_out") + n("connections_in")) + " conexiones en total" : "esperando Core");
            landscapeMetric(c, 338, "ÚLTIMO BLOQUE", ready() ? ago() : "—", ready() ? "punta " + number(n("blocks")) : "esperando Core");
            landscapeMetric(c, 646, "MEMPOOL", ready() ? number(n("mempool_size")) + " tx · " + bytes(n("mempool_bytes")) : "—",
                    ready() ? decimal(percent("mempool_bytes", "mempool_max"), 0) + "% de " + bytes(n("mempool_max")) : "esperando Core");
            landscapeMetric(c, 954, "CORE ACTIVO", n("core_uptime") > 0 ? duration(n("core_uptime")) : "—", "desde el último inicio");

            landscapeResource(c, 30, "MICROSD", "storage_used_kib", "storage_total_kib");
            landscapeResource(c, 440, "MEMORIA", "memory_used_kib", "memory_total_kib");
            card(c, 850, 436, 395, 160);
            label(c, "TABLET", 869, 465);
            text(c, (int)n("battery_percent") + "% · " + decimal(n("battery_temp_tenths") / 10, 0) + " °C", 869, 523, 29, foreground, false, 355);
            String charging = s("battery_status");
            String battery = "Charging".equals(charging) ? "Cargando" : "Discharging".equals(charging) ? "En batería"
                    : "Full".equals(charging) ? "Carga completa" : "Not charging".equals(charging) ? "Conectada" : "Estado desconocido";
            text(c, battery, 869, 555, 14, muted, false, 355);
            batteryBar(c, 869, 578, 355);

            landscapeConnection(c, 30, "TOR", "activo".equals(s("tor")), "activo".equals(s("tor")) ? "Activo" : "No disponible");
            landscapeConnection(c, 440, "WI-FI", "conectado".equals(s("wifi")), "conectado".equals(s("wifi")) ? "Conectado" : "Desconectado");
            landscapeConnection(c, 850, "SSH", n("ssh_connections") > 0, n("ssh_connections") > 0 ? (int)n("ssh_connections") + " conexiones" : "Sin conexiones");
        }
        private void landscapeMetric(Canvas c, float x, String title, String value, String note) {
            card(c, x, 283, 290, 140);
            label(c, title, x + 19, 310);
            text(c, value, x + 19, 369, 23, foreground, false, 252);
            text(c, note, x + 19, 405, 14, muted, false, 252);
        }
        private void landscapeResource(Canvas c, float x, String title, String used, String total) {
            card(c, x, 436, 395, 160);
            label(c, title, x + 19, 465);
            text(c, shownPercent(percent(used, total)) + "% usada", x + 19, 523, 29, foreground, false, 355);
            text(c, bytes(n(used) * 1024) + " / " + bytes(n(total) * 1024), x + 19, 555, 14, muted, false, 355);
            bar(c, x + 19, 578, 355, percent(used, total));
        }
        private void landscapeConnection(Canvas c, float x, String title, boolean ok, String value) {
            card(c, x, 609, 395, 90);
            label(c, title, x + 19, 636);
            rect(c, x + 19, 668, 12, 12, ok ? green : "SSH".equals(title) ? muted : red, 6);
            text(c, value, x + 43, 681, 19, foreground, false, 330);
        }
        private void metric(Canvas c, float x, float y, String title, String value, String note) {
            card(c, x, y, 362, 123); label(c, title, x + 23, y + 28);
            text(c, value, x + 23, y + 69, 26, foreground, false, 316);
            text(c, note, x + 23, y + 106, 14, muted, false, 316);
        }
        private void resource(Canvas c, float x, String title, String used, String total) {
            card(c, x, 731, 238, 178); label(c, title, x + 19, 760);
            text(c, shownPercent(percent(used, total)) + "% usada", x + 19, 818, 29, foreground, false, 200);
            text(c, bytes(n(used) * 1024) + " / " + bytes(n(total) * 1024), x + 19, 856, 14, muted, false, 200);
            bar(c, x + 19, 891, 200, percent(used, total));
        }
        private void connection(Canvas c, float x, String title, boolean ok, String value) {
            card(c, x, 921, 238, 116); label(c, title, x + 19, 949);
            rect(c, x + 19, 1003, 12, 12, ok ? green : "SSH".equals(title) ? muted : red, 6);
            text(c, value, x + 43, 1016, 19, foreground, false, 182);
        }
        private void drawDetails(Canvas c) {
            card(c, 30, 118, 740, 935);
            detail(c, "BITCOIN CORE", "Versión " + version() + " · " + s("chain"), 155);
            detail(c, "ESTADO", state(), 252);
            detail(c, "RED", ready() ? (int)(n("connections_in") + n("connections_out")) + " pares · Tor " + s("tor") : "Esperando Bitcoin Core", 349);
            detail(c, "PODA", ready() ? b("pruned") ? "Activa desde bloque " + number(n("prune_height")) + " · " + bytes(n("prune_target_bytes")) : "Desactivada" : "Esperando Bitcoin Core", 446);
            detail(c, "TIEMPO ACTIVO", "Core " + duration(n("core_uptime")) + " · Android " + duration(n("device_uptime")), 543);
            detail(c, "MICROSD", bytes(n("storage_used_kib") * 1024) + " / " + bytes(n("storage_total_kib") * 1024), 640);
            detail(c, "MEMORIA", bytes(n("memory_used_kib") * 1024) + " / " + bytes(n("memory_total_kib") * 1024), 737);
            label(c, "DIRECCIÓN ONION P2P", 54, 834);
            String onion = s("onion") + ":8333";
            for (int i = 0; i < onion.length(); i += 36) text(c, onion.substring(i, Math.min(i + 36, onion.length())), 54,
                    869 + i / 36 * 27, 22, foreground, false, 690);
            card(c, 54, 959, 692, 60);
            centeredText(c, detailInFlight ? "Consultando…" : "VER INFORMACIÓN TÉCNICA COMPLETA", 400, 989, 21, orange, 644);
        }
        private void drawDetailsLandscape(Canvas c) {
            card(c, 30, 115, 590, 583);
            card(c, 635, 115, 610, 583);
            landscapeDetail(c, 54, "BITCOIN CORE", "Versión " + version() + " · " + s("chain"), 155, 540);
            landscapeDetail(c, 54, "ESTADO", state(), 282, 540);
            landscapeDetail(c, 54, "RED", ready() ? (int)(n("connections_in") + n("connections_out")) + " pares · Tor " + s("tor") : "Esperando Bitcoin Core", 409, 540);
            landscapeDetail(c, 54, "PODA", ready() ? b("pruned") ? "Activa desde bloque " + number(n("prune_height")) + " · " + bytes(n("prune_target_bytes")) : "Desactivada" : "Esperando Bitcoin Core", 536, 540);
            landscapeDetail(c, 659, "TIEMPO ACTIVO", "Core " + duration(n("core_uptime")) + " · Android " + duration(n("device_uptime")), 155, 560);
            landscapeDetail(c, 659, "MICROSD", bytes(n("storage_used_kib") * 1024) + " / " + bytes(n("storage_total_kib") * 1024), 282, 560);
            landscapeDetail(c, 659, "MEMORIA", bytes(n("memory_used_kib") * 1024) + " / " + bytes(n("memory_total_kib") * 1024), 409, 560);
            label(c, "DIRECCIÓN ONION P2P", 659, 536);
            String onion = s("onion");
            for (int i = 0; i < onion.length(); i += 32) {
                text(c, onion.substring(i, Math.min(i + 32, onion.length())), 659,
                        575 + i / 32 * 31, 22, foreground, false, 560);
            }
            text(c, "Puerto 8333", 659, 661, 15, muted, false, 560);
            card(c, 635, 710, 400, 55);
            centeredText(c, detailInFlight ? "Consultando…" : "VER INFORMACIÓN TÉCNICA COMPLETA", 835, 737.5f, 18, orange, 365);
        }
        private void landscapeDetail(Canvas c, float x, String title, String value, float y, float width) {
            label(c, title, x, y);
            text(c, value, x, y + 42, 24, foreground, false, width);
        }
        private void detail(Canvas c, String title, String value, float y) {
            label(c, title, 54, y);
            text(c, value, 54, y + 36, 24, foreground, false, 690);
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (scale <= 0) return false;
            float x = (event.getX() - offsetX) / scale, y = (event.getY() - offsetY) / scale;
            boolean button = landscape ? x >= 1080 && x <= 1245 && y >= 710 && y <= 765
                    : x >= 605 && x <= 769 && y >= (details ? 1100 : 1060) && y <= (details ? 1153 : 1113);
            boolean raw = details && (landscape ? x >= 635 && x <= 1035 && y >= 710 && y <= 765
                    : x >= 54 && x <= 746 && y >= 959 && y <= 1019);
            if (event.getAction() == MotionEvent.ACTION_DOWN) { pressed = button || raw; return pressed; }
            if (event.getAction() == MotionEvent.ACTION_CANCEL) { pressed = false; return true; }
            if (event.getAction() == MotionEvent.ACTION_UP && pressed) {
                pressed = false;
                if (raw) rawDetails(); else if (button) performClick();
                return true;
            }
            return pressed;
        }
        @Override public boolean performClick() {
            super.performClick(); details = !details; invalidate(); return true;
        }
    }
}
