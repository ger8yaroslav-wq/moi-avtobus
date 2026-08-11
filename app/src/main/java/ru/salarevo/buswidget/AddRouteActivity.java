package ru.salarevo.buswidget;

import android.app.Activity;
import android.location.Address;
import android.location.Geocoder;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AddRouteActivity extends Activity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private EditText groupLabel;
    private EditText stopQuery;
    private TextView status;
    private LinearLayout results;
    private Button searchButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_route);
        groupLabel = findViewById(R.id.group_label);
        stopQuery = findViewById(R.id.stop_query);
        status = findViewById(R.id.search_status);
        results = findViewById(R.id.search_results);
        searchButton = findViewById(R.id.search_stop);
        groupLabel.setText(ConfigurationStore.lastGroupLabel(this));

        searchButton.setOnClickListener(v -> search());
        findViewById(R.id.cancel).setOnClickListener(v -> finish());
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    private void search() {
        String query = stopQuery.getText().toString().trim();
        if (query.isEmpty()) {
            stopQuery.setError("Введите название остановки");
            return;
        }
        if (query.length() < 3) {
            stopQuery.setError("Введите хотя бы три буквы названия остановки");
            return;
        }
        setBusy("Ищу все платформы остановки…");
        executor.execute(() -> {
            try {
                List<StopSearchClient.Candidate> candidates = StopSearchClient.searchByStop(query);
                if (!candidates.isEmpty()) {
                    runOnUiThread(() -> showCandidates(candidates));
                    return;
                }
            } catch (Throwable ignored) {
                // Ниже пробуем системный геокодер как резервный способ.
            }
            try {
                List<Address> addresses = new Geocoder(this, new Locale("ru", "RU"))
                        .getFromLocationName(query + ", Москва", 3);
                if (addresses == null || addresses.isEmpty()) {
                    throw new Exception("Остановка не найдена");
                }
                Address first = addresses.get(0);
                List<StopSearchClient.Candidate> candidates =
                        StopSearchClient.nearby(first.getLatitude(), first.getLongitude());
                runOnUiThread(() -> showCandidates(candidates));
            } catch (Throwable error) {
                runOnUiThread(() -> showError(error));
            }
        });
    }

    private void showCandidates(List<StopSearchClient.Candidate> candidates) {
        clearResults();
        searchButton.setEnabled(true);
        int shown = 0;
        String previousStop = null;
        for (StopSearchClient.Candidate candidate : candidates) {
            if (!candidate.stopName.equals(previousStop)) {
                TextView platform = new TextView(this);
                platform.setText("Остановка: " + candidate.stopName);
                platform.setTextColor(getColor(R.color.text_primary));
                platform.setTextSize(14);
                platform.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                headerParams.topMargin = dp(shown == 0 ? 10 : 18);
                platform.setLayoutParams(headerParams);
                results.addView(platform);
                previousStop = candidate.stopName;
            }
            Button button = resultButton(candidate.routeNumber + " → " + candidate.destination
                    + (candidate.nextStop.isEmpty() ? "" : "\nСледующая: " + candidate.nextStop));
            button.setOnClickListener(v -> saveCandidate(candidate));
            results.addView(button);
            shown++;
        }
        if (shown == 0) {
            status.setText("Не удалось найти маршруты этой остановки. Попробуйте убрать номер павильона или выхода из названия.");
        } else {
            status.setText("Найдены маршруты со всех ближайших платформ. Выберите автобус и направление:");
        }
    }

    private void saveCandidate(StopSearchClient.Candidate candidate) {
        String label = groupLabel.getText().toString().trim();
        if (label.isEmpty()) {
            groupLabel.setError("Напишите заголовок раздела, например «До метро»");
            return;
        }
        setBusy("Проверяю следующую остановку и сохраняю…");
        executor.execute(() -> {
            String nextStop = candidate.nextStop.isEmpty()
                    ? StopSearchClient.resolveNextStop(candidate) : candidate.nextStop;
            RouteConfig config = RouteConfig.create(label, candidate.stopId, candidate.stopName,
                    candidate.latitude, candidate.longitude, candidate.routeNumber,
                    candidate.routeId, candidate.pathId, candidate.destination,
                    nextStop, candidate.color);
            boolean saved = ConfigurationStore.add(this, config);
            runOnUiThread(() -> {
                if (!saved) {
                    showError(new Exception("Не удалось сохранить маршрут"));
                    return;
                }
                AppPrefs.beginRefresh(this);
                RefreshScheduler.refreshNow(this);
                Toast.makeText(this, "Маршрут добавлен", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                finish();
            });
        });
    }

    private Button resultButton(String text) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        button.setText(text);
        button.setTextSize(13);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(7);
        button.setLayoutParams(params);
        return button;
    }

    private void setBusy(String message) {
        searchButton.setEnabled(false);
        status.setText(message);
        clearResults();
    }

    private void showError(Throwable error) {
        searchButton.setEnabled(true);
        status.setText("Не удалось выполнить поиск: "
                + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
    }

    private void clearResults() {
        results.removeAllViews();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
