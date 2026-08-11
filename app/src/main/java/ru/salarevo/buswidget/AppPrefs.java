package ru.salarevo.buswidget;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    static final String PREFS = "bus_widget";
    static final String KEY_UPDATED = "updated_at";
    static final String KEY_ERROR = "last_error";
    static final String KEY_CRASH = "last_crash";
    static final String KEY_CHECKING = "checking";
    static final String KEY_DIAGNOSTIC = "diagnostic";
    private static final String KEY_MINI_GROUP_PREFIX = "mini_group_";

    private AppPrefs() {}

    static SharedPreferences get(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void removeLegacyKey(Context context) {
        get(context).edit().remove("mapkit_api_key").apply();
    }

    static void beginRefresh(Context context) {
        get(context).edit()
                .putBoolean(KEY_CHECKING, true)
                .remove(KEY_ERROR)
                .remove(KEY_CRASH)
                .apply();
    }

    static void saveArrivals(Context context, CityArrivalProvider.FetchResult result) {
        SharedPreferences.Editor editor = get(context).edit()
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .putBoolean(KEY_CHECKING, false)
                .putString(KEY_DIAGNOSTIC, result.diagnostic)
                .remove(KEY_ERROR)
                .remove(KEY_CRASH);
        for (RouteConfig config : ConfigurationStore.load(context)) {
            if (result.unresolvedIds.contains(config.id)) continue;
            CityArrivalProvider.Arrival arrival = result.arrivals.get(config.id);
            editor.putLong("eta_" + config.id, arrival == null ? 0L : arrival.epochMs);
            editor.putBoolean("live_" + config.id, arrival != null && arrival.live);
            editor.putLong("eta_next_" + config.id, arrival == null ? 0L : arrival.nextEpochMs);
            editor.putBoolean("live_next_" + config.id, arrival != null && arrival.nextLive);
        }
        editor.apply();
    }

    static long arrival(Context context, String configId) {
        return get(context).getLong("eta_" + configId, 0L);
    }

    static boolean isLive(Context context, String configId) {
        return get(context).getBoolean("live_" + configId, false);
    }

    static long nextArrival(Context context, String configId) {
        return get(context).getLong("eta_next_" + configId, 0L);
    }

    static boolean isNextLive(Context context, String configId) {
        return get(context).getBoolean("live_next_" + configId, false);
    }

    static String miniGroup(Context context, int appWidgetId) {
        return get(context).getString(KEY_MINI_GROUP_PREFIX + appWidgetId, "");
    }

    static void saveMiniGroup(Context context, int appWidgetId, String groupLabel) {
        get(context).edit().putString(KEY_MINI_GROUP_PREFIX + appWidgetId, groupLabel).apply();
    }

    static void removeMiniGroup(Context context, int appWidgetId) {
        get(context).edit().remove(KEY_MINI_GROUP_PREFIX + appWidgetId).apply();
    }

    static void removeArrival(Context context, String configId) {
        get(context).edit()
                .remove("eta_" + configId)
                .remove("live_" + configId)
                .remove("eta_next_" + configId)
                .remove("live_next_" + configId)
                .apply();
    }

    static void saveError(Context context, String message, String diagnostic) {
        get(context).edit()
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .putBoolean(KEY_CHECKING, false)
                .putString(KEY_ERROR, message)
                .putString(KEY_DIAGNOSTIC, diagnostic == null ? "" : diagnostic)
                .apply();
    }

    static void saveCrash(Context context, String message) {
        get(context).edit().putString(KEY_CRASH, message).commit();
    }
}
