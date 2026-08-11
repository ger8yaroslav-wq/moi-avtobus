package ru.salarevo.buswidget;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private TextView status;
    private TextView diagnostic;
    private TextView routeCount;
    private LinearLayout configuredRoutes;
    private Button addRoute;
    private Button unmergeRoutes;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            renderStatus();
            handler.postDelayed(this, 30_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        status = findViewById(R.id.status);
        diagnostic = findViewById(R.id.diagnostic);
        routeCount = findViewById(R.id.route_count);
        configuredRoutes = findViewById(R.id.configured_routes);
        addRoute = findViewById(R.id.add_route);
        unmergeRoutes = findViewById(R.id.unmerge_routes);
        AppPrefs.removeLegacyKey(this);
        ConfigurationStore.load(this);

        findViewById(R.id.refresh_now).setOnClickListener(v -> {
            AppPrefs.beginRefresh(this);
            renderStatus();
            RefreshScheduler.refreshNow(this);
        });
        addRoute.setOnClickListener(v -> startActivity(new Intent(this, AddRouteActivity.class)));
        findViewById(R.id.merge_routes).setOnClickListener(v -> showMergeDialog());
        unmergeRoutes.setOnClickListener(v -> showUnmergeDialog());
        findViewById(R.id.copy_report).setOnClickListener(v -> copyReport());
        findViewById(R.id.open_transport).setOnClickListener(v -> openUrl(
                "https://transport.mos.ru/mostrans/all_news/125180"));

        RefreshScheduler.schedule(this);
        if (AppPrefs.get(this).getLong(AppPrefs.KEY_UPDATED, 0L) == 0L) {
            AppPrefs.beginRefresh(this);
            RefreshScheduler.refreshNow(this);
        }
        renderConfiguredRoutes();
        renderStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        renderConfiguredRoutes();
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private void renderConfiguredRoutes() {
        List<RouteConfig> configs = ConfigurationStore.load(this);
        configuredRoutes.removeAllViews();
        routeCount.setText("Добавлено маршрутов: " + configs.size() + " · без ограничения");
        addRoute.setEnabled(true);
        addRoute.setText("ДОБАВИТЬ АВТОБУС");
        Map<String, List<RouteConfig>> merges = collectMerges(configs);
        unmergeRoutes.setVisibility(merges.isEmpty() ? View.GONE : View.VISIBLE);
        String previousGroup = null;
        for (RouteConfig config : configs) {
            if (!config.groupLabel.equals(previousGroup)) {
                TextView header = new TextView(this);
                header.setText(config.groupLabel);
                header.setTextColor(getColor(R.color.text_primary));
                header.setTextSize(16);
                header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                hp.topMargin = dp(previousGroup == null ? 8 : 20);
                header.setLayoutParams(hp);
                configuredRoutes.addView(header);
                previousGroup = config.groupLabel;
            }

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(7), 0, dp(7));
            TextView info = new TextView(this);
            String direction = config.routeNumber + " → " + config.destination
                    + "\n" + config.stopName
                    + (config.nextStop.isEmpty() ? "" : " · далее " + config.nextStop)
                    + (config.mergeId.isEmpty() ? "" : "\nОбъединено: "
                    + joinedNumbers(merges.get(config.mergeId)));
            info.setText(direction);
            info.setTextColor(getColor(R.color.text_secondary));
            info.setTextSize(13);
            row.addView(info, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button remove = new Button(this);
            remove.setText("×");
            remove.setTextSize(18);
            remove.setContentDescription("Удалить " + config.routeNumber);
            remove.setOnClickListener(v -> confirmRemove(config));
            row.addView(remove, new LinearLayout.LayoutParams(dp(47), dp(43)));
            configuredRoutes.addView(row);
        }
        BusWidgetProvider.renderAll(this);
    }

    private void showMergeDialog() {
        List<RouteConfig> configs = ConfigurationStore.load(this);
        if (configs.size() < 2) {
            Toast.makeText(this, "Добавьте хотя бы два автобуса", Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[configs.size()];
        boolean[] selected = new boolean[configs.size()];
        for (int i = 0; i < configs.size(); i++) {
            RouteConfig config = configs.get(i);
            labels[i] = config.groupLabel + " · " + config.routeNumber
                    + "\n" + config.stopName + " → " + config.destination;
        }
        new AlertDialog.Builder(this)
                .setTitle("Отметьте автобусы галочками")
                .setMultiChoiceItems(labels, selected, (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Объединить", (dialog, which) -> {
                    List<String> ids = new ArrayList<>();
                    String groupLabel = null;
                    for (int i = 0; i < configs.size(); i++) {
                        if (!selected[i]) continue;
                        RouteConfig config = configs.get(i);
                        if (groupLabel == null) groupLabel = config.groupLabel;
                        if (!groupLabel.equals(config.groupLabel)) {
                            Toast.makeText(this, "Выберите автобусы из одного раздела",
                                    Toast.LENGTH_LONG).show();
                            return;
                        }
                        ids.add(config.id);
                    }
                    if (ids.size() < 2) {
                        Toast.makeText(this, "Выберите хотя бы два автобуса",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (ConfigurationStore.merge(this, ids)) {
                        renderConfiguredRoutes();
                        BusWidgetProvider.renderAll(this);
                    }
                })
                .show();
    }

    private void showUnmergeDialog() {
        Map<String, List<RouteConfig>> merges = collectMerges(ConfigurationStore.load(this));
        if (merges.isEmpty()) {
            Toast.makeText(this, "Объединённых автобусов пока нет", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> ids = new ArrayList<>(merges.keySet());
        String[] labels = new String[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            List<RouteConfig> group = merges.get(ids.get(i));
            labels[i] = group.get(0).groupLabel + " · " + joinedNumbers(group);
        }
        new AlertDialog.Builder(this)
                .setTitle("Разъединить автобусы")
                .setItems(labels, (dialog, which) -> {
                    ConfigurationStore.unmerge(this, ids.get(which));
                    renderConfiguredRoutes();
                    BusWidgetProvider.renderAll(this);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private Map<String, List<RouteConfig>> collectMerges(List<RouteConfig> configs) {
        Map<String, List<RouteConfig>> all = new LinkedHashMap<>();
        for (RouteConfig config : configs) {
            if (!config.mergeId.isEmpty()) {
                all.computeIfAbsent(config.mergeId, ignored -> new ArrayList<>()).add(config);
            }
        }
        all.entrySet().removeIf(entry -> entry.getValue().size() < 2);
        return all;
    }

    private String joinedNumbers(List<RouteConfig> configs) {
        if (configs == null) return "";
        StringBuilder text = new StringBuilder();
        for (RouteConfig config : configs) {
            if (text.length() > 0) text.append(", ");
            text.append(config.routeNumber);
        }
        return text.toString();
    }

    private void confirmRemove(RouteConfig config) {
        new AlertDialog.Builder(this)
                .setTitle("Удалить автобус " + config.routeNumber + "?")
                .setMessage(config.groupLabel + " · " + config.stopName + " → " + config.destination)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    ConfigurationStore.remove(this, config.id);
                    renderConfiguredRoutes();
                    AppPrefs.beginRefresh(this);
                    RefreshScheduler.refreshNow(this);
                })
                .show();
    }

    private void renderStatus() {
        long updated = AppPrefs.get(this).getLong(AppPrefs.KEY_UPDATED, 0L);
        String error = AppPrefs.get(this).getString(AppPrefs.KEY_ERROR, "");
        String crash = AppPrefs.get(this).getString(AppPrefs.KEY_CRASH, "");
        String details = AppPrefs.get(this).getString(AppPrefs.KEY_DIAGNOSTIC, "");
        boolean checking = AppPrefs.get(this).getBoolean(AppPrefs.KEY_CHECKING, false);
        if (checking) {
            status.setText("Обновляю прогнозы…");
        } else if (!crash.isEmpty()) {
            status.setText("Предыдущий запуск аварийно завершился: " + crash);
        } else if (!error.isEmpty()) {
            status.setText("Не удалось обновить: " + error);
        } else if (updated == 0L) {
            status.setText("Нажмите «Обновить сейчас».");
        } else {
            StringBuilder text = new StringBuilder("Обновлено ")
                    .append(DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(updated)));
            for (RouteConfig config : ConfigurationStore.load(this)) {
                long eta = AppPrefs.arrival(this, config.id);
                text.append("\n").append(config.routeNumber).append(" · ").append(config.groupLabel).append(": ");
                if (eta > System.currentTimeMillis()) {
                    long minutes = Math.max(1, (eta - System.currentTimeMillis() + 59_999L) / 60_000L);
                    text.append(minutes).append(" мин")
                            .append(AppPrefs.isLive(this, config.id) ? " · живое" : " · расписание");
                } else {
                    text.append("ожидаю следующий прогноз");
                }
            }
            status.setText(text.toString());
        }
        diagnostic.setText(details.isEmpty() ? "Отчёт появится после обновления." : details);
        BusWidgetProvider.renderAll(this);
    }

    private void copyReport() {
        String text = status.getText() + "\n\n" + diagnostic.getText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("Отчёт виджета автобусов", text));
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show();
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
