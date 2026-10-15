package ar.eze.v480wifiautoconnect;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public final class MainActivity extends Activity {
    private static final String TAG = "V480NodeApp";
    private static final String STATUS_URL = "http://localhost:8080/cgi-bin/api";
    private static final String NODE_START = "/data/data/com.termux/files/home/bin/node-start";
    private static final String NODE_STOP = "/data/data/com.termux/files/home/bin/node-stop";
    private static final long REFRESH_MS = 10_000L;
    private static final long ACTION_TIMEOUT_MS = 20_000L;

    private static final int ACTION_NONE = 0;
    private static final int ACTION_START = 1;
    private static final int ACTION_STOP = 2;

    private final Handler handler = new Handler();
    private Button startButton;
    private Button stopButton;
    private TextView statusText;
    private boolean statusRequestInFlight;
    private boolean monitoring;
    private int pendingAction = ACTION_NONE;
    private long pendingActionDeadline;

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            if (!monitoring) return;
            handler.removeCallbacks(this);
            refreshNodeStatus();
            if (monitoring) {
                handler.postDelayed(this, pendingAction == ACTION_NONE ? REFRESH_MS : 3_000L);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, WifiReconnectService.class));
        buildInterface();
        showChecking();
    }

    private void buildInterface() {
        int gap = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(gap, gap, gap, gap);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.ic_bitcoin);
        logo.setContentDescription("Bitcoin");
        LinearLayout.LayoutParams logoParams = new LinearLayout.LayoutParams(dp(88), dp(88));
        logoParams.gravity = Gravity.CENTER_HORIZONTAL;
        logoParams.bottomMargin = gap;
        root.addView(logo, logoParams);

        TextView title = new TextView(this);
        title.setText("Nodo Bitcoin V480");
        title.setTextSize(28f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title, fullWidthWrap());

        TextView description = new TextView(this);
        description.setText("Wi-Fi, Tor y Bitcoin se recuperan automáticamente. "
                + "La app comprueba el estado del nodo cada 10 segundos.");
        description.setTextSize(18f);
        description.setPadding(0, gap, 0, 0);
        root.addView(description, fullWidthWrap());

        statusText = new TextView(this);
        statusText.setTextSize(18f);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setGravity(Gravity.CENTER);
        statusText.setPadding(0, gap, 0, 0);
        root.addView(statusText, fullWidthWrap());

        Button panelButton = makeButton("ABRIR PANEL DEL NODO", 18f);
        panelButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(MainActivity.this, NativePanelActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
            }
        });
        root.addView(panelButton, buttonParams(gap));

        startButton = makeButton("INICIAR NODO", 16f);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pendingAction = ACTION_START;
                pendingActionDeadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS;
                showTransition("Iniciando el nodo…");
                runTermuxCommand(NODE_START, "Orden de inicio enviada");
                handler.removeCallbacks(refreshRunnable);
                handler.postDelayed(refreshRunnable, 1_000L);
            }
        });
        root.addView(startButton, buttonParams(gap));

        stopButton = makeButton("DETENER Y PREPARAR APAGADO", 16f);
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                pendingAction = ACTION_STOP;
                pendingActionDeadline = SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS;
                showTransition("Deteniendo el nodo…");
                runTermuxCommand(NODE_STOP,
                        "Deteniendo el nodo; esperá 15 segundos antes de apagar");
                handler.removeCallbacks(refreshRunnable);
                handler.postDelayed(refreshRunnable, 1_000L);
            }
        });
        root.addView(stopButton, buttonParams(gap));

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    private Button makeButton(String text, float textSize) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(textSize);
        return button;
    }

    private LinearLayout.LayoutParams fullWidthWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams buttonParams(int topMargin) {
        LinearLayout.LayoutParams params = fullWidthWrap();
        params.topMargin = topMargin;
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        monitoring = true;
        handler.removeCallbacks(refreshRunnable);
        handler.post(refreshRunnable);
    }

    @Override
    protected void onPause() {
        monitoring = false;
        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void refreshNodeStatus() {
        if (statusRequestInFlight) {
            return;
        }
        statusRequestInFlight = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                NodeStatus result = readNodeStatus();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        statusRequestInFlight = false;
                        if (monitoring) applyNodeStatus(result);
                    }
                });
            }
        }, "node-status").start();
    }

    private NodeStatus readNodeStatus() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(STATUS_URL).openConnection();
            // El CGI consulta a Bitcoin Core en una tablet lenta; durante IBD una
            // respuesta normal puede tardar varios segundos.
            connection.setConnectTimeout(2_000);
            connection.setReadTimeout(8_000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Cache-Control", "no-cache");
            InputStream input = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, "UTF-8"));
            StringBuilder jsonText = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonText.append(line);
            }
            reader.close();
            JSONObject json = new JSONObject(jsonText.toString());
            String bitcoin = json.optString("bitcoin", "inactivo");
            String tor = json.optString("tor", "inactivo");
            return new NodeStatus("activo".equalsIgnoreCase(bitcoin), bitcoin, tor, true);
        } catch (Throwable error) {
            Log.w(TAG, "No se pudo consultar la API local del nodo", error);
            return new NodeStatus(false, "inactivo", "inactivo", false);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void applyNodeStatus(NodeStatus status) {
        long now = SystemClock.elapsedRealtime();
        if (pendingAction == ACTION_START) {
            if (status.active) {
                pendingAction = ACTION_NONE;
                showActive(status.tor);
            } else if (now < pendingActionDeadline) {
                showTransition("Iniciando el nodo…");
            } else {
                pendingAction = ACTION_NONE;
                showInactive(status);
            }
            return;
        }

        if (pendingAction == ACTION_STOP) {
            if (!status.reachable || !status.active) {
                pendingAction = ACTION_NONE;
                showInactive(status);
            } else if (now < pendingActionDeadline) {
                showTransition("Deteniendo el nodo…");
            } else {
                pendingAction = ACTION_NONE;
                showActive(status.tor);
            }
            return;
        }

        if (status.active) {
            showActive(status.tor);
        } else {
            showInactive(status);
        }
    }

    private void showChecking() {
        statusText.setText("Comprobando el estado del nodo…");
        statusText.setTextColor(Color.DKGRAY);
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
    }

    private void showTransition(String message) {
        statusText.setText("● " + message);
        statusText.setTextColor(Color.rgb(230, 126, 34));
        startButton.setEnabled(false);
        stopButton.setEnabled(false);
    }

    private void showActive(String tor) {
        statusText.setText("● Nodo activo · Tor " + tor);
        statusText.setTextColor(Color.rgb(32, 122, 72));
        startButton.setEnabled(false);
        stopButton.setEnabled(true);
    }

    private void showInactive(NodeStatus status) {
        String message = status.reachable
                ? "● Nodo detenido · Tor " + status.tor
                : "● Nodo detenido";
        statusText.setText(message);
        statusText.setTextColor(Color.rgb(120, 120, 120));
        startButton.setEnabled(true);
        stopButton.setEnabled(false);
    }

    private void runTermuxCommand(String path, String successMessage) {
        try {
            Intent command = new Intent("com.termux.RUN_COMMAND");
            command.setComponent(new ComponentName(
                    "com.termux", "com.termux.app.RunCommandService"));
            command.putExtra("com.termux.RUN_COMMAND_PATH", path);
            command.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[0]);
            command.putExtra("com.termux.RUN_COMMAND_WORKDIR",
                    "/data/data/com.termux/files/home");
            command.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(command);
            Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show();
        } catch (Throwable error) {
            pendingAction = ACTION_NONE;
            Toast.makeText(this, "No se pudo ejecutar la orden: " + error.getMessage(),
                    Toast.LENGTH_LONG).show();
            refreshNodeStatus();
        }
    }

    private static final class NodeStatus {
        final boolean active;
        final String bitcoin;
        final String tor;
        final boolean reachable;

        NodeStatus(boolean active, String bitcoin, String tor, boolean reachable) {
            this.active = active;
            this.bitcoin = bitcoin;
            this.tor = tor;
            this.reachable = reachable;
        }
    }
}
