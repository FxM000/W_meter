package com.wattmetre.app;

import android.app.Activity;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Locale;

public class MainActivity extends Activity {

    // Puissance maximale du téléphone (Redmi K60 : 67 W). Change ici pour un autre modèle.
    private static final double MAX_W = 67.0;
    // Part de l'énergie du chargeur qui arrive réellement dans la batterie.
    private static final double EFFICIENCY = 0.85;

    private static final int YELLOW = Color.parseColor("#F2B705");
    private static final int BG = Color.parseColor("#14171B");
    private static final int MUTED = Color.parseColor("#98A2AD");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private BatteryManager bm;
    private TextView bigWatts;
    private TextView mode;
    private TextView details;
    private Button unitBtn;
    private int unitMode = 0; // 0 = auto, 1 = µA, 2 = mA
    private double peak = 0;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(BG);
        bm = (BatteryManager) getSystemService(BATTERY_SERVICE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(dp(20), dp(24), dp(20), dp(24));

        root.addView(label("Wattmètre de charge", 24, YELLOW, true));
        addWithTop(root, label("Tension et courant réels de la batterie.", 14, MUTED, false), 4);

        bigWatts = new TextView(this);
        bigWatts.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        bigWatts.setTextSize(56);
        bigWatts.setTextColor(Color.parseColor("#17200F"));
        bigWatts.setGravity(Gravity.CENTER);
        bigWatts.setText("--,- W");
        GradientDrawable lcd = new GradientDrawable();
        lcd.setColor(Color.parseColor("#C4CFAE"));
        lcd.setCornerRadius(dp(10));
        bigWatts.setBackground(lcd);
        bigWatts.setPadding(dp(12), dp(24), dp(12), dp(24));
        addWithTop(root, bigWatts, 20);

        addWithTop(root, label("Puissance du chargeur, estimée à partir de la batterie", 13, MUTED, false), 6);

        mode = label("Mode : --", 18, Color.WHITE, true);
        addWithTop(root, mode, 16);

        details = label("", 15, Color.parseColor("#ECEEF1"), false);
        details.setTypeface(Typeface.MONOSPACE);
        details.setLineSpacing(0, 1.3f);
        addWithTop(root, details, 12);

        unitBtn = new Button(this);
        unitBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                unitMode = (unitMode + 1) % 3;
                refreshUnitLabel();
                update();
            }
        });
        refreshUnitLabel();
        addWithTop(root, unitBtn, 20);

        Button resetBtn = new Button(this);
        resetBtn.setText("Remettre le pic à zéro");
        resetBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                peak = 0;
                update();
            }
        });
        addWithTop(root, resetBtn, 8);

        addWithTop(root, label("Si le courant te paraît 1000 fois trop grand ou trop petit, change l’unité du courant. Pour une meilleure lecture, mesure entre 20 % et 70 % de batterie, écran allumé.", 13, MUTED, false), 16);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void refreshUnitLabel() {
        String u = unitMode == 0 ? "auto" : (unitMode == 1 ? "µA" : "mA");
        unitBtn.setText("Unité du courant : " + u);
    }

    private String chargeMode(double w) {
        if (w >= 45) return "Charge turbo";
        if (w >= 18) return "Charge rapide";
        if (w >= 5) return "Charge normale";
        return "Charge lente";
    }

    private void update() {
        Intent b = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return;

        int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
        if (mv > 0 && mv < 100) mv *= 1000; // certains téléphones donnent des volts
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        int pct = scale > 0 ? Math.round(level * 100f / scale) : level;

        long raw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        boolean hasCurrent = raw != Long.MIN_VALUE;
        boolean charging = plugged != 0;

        String plug;
        if (plugged == 0) {
            plug = "aucun";
        } else if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) {
            plug = "sans fil";
        } else if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) {
            plug = "chargeur (secteur)";
        } else {
            plug = "USB";
        }

        double volts = mv / 1000.0;
        double abs = Math.abs((double) raw);
        double ma;
        if (unitMode == 1) {
            ma = abs / 1000.0;
        } else if (unitMode == 2) {
            ma = abs;
        } else {
            ma = abs > 50000 ? abs / 1000.0 : abs;
        }
        double wBatt = volts * ma / 1000.0;
        double wCharger = wBatt / EFFICIENCY;

        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.FRANCE, "Batterie : %d %%  ·  %.1f °C\n", pct, temp / 10.0));
        sb.append("Branchement : ").append(plug).append("\n");

        if (!charging) {
            bigWatts.setText("--,- W");
            mode.setText("Branche le chargeur pour mesurer");
        } else if (!hasCurrent) {
            bigWatts.setText("--,- W");
            mode.setText("Courant non fourni par ce téléphone");
            sb.append(String.format(Locale.FRANCE, "Tension : %.2f V\n", volts));
        } else {
            if (wCharger > peak) peak = wCharger;
            bigWatts.setText(String.format(Locale.FRANCE, "%.1f W", wCharger));
            mode.setText(String.format(Locale.FRANCE, "Mode : %s (%d %% des %d W)",
                    chargeMode(wCharger), Math.round(wCharger / MAX_W * 100), (int) MAX_W));
            sb.append(String.format(Locale.FRANCE, "Tension : %.2f V\n", volts));
            sb.append(String.format(Locale.FRANCE, "Courant : %.0f mA\n", ma));
            sb.append(String.format(Locale.FRANCE, "Reçu par la batterie : %.1f W\n", wBatt));
            sb.append(String.format(Locale.FRANCE, "Pic du chargeur : %.1f W", peak));
        }
        details.setText(sb.toString());
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private void addWithTop(LinearLayout parent, View v, int topDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(topDp);
        parent.addView(v, lp);
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
