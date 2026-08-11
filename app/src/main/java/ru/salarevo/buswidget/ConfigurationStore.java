package ru.salarevo.buswidget;

import android.content.Context;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ConfigurationStore {
    private static final String KEY_CONFIGS = "route_configs_v3";

    private ConfigurationStore() {}

    static List<RouteConfig> load(Context context) {
        String raw = AppPrefs.get(context).getString(KEY_CONFIGS, "");
        if (raw.isEmpty()) {
            List<RouteConfig> defaults = defaults();
            save(context, defaults);
            return defaults;
        }
        try {
            JSONArray array = new JSONArray(raw);
            List<RouteConfig> result = new ArrayList<>();
            boolean migrated = false;
            for (int i = 0; i < array.length(); i++) {
                if (array.optJSONObject(i) != null) {
                    RouteConfig config = RouteConfig.fromJson(array.optJSONObject(i));
                    RouteConfig updated = withoutDefaultMetroSuffix(config);
                    result.add(updated);
                    migrated |= updated != config;
                }
            }
            if (migrated) save(context, result);
            return result;
        } catch (Throwable ignored) {
            return defaults();
        }
    }

    static boolean save(Context context, List<RouteConfig> configs) {
        normalizeMerges(configs);
        JSONArray array = new JSONArray();
        try {
            for (int i = 0; i < configs.size(); i++) {
                array.put(configs.get(i).toJson());
            }
            return AppPrefs.get(context).edit().putString(KEY_CONFIGS, array.toString()).commit();
        } catch (Throwable ignored) {
            return false;
        }
    }

    static boolean add(Context context, RouteConfig config) {
        List<RouteConfig> configs = new ArrayList<>(load(context));
        configs.add(config);
        return save(context, configs);
    }

    static boolean merge(Context context, List<String> ids) {
        Set<String> selected = new HashSet<>(ids);
        if (selected.size() < 2) return false;
        String mergeId = UUID.randomUUID().toString();
        List<RouteConfig> configs = new ArrayList<>(load(context));
        int changed = 0;
        for (int i = 0; i < configs.size(); i++) {
            RouteConfig config = configs.get(i);
            if (selected.contains(config.id)) {
                configs.set(i, config.withMergeId(mergeId));
                changed++;
            }
        }
        return changed >= 2 && save(context, configs);
    }

    static void unmerge(Context context, String mergeId) {
        List<RouteConfig> configs = new ArrayList<>(load(context));
        for (int i = 0; i < configs.size(); i++) {
            RouteConfig config = configs.get(i);
            if (config.mergeId.equals(mergeId)) configs.set(i, config.withMergeId(""));
        }
        save(context, configs);
    }

    static void remove(Context context, String id) {
        List<RouteConfig> configs = new ArrayList<>(load(context));
        configs.removeIf(config -> config.id.equals(id));
        save(context, configs);
        AppPrefs.removeArrival(context, id);
    }

    static String lastGroupLabel(Context context) {
        List<RouteConfig> configs = load(context);
        return configs.isEmpty() ? "До метро" : configs.get(configs.size() - 1).groupLabel;
    }

    private static List<RouteConfig> defaults() {
        List<RouteConfig> result = new ArrayList<>();
        result.add(new RouteConfig("default-salarevskaya13-420", "Саларьевская, 13",
                "965fc124-18c9-44ae-b8fd-015a4d367451",
                "Саларьевская улица, 13", 55.614720, 37.411650, "420",
                "e543b0ee-0672-41cb-b695-95b8d67110be", "707d6ade-497a-4846-b46c-e00d1c8a7e56",
                "Метро Саларьево", "Саларьево Парк", "#8F6BFF", "default-merge-salarevskaya13"));
        result.add(new RouteConfig("default-salarevskaya13-272", "Саларьевская, 13",
                "965fc124-18c9-44ae-b8fd-015a4d367451",
                "Саларьевская улица, 13", 55.614720, 37.411650, "272",
                "2be3975b-c3d1-428b-8e91-5417682081f5", "4319d82e-27bd-42ae-9d0e-8bea8423799f",
                "Метро Саларьево", "Саларьево Парк", "#21A8F6", "default-merge-salarevskaya13"));

        result.add(new RouteConfig("default-salarevo-park-420", "Саларьево Парк",
                "6f964c90-842a-4e83-9080-ff34c98f14f5",
                "Саларьево Парк", 55.617280, 37.415577, "420",
                "e543b0ee-0672-41cb-b695-95b8d67110be", "707d6ade-497a-4846-b46c-e00d1c8a7e56",
                "Метро Саларьево", "Метро Саларьево", "#8F6BFF", "default-merge-salarevo-park"));
        result.add(new RouteConfig("default-salarevo-park-272", "Саларьево Парк",
                "6f964c90-842a-4e83-9080-ff34c98f14f5",
                "Саларьево Парк", 55.617280, 37.415577, "272",
                "2be3975b-c3d1-428b-8e91-5417682081f5", "4319d82e-27bd-42ae-9d0e-8bea8423799f",
                "Метро Саларьево", "Метро Саларьево", "#21A8F6", "default-merge-salarevo-park"));

        // The city API may rotate platform IDs. For this stop we deliberately match by the
        // official name, coordinates and pathId; the actual stop ID from stop/near is then used
        // for forecasts and the timetable.
        result.add(new RouteConfig("default-bolshoe-ponizove-420", "Большое Понизовье", "",
                "Улица Большое Понизовье", 55.6087336, 37.4070829, "420",
                "e543b0ee-0672-41cb-b695-95b8d67110be", "707d6ade-497a-4846-b46c-e00d1c8a7e56",
                "Метро Саларьево", "Саларьевская улица, 13", "#8F6BFF", "default-merge-bolshoe-ponizove"));
        result.add(new RouteConfig("default-bolshoe-ponizove-272", "Большое Понизовье", "",
                "Улица Большое Понизовье", 55.6087336, 37.4070829, "272",
                "2be3975b-c3d1-428b-8e91-5417682081f5", "4319d82e-27bd-42ae-9d0e-8bea8423799f",
                "Метро Саларьево", "Саларьевская улица, 13", "#21A8F6", "default-merge-bolshoe-ponizove"));
        return Collections.unmodifiableList(result);
    }

    private static RouteConfig withoutDefaultMetroSuffix(RouteConfig config) {
        if (config.id.startsWith("default-salarevskaya13-")
                && config.groupLabel.equals("Саларьевская, 13 → метро")) {
            return config.withGroupLabel("Саларьевская, 13");
        }
        if (config.id.startsWith("default-salarevo-park-")
                && config.groupLabel.equals("Саларьево Парк → метро")) {
            return config.withGroupLabel("Саларьево Парк");
        }
        if (config.id.startsWith("default-bolshoe-ponizove-")
                && config.groupLabel.equals("Большое Понизовье → метро")) {
            return config.withGroupLabel("Большое Понизовье");
        }
        return config;
    }

    private static void normalizeMerges(List<RouteConfig> configs) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<>();
        for (RouteConfig config : configs) {
            if (!config.mergeId.isEmpty()) counts.merge(config.mergeId, 1, Integer::sum);
        }
        for (int i = 0; i < configs.size(); i++) {
            RouteConfig config = configs.get(i);
            if (!config.mergeId.isEmpty() && counts.getOrDefault(config.mergeId, 0) < 2) {
                configs.set(i, config.withMergeId(""));
            }
        }
    }
}
