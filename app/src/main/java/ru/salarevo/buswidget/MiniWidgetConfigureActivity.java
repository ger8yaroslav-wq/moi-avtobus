package ru.salarevo.buswidget;

import android.app.Activity;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

public final class MiniWidgetConfigureActivity extends Activity {
    private int appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID;
    private final List<String> groupLabels = new ArrayList<>();
    private RadioGroup choices;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setResult(RESULT_CANCELED);
        setContentView(R.layout.activity_mini_widget_configure);

        appWidgetId = getIntent().getIntExtra(
                AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish();
            return;
        }

        choices = findViewById(R.id.mini_group_choices);
        Button save = findViewById(R.id.save_mini_widget);
        groupLabels.addAll(new LinkedHashSet<>(collectGroupLabels()));
        String current = AppPrefs.miniGroup(this, appWidgetId);

        for (int i = 0; i < groupLabels.size(); i++) {
            RadioButton choice = new RadioButton(this);
            choice.setId(View.generateViewId());
            choice.setText(groupLabels.get(i));
            choice.setTextColor(getColor(R.color.text_primary));
            choice.setTextSize(16);
            choice.setPadding(0, dp(8), 0, dp(8));
            choices.addView(choice, new RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT, RadioGroup.LayoutParams.WRAP_CONTENT));
            if (groupLabels.get(i).equals(current) || (current.isEmpty() && i == 0)) {
                choice.setChecked(true);
            }
        }

        if (groupLabels.isEmpty()) {
            TextView message = findViewById(R.id.mini_config_message);
            message.setText("Сначала добавьте хотя бы один автобус в приложении.");
            save.setEnabled(false);
        }

        save.setOnClickListener(v -> saveSelection());
        findViewById(R.id.cancel_mini_widget).setOnClickListener(v -> finish());
    }

    private List<String> collectGroupLabels() {
        List<String> labels = new ArrayList<>();
        for (RouteConfig config : ConfigurationStore.load(this)) labels.add(config.groupLabel);
        return labels;
    }

    private void saveSelection() {
        int selectedId = choices.getCheckedRadioButtonId();
        View selected = choices.findViewById(selectedId);
        int index = choices.indexOfChild(selected);
        if (index < 0 || index >= groupLabels.size()) return;

        AppPrefs.saveMiniGroup(this, appWidgetId, groupLabels.get(index));
        BusWidgetProvider.renderAll(this);
        Intent result = new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        setResult(RESULT_OK, result);
        finish();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
