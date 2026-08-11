package ru.salarevo.buswidget;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BusWidgetProvider extends AppWidgetProvider {
    static final String ACTION_REFRESH = "ru.salarevo.buswidget.REFRESH";
    static final String ACTION_ARRIVAL_REACHED = "ru.salarevo.buswidget.ARRIVAL_REACHED";
    static final String ACTION_MINUTE_TICK = "ru.salarevo.buswidget.MINUTE_TICK";

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] appWidgetIds) {
        renderAll(context);
        RefreshScheduler.schedule(context);
        RefreshScheduler.refreshNow(context);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        if (ACTION_MINUTE_TICK.equals(intent.getAction())) {
            renderAll(context);
        } else if (ACTION_REFRESH.equals(intent.getAction())
                || ACTION_ARRIVAL_REACHED.equals(intent.getAction())) {
            AppPrefs.beginRefresh(context);
            renderAll(context);
            RefreshScheduler.refreshNow(context);
        }
    }

    static void renderAll(Context context) {
        AppWidgetManager manager = AppWidgetManager.getInstance(context);
        int darkCount = updateProvider(context, manager, BusWidgetProvider.class, false);
        int lightCount = updateProvider(context, manager, LightBusWidgetProvider.class, true);
        if (darkCount + lightCount == 0) {
            RefreshScheduler.cancelMinuteTick(context);
        } else {
            RefreshScheduler.scheduleMinuteTick(context);
        }
    }

    private static int updateProvider(Context context, AppWidgetManager manager,
                                      Class<? extends AppWidgetProvider> provider,
                                      boolean light) {
        int[] ids = manager.getAppWidgetIds(new ComponentName(context, provider));
        for (int id : ids) {
            Bundle options = manager.getAppWidgetOptions(id);
            int minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0);
            int minWidth = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0);
            boolean micro = minWidth > 0 && minWidth < 90;
            if (micro) {
                manager.updateAppWidget(id, buildMiniViews(context, light, id));
            } else {
                boolean compact = minHeight == 0 || minHeight < 90;
                manager.updateAppWidget(id, buildViews(context, light, compact, id));
            }
        }
        return ids.length;
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager,
                                          int appWidgetId, Bundle newOptions) {
        renderAll(context);
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        for (int appWidgetId : appWidgetIds) AppPrefs.removeMiniGroup(context, appWidgetId);
        super.onDeleted(context, appWidgetIds);
    }

    @Override
    public void onDisabled(Context context) {
        renderAll(context);
        super.onDisabled(context);
    }

    private static RemoteViews buildViews(Context context, boolean light,
                                          boolean compact, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                compact
                        ? (light ? R.layout.widget_bus_compact_light : R.layout.widget_bus_compact)
                        : (light ? R.layout.widget_bus_light : R.layout.widget_bus));
        bindWidgetRefresh(context, views);

        int primary = context.getColor(light
                ? R.color.widget_light_text_primary : R.color.widget_text_primary);
        int secondary = context.getColor(light
                ? R.color.widget_light_text_secondary : R.color.widget_text_secondary);
        if (!compact) views.setTextColor(R.id.widget_title, secondary);

        views.removeAllViews(R.id.dynamic_rows);
        List<RouteConfig> configs = ConfigurationStore.load(context);
        if (configs.isEmpty()) {
            RemoteViews empty = new RemoteViews(context.getPackageName(), R.layout.widget_empty_row);
            empty.setTextViewText(R.id.empty_message, compact ? "Нет автобусов" : "Откройте приложение и добавьте автобус");
            empty.setTextColor(R.id.empty_message, secondary);
            views.addView(R.id.dynamic_rows, empty);
        } else if (compact) {
            List<RouteConfig> selected = selectedGroupConfigs(context, configs, appWidgetId);
            RemoteViews header = new RemoteViews(context.getPackageName(), R.layout.widget_group_header_compact);
            header.setTextViewText(R.id.group_title, selected.get(0).groupLabel);
            header.setTextColor(R.id.group_title, primary);
            views.addView(R.id.dynamic_rows, header);
            views.addView(R.id.dynamic_rows,
                    routeRow(context, firstRow(selected), light));
        } else {
            Map<String, List<RouteConfig>> sections = new LinkedHashMap<>();
            for (RouteConfig config : configs) {
                sections.computeIfAbsent(config.groupLabel, ignored -> new ArrayList<>()).add(config);
            }
            for (Map.Entry<String, List<RouteConfig>> section : sections.entrySet()) {
                RemoteViews header = new RemoteViews(context.getPackageName(), R.layout.widget_group_header);
                header.setTextViewText(R.id.group_title, section.getKey());
                header.setTextColor(R.id.group_title, primary);
                views.addView(R.id.dynamic_rows, header);

                Map<String, List<RouteConfig>> rows = new LinkedHashMap<>();
                for (RouteConfig config : section.getValue()) {
                    String key = config.mergeId.isEmpty() ? "route:" + config.id : "merge:" + config.mergeId;
                    rows.computeIfAbsent(key, ignored -> new ArrayList<>()).add(config);
                }
                for (List<RouteConfig> rowConfigs : rows.values()) {
                    views.addView(R.id.dynamic_rows,
                            routeRow(context, rowConfigs, light));
                }
            }
        }

        return views;
    }

    private static List<RouteConfig> firstRow(List<RouteConfig> configs) {
        RouteConfig first = configs.get(0);
        List<RouteConfig> row = new ArrayList<>();
        for (RouteConfig config : configs) {
            boolean sameRow = first.mergeId.isEmpty()
                    ? config.id.equals(first.id)
                    : first.groupLabel.equals(config.groupLabel) && first.mergeId.equals(config.mergeId);
            if (sameRow) row.add(config);
        }
        return row;
    }

    private static RemoteViews buildMiniViews(Context context, boolean light, int appWidgetId) {
        RemoteViews views = new RemoteViews(context.getPackageName(),
                light ? R.layout.widget_mini_light : R.layout.widget_mini);
        bindMiniSettings(context, views, appWidgetId);

        int secondary = context.getColor(light
                ? R.color.widget_light_text_secondary : R.color.widget_text_secondary);

        List<RouteConfig> configs = ConfigurationStore.load(context);
        if (configs.isEmpty()) {
            views.setTextViewText(R.id.mini_eta_first, "—");
            views.setTextColor(R.id.mini_eta_first, secondary);
            views.setViewVisibility(R.id.mini_eta_separator, View.GONE);
            views.setViewVisibility(R.id.mini_eta_second, View.GONE);
            return views;
        }

        List<RouteConfig> selectedConfigs = selectedGroupConfigs(context, configs, appWidgetId);
        long now = System.currentTimeMillis();
        List<EtaPoint> arrivals = collectArrivals(context, selectedConfigs, now);
        if (arrivals.isEmpty()) {
            views.setTextViewText(R.id.mini_eta_first, "—");
            views.setTextColor(R.id.mini_eta_first, secondary);
            views.setViewVisibility(R.id.mini_eta_separator, View.GONE);
            views.setViewVisibility(R.id.mini_eta_second, View.GONE);
        } else {
            EtaPoint first = arrivals.get(0);
            views.setTextViewText(R.id.mini_eta_first,
                    String.valueOf(minutesUntil(first.epochMs, now)));
            views.setTextColor(R.id.mini_eta_first, etaColor(context, light, first.live));
            if (arrivals.size() > 1) {
                EtaPoint second = arrivals.get(1);
                views.setViewVisibility(R.id.mini_eta_separator, View.VISIBLE);
                views.setViewVisibility(R.id.mini_eta_second, View.VISIBLE);
                views.setTextColor(R.id.mini_eta_separator, secondary);
                views.setTextViewText(R.id.mini_eta_second,
                        String.valueOf(minutesUntil(second.epochMs, now)));
                views.setTextColor(R.id.mini_eta_second, etaColor(context, light, second.live));
            } else {
                views.setViewVisibility(R.id.mini_eta_separator, View.GONE);
                views.setViewVisibility(R.id.mini_eta_second, View.GONE);
            }
        }
        return views;
    }

    private static void bindWidgetRefresh(Context context, RemoteViews views) {
        Intent refreshIntent = new Intent(context, BusWidgetProvider.class).setAction(ACTION_REFRESH);
        PendingIntent refreshPending = PendingIntent.getBroadcast(
                context, 1, refreshIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, refreshPending);
    }

    private static void bindMiniSettings(Context context, RemoteViews views, int appWidgetId) {
        Intent settingsIntent = new Intent(context, MiniWidgetConfigureActivity.class)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                .setData(Uri.parse("mybus://mini-widget/" + appWidgetId));
        PendingIntent settingsPending = PendingIntent.getActivity(
                context, appWidgetId, settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        views.setOnClickPendingIntent(R.id.widget_root, settingsPending);
    }

    private static RemoteViews routeRow(Context context, List<RouteConfig> configs,
                                        boolean light) {
        RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_route_row);
        StringBuilder numbers = new StringBuilder();
        for (RouteConfig config : configs) {
            if (numbers.length() > 0) numbers.append(", ");
            numbers.append(config.routeNumber);
        }
        row.setTextViewText(R.id.route_number, numbers.toString());
        RouteConfig first = configs.get(0);
        try {
            int color = Color.parseColor(first.color);
            row.setTextColor(R.id.route_number, light ? darkenForLight(color) : color);
        } catch (Throwable ignored) {
            row.setTextColor(R.id.route_number, context.getColor(light
                    ? R.color.widget_light_text_primary : R.color.widget_text_primary));
        }

        int secondary = context.getColor(light
                ? R.color.widget_light_text_secondary : R.color.widget_text_secondary);
        row.setTextColor(R.id.route_empty, secondary);
        long now = System.currentTimeMillis();
        List<EtaPoint> arrivals = collectArrivals(context, configs, now);
        if (!arrivals.isEmpty()) {
            EtaPoint firstArrival = arrivals.get(0);
            row.setViewVisibility(R.id.route_eta_container, View.VISIBLE);
            row.setViewVisibility(R.id.route_empty, View.GONE);
            row.setTextViewText(R.id.route_eta_first,
                    String.valueOf(minutesUntil(firstArrival.epochMs, now)));
            row.setTextColor(R.id.route_eta_first, etaColor(context, light, firstArrival.live));
            row.setTextColor(R.id.route_eta_separator, secondary);
            row.setTextColor(R.id.route_eta_suffix, secondary);
            if (arrivals.size() > 1) {
                EtaPoint secondArrival = arrivals.get(1);
                row.setViewVisibility(R.id.route_eta_separator, View.VISIBLE);
                row.setViewVisibility(R.id.route_eta_second, View.VISIBLE);
                row.setTextViewText(R.id.route_eta_second,
                        String.valueOf(minutesUntil(secondArrival.epochMs, now)));
                row.setTextColor(R.id.route_eta_second,
                        etaColor(context, light, secondArrival.live));
            } else {
                row.setViewVisibility(R.id.route_eta_separator, View.GONE);
                row.setViewVisibility(R.id.route_eta_second, View.GONE);
            }
        } else {
            row.setViewVisibility(R.id.route_eta_container, View.GONE);
            row.setViewVisibility(R.id.route_empty, View.VISIBLE);
            row.setTextViewText(R.id.route_empty, "—");
        }
        return row;
    }

    private static List<RouteConfig> selectedGroupConfigs(Context context,
                                                           List<RouteConfig> configs,
                                                           int appWidgetId) {
        String selectedGroup = AppPrefs.miniGroup(context, appWidgetId);
        boolean exists = false;
        for (RouteConfig config : configs) {
            if (selectedGroup.equals(config.groupLabel)) {
                exists = true;
                break;
            }
        }
        if (!exists) selectedGroup = configs.get(0).groupLabel;
        List<RouteConfig> selected = new ArrayList<>();
        for (RouteConfig config : configs) {
            if (selectedGroup.equals(config.groupLabel)) selected.add(config);
        }
        return selected;
    }

    private static int etaColor(Context context, boolean light, boolean live) {
        return context.getColor(live
                ? (light ? R.color.widget_light_eta_green : R.color.widget_eta_green)
                : (light ? R.color.widget_light_text_secondary : R.color.widget_text_secondary));
    }

    private static List<EtaPoint> collectArrivals(Context context,
                                                  List<RouteConfig> configs, long now) {
        List<EtaPoint> result = new ArrayList<>();
        for (RouteConfig config : configs) {
            addArrival(result, AppPrefs.arrival(context, config.id),
                    AppPrefs.isLive(context, config.id), now);
            addArrival(result, AppPrefs.nextArrival(context, config.id),
                    AppPrefs.isNextLive(context, config.id), now);
        }
        result.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));
        List<EtaPoint> unique = new ArrayList<>();
        for (EtaPoint point : result) {
            if (unique.isEmpty()
                    || Math.abs(point.epochMs - unique.get(unique.size() - 1).epochMs) > 5_000L) {
                unique.add(point);
            } else if (point.live && !unique.get(unique.size() - 1).live) {
                unique.set(unique.size() - 1, point);
            }
        }
        return prioritizeEtaPoints(unique);
    }

    private static List<EtaPoint> prioritizeEtaPoints(List<EtaPoint> points) {
        List<EtaPoint> live = new ArrayList<>();
        List<EtaPoint> scheduled = new ArrayList<>();
        for (EtaPoint point : points) {
            (point.live ? live : scheduled).add(point);
        }
        live.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));
        scheduled.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));

        List<EtaPoint> result = new ArrayList<>();
        for (EtaPoint point : live) {
            result.add(point);
            if (result.size() == 2) return result;
        }
        long after = result.isEmpty() ? Long.MIN_VALUE : result.get(0).epochMs + 5_000L;
        for (EtaPoint point : scheduled) {
            if (point.epochMs <= after) continue;
            result.add(point);
            if (result.size() == 2) break;
        }
        return result;
    }

    private static void addArrival(List<EtaPoint> target, long epochMs, boolean live, long now) {
        if (epochMs > now + 5_000L) target.add(new EtaPoint(epochMs, live));
    }

    private static long minutesUntil(long epochMs, long now) {
        return Math.max(1L, (epochMs - now + 59_999L) / 60_000L);
    }

    private static final class EtaPoint {
        final long epochMs;
        final boolean live;

        EtaPoint(long epochMs, boolean live) {
            this.epochMs = epochMs;
            this.live = live;
        }
    }

    private static int darkenForLight(int color) {
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        double brightness = red * 0.299 + green * 0.587 + blue * 0.114;
        if (brightness < 145) return color;
        return Color.rgb((int) (red * 0.68), (int) (green * 0.68), (int) (blue * 0.68));
    }
}
