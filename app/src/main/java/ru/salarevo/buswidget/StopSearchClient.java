package ru.salarevo.buswidget;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class StopSearchClient {
    private static final String BASE = "https://api.moscowapp.mos.ru/v8.2/";

    static final class Suggestion {
        final String id;
        final String name;
        final String description;
        final double latitude;
        final double longitude;

        Suggestion(String id, String name, String description, double latitude, double longitude) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.latitude = latitude;
            this.longitude = longitude;
        }
    }

    static final class Candidate {
        final String stopId;
        final String stopName;
        final double latitude;
        final double longitude;
        final String routeNumber;
        final String routeId;
        final String pathId;
        final String destination;
        final String nextStop;
        final String color;

        Candidate(String stopId, String stopName, double latitude, double longitude,
                  String routeNumber, String routeId, String pathId,
                  String destination, String nextStop, String color) {
            this.stopId = stopId;
            this.stopName = stopName;
            this.latitude = latitude;
            this.longitude = longitude;
            this.routeNumber = routeNumber;
            this.routeId = routeId;
            this.pathId = pathId;
            this.destination = destination;
            this.nextStop = nextStop;
            this.color = color;
        }
    }

    private StopSearchClient() {}

    static List<Candidate> searchByStop(String query) throws Exception {
        List<Suggestion> suggestions = new ArrayList<>();
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Map<String, Suggestion> centers = new LinkedHashMap<>();
        Exception lastError = null;
        try {
            suggestions = searchSuggestions(query);
        } catch (Exception error) {
            lastError = error;
        }

        // Сначала читаем каждую платформу, которую вернул поиск, по её точному id.
        for (Suggestion suggestion : suggestions) {
            if (!matchesStopFamily(suggestion.name, query)) continue;
            String centerKey = String.format(Locale.US, "%.3f,%.3f",
                    suggestion.latitude, suggestion.longitude);
            centers.putIfAbsent(centerKey, suggestion);
            try {
                for (Candidate candidate : candidatesAtExactStop(suggestion)) {
                    if (matchesStopFamily(candidate.stopName, query)) {
                        addUnique(result, seen, candidate);
                    }
                }
            } catch (Exception error) {
                lastError = error;
            }
        }

        // Затем сканируем транспортный узел на высокой детализации. На низком zoom
        // сервер часто возвращает только часть павильонов и маршрутов.
        int scannedCenters = 0;
        for (Suggestion center : centers.values()) {
            try {
                for (Candidate candidate : nearbyExpanded(center.latitude, center.longitude)) {
                    if (matchesStopFamily(candidate.stopName, query)) {
                        addUnique(result, seen, candidate);
                    }
                }
            } catch (Exception error) {
                lastError = error;
            }
            if (++scannedCenters >= 1) break;
        }

        // Страховка для исходного транспортного узла пользователя: эти маршруты
        // проверяются по полным карточкам и не зависят от неполного stop/near.
        if (looksLikeSalarevo(query)) {
            addKnownRoute(result, seen, query, "272",
                    "2be3975b-c3d1-428b-8e91-5417682081f5");
            addKnownRoute(result, seen, query, "298",
                    "199cc3c0-3383-4c83-88ab-db5894ffcba3");
            addKnownRoute(result, seen, query, "420",
                    "e543b0ee-0672-41cb-b695-95b8d67110be");
        }

        if (result.isEmpty() && lastError != null) throw lastError;
        result.sort(Comparator.comparing((Candidate value) -> normalize(value.stopName))
                .thenComparing(value -> normalize(value.routeNumber))
                .thenComparing(value -> normalize(value.destination)));
        return result;
    }

    static List<Candidate> nearby(double latitude, double longitude) throws Exception {
        return nearbyExpanded(latitude, longitude);
    }

    private static List<Suggestion> searchSuggestions(String query) throws Exception {
        Object root = requestSuggestions(query);
        List<Suggestion> result = new ArrayList<>();
        collectSuggestions(root, result, new HashSet<>(), 0);
        return result.size() > 60 ? new ArrayList<>(result.subList(0, 60)) : result;
    }

    private static Object requestSuggestions(String query) throws Exception {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
        String url = BASE + "suggest?sessionId=" + UUID.randomUUID()
                + "&query=" + encoded + "&types=public_transport";
        CityArrivalProvider.HttpResult response = CityArrivalProvider.get(url);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("Поиск остановок: HTTP " + response.code);
        }
        return response.body.trim().startsWith("[")
                ? new JSONArray(response.body) : new JSONObject(response.body);
    }

    private static List<Candidate> candidatesAtExactStop(Suggestion suggestion) throws Exception {
        List<Candidate> result = new ArrayList<>();
        if (suggestion.id.isEmpty()) return result;
        CityArrivalProvider.HttpResult response = CityArrivalProvider.get(
                BASE + "stop_v2/" + URLEncoder.encode(suggestion.id, StandardCharsets.UTF_8.name()));
        if (response.code < 200 || response.code >= 300) return result;
        JSONObject root = new JSONObject(response.body);
        JSONObject stop = findObjectWithArray(root, 0, "routes", "routePath");
        if (stop == null) stop = root;
        String stopId = readableId(stop.opt("id"));
        String stopName = stop.optString("name", suggestion.name);
        double[] coordinates = coordinatesOf(stop);
        double latitude = coordinates == null ? suggestion.latitude : coordinates[0];
        double longitude = coordinates == null ? suggestion.longitude : coordinates[1];
        JSONArray routes = stop.optJSONArray("routes");
        if (routes == null) routes = stop.optJSONArray("routePath");
        if (routes == null) return result;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < routes.length(); i++) {
            JSONObject route = routes.optJSONObject(i);
            if (route == null) continue;
            String number = route.optString("number", "").trim();
            String destination = route.optString("lastStopName", "").trim();
            if (number.isEmpty() || destination.isEmpty()) continue;
            String id = readableId(route.opt("id"));
            String routeId = readableId(route.opt("routeId"));
            if (routeId.isEmpty()) routeId = id;
            String pathId = readableId(route.opt("routePathId"));
            String color = readColor(route.opt("numberColor"));
            if ("#6D7CFF".equals(color)) color = readColor(route.opt("color"));
            addUnique(result, seen, new Candidate(stopId, stopName, latitude, longitude,
                    number, routeId, pathId, destination, "", color));
        }
        return result;
    }

    private static List<Candidate> nearbyExpanded(double latitude, double longitude) throws Exception {
        // Центр на zoom 17 плюс крест из точек zoom 18 покрывает крупный узел,
        // не смешивая его с остановками на расстоянии нескольких километров.
        double[][] points = new double[][]{
                {latitude, longitude, 17},
                {latitude + 0.0025, longitude, 18},
                {latitude - 0.0025, longitude, 18},
                {latitude, longitude + 0.0040, 18},
                {latitude, longitude - 0.0040, 18}
        };
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception lastError = null;
        for (double[] point : points) {
            try {
                for (Candidate candidate : nearbyPoint(point[0], point[1], (int) point[2])) {
                    if (distanceMeters(latitude, longitude,
                            candidate.latitude, candidate.longitude) <= 1_000) {
                        addUnique(result, seen, candidate);
                    }
                }
            } catch (Exception error) {
                lastError = error;
            }
        }
        if (result.isEmpty() && lastError != null) throw lastError;
        return result;
    }

    private static List<Candidate> nearbyPoint(double latitude, double longitude, int zoom)
            throws Exception {
        String url = BASE + "stop/near?p="
                + String.format(Locale.US, "%.6f%%2C%.6f", latitude, longitude)
                + "&zoom=" + zoom;
        CityArrivalProvider.HttpResult response = CityArrivalProvider.get(url);
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("Платформы рядом: HTTP " + response.code);
        }
        JSONArray stops = CityArrivalProvider.parseArrayResponse(response.body);
        List<Candidate> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < stops.length(); i++) {
            JSONObject stop = stops.optJSONObject(i);
            if (stop == null) continue;
            double stopLat = stop.optDouble("lat", latitude);
            double stopLon = stop.optDouble("lon", longitude);
            if (distanceMeters(latitude, longitude, stopLat, stopLon) > 750) continue;
            String stopId = readableId(stop.opt("id"));
            String stopName = stop.optString("name", "Остановка");
            JSONArray paths = stop.optJSONArray("routePath");
            if (paths == null) paths = stop.optJSONArray("routes");
            if (paths == null) continue;
            for (int j = 0; j < paths.length(); j++) {
                JSONObject path = paths.optJSONObject(j);
                if (path == null) continue;
                String number = path.optString("number", "").trim();
                String destination = path.optString("lastStopName", "").trim();
                if (number.isEmpty() || destination.isEmpty()) continue;
                addUnique(result, seen, new Candidate(stopId, stopName, stopLat, stopLon, number,
                        readableId(path.opt("id")), readableId(path.opt("routePathId")),
                        destination, "", readColor(path.opt("color"))));
            }
        }
        return result;
    }

    private static void addKnownRoute(List<Candidate> out, Set<String> seen, String query,
                                      String number, String routeId) {
        try {
            JSONObject route = loadRoute(routeId);
            addCandidatesFromRoute(route, number, query, out, seen);
        } catch (Throwable ignored) {
            // Основные платформы уже собраны выше; это только дополнительная страховка.
        }
    }

    private static void addCandidatesFromRoute(JSONObject route, String expectedNumber,
                                               String query, List<Candidate> out,
                                               Set<String> seen) {
        String number = route.optString("number", expectedNumber).trim();
        if (!expectedNumber.equalsIgnoreCase(number)) return;
        String routeId = readableId(route.opt("id"));
        String color = readColor(route.opt("numberColor"));
        JSONArray directions = route.optJSONArray("directions");
        if (directions == null) return;
        for (int i = 0; i < directions.length(); i++) {
            JSONObject direction = directions.optJSONObject(i);
            if (direction == null) continue;
            String destination = direction.optString("toStop",
                    direction.optString("lastStopName", "Направление"));
            JSONArray paths = direction.optJSONArray("directionPaths");
            if (paths == null) paths = direction.optJSONArray("paths");
            if (paths == null) continue;
            for (int j = 0; j < paths.length(); j++) {
                JSONObject path = paths.optJSONObject(j);
                if (path == null) continue;
                String pathId = readableId(path.opt("id"));
                JSONArray stops = path.optJSONArray("stops");
                if (stops == null || stops.length() < 2) continue;
                for (int k = 0; k < stops.length() - 1; k++) {
                    JSONObject stop = stops.optJSONObject(k);
                    JSONObject next = stops.optJSONObject(k + 1);
                    if (stop == null || next == null) continue;
                    String stopName = stop.optString("name", "Остановка");
                    if (!matchesStopFamily(stopName, query)) continue;
                    double[] coordinates = coordinatesOf(stop);
                    if (coordinates == null) continue;
                    addUnique(out, seen, new Candidate(readableId(stop.opt("id")), stopName,
                            coordinates[0], coordinates[1], number, routeId, pathId,
                            destination, next.optString("name", ""), color));
                }
            }
        }
    }

    static String resolveNextStop(Candidate candidate) {
        if (!candidate.nextStop.isEmpty()) return candidate.nextStop;
        if (candidate.routeId.isEmpty()) return "";
        try {
            JSONObject route = loadRoute(candidate.routeId);
            JSONArray directions = route.optJSONArray("directions");
            if (directions == null) return "";
            for (int i = 0; i < directions.length(); i++) {
                JSONObject direction = directions.optJSONObject(i);
                if (direction == null) continue;
                JSONArray paths = direction.optJSONArray("directionPaths");
                if (paths == null) paths = direction.optJSONArray("paths");
                if (paths == null) continue;
                for (int j = 0; j < paths.length(); j++) {
                    JSONObject path = paths.optJSONObject(j);
                    if (path == null) continue;
                    String actualPathId = readableId(path.opt("id"));
                    if (!candidate.pathId.isEmpty() && !candidate.pathId.equals(actualPathId)) continue;
                    JSONArray stops = path.optJSONArray("stops");
                    if (stops == null) continue;
                    for (int k = 0; k < stops.length() - 1; k++) {
                        JSONObject stop = stops.optJSONObject(k);
                        JSONObject next = stops.optJSONObject(k + 1);
                        if (stop != null && next != null
                                && candidate.stopId.equals(readableId(stop.opt("id")))) {
                            return next.optString("name", "");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // Конечная остановка всё равно сохраняет выбранное направление.
        }
        return "";
    }

    private static JSONObject loadRoute(String routeId) throws Exception {
        CityArrivalProvider.HttpResult response = CityArrivalProvider.get(
                BASE + "route_v3/" + URLEncoder.encode(routeId, StandardCharsets.UTF_8.name()));
        if (response.code < 200 || response.code >= 300) {
            throw new Exception("Маршрут: HTTP " + response.code);
        }
        JSONObject root = new JSONObject(response.body);
        JSONObject route = findObjectWithArray(root, 0, "directions");
        return route == null ? root : route;
    }

    private static void collectSuggestions(Object value, List<Suggestion> out,
                                           Set<String> seen, int depth) {
        if (value == null || depth > 8 || out.size() >= 80) return;
        if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectSuggestions(array.opt(i), out, seen, depth + 1);
            }
            return;
        }
        if (!(value instanceof JSONObject object)) return;
        String name = object.optString("name", object.optString("title", "")).trim();
        double[] coordinates = coordinatesOf(object);
        if (!name.isEmpty() && coordinates != null) {
            String key = readableId(object.opt("id")) + "|" + name + "|"
                    + String.format(Locale.US, "%.5f,%.5f", coordinates[0], coordinates[1]);
            if (seen.add(key)) {
                out.add(new Suggestion(readableId(object.opt("id")), name,
                        object.optString("description", object.optString("subtitle", "")),
                        coordinates[0], coordinates[1]));
            }
        }
        for (String key : new String[]{"data", "result", "response", "items", "groupItems", "suggestions"}) {
            collectSuggestions(object.opt(key), out, seen, depth + 1);
        }
    }

    private static boolean matchesStopFamily(String stopName, String query) {
        List<String> wanted = meaningfulTokens(query);
        List<String> actual = meaningfulTokens(stopName);
        if (wanted.isEmpty()) return normalize(stopName).contains(normalize(query));
        for (String wantedToken : wanted) {
            boolean matched = false;
            for (String actualToken : actual) {
                if (actualToken.contains(wantedToken) || wantedToken.contains(actualToken)) {
                    matched = true;
                    break;
                }
                int threshold = Math.min(wantedToken.length(), actualToken.length()) >= 7 ? 2 : 1;
                if (levenshtein(wantedToken, actualToken) <= threshold) {
                    matched = true;
                    break;
                }
            }
            if (!matched) return false;
        }
        return true;
    }

    private static List<String> meaningfulTokens(String value) {
        List<String> result = new ArrayList<>();
        for (String token : normalize(value).split(" ")) {
            if (token.length() < 3 || token.matches(".*\\d.*")) continue;
            if (token.equals("метро") || token.equals("станция") || token.equals("остановка")
                    || token.equals("павильон") || token.equals("выход") || token.equals("автобус")) {
                continue;
            }
            result.add(token);
        }
        return result;
    }

    private static boolean looksLikeSalarevo(String query) {
        for (String token : meaningfulTokens(query)) {
            if (levenshtein(token, "саларьево") <= 2) return true;
        }
        return false;
    }

    private static int levenshtein(String left, String right) {
        int[] previous = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) previous[j] = j;
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1];
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int cost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            previous = current;
        }
        return previous[right.length()];
    }

    private static String normalize(String value) {
        return value.toLowerCase(new Locale("ru", "RU"))
                .replace('ё', 'е')
                .replaceAll("[^а-яa-z0-9]+", " ")
                .trim();
    }

    private static void addUnique(List<Candidate> out, Set<String> seen, Candidate candidate) {
        String stopKey = candidate.stopId.isEmpty()
                ? candidate.stopName + "|" + String.format(Locale.US, "%.5f,%.5f",
                candidate.latitude, candidate.longitude) : candidate.stopId;
        String unique = stopKey + "|" + candidate.pathId + "|" + candidate.routeNumber
                + "|" + normalize(candidate.destination);
        if (seen.add(unique)) out.add(candidate);
    }

    private static double[] coordinatesOf(JSONObject object) {
        double lat = object.optDouble("lat", Double.NaN);
        double lon = object.optDouble("lon", Double.NaN);
        JSONObject coordinates = object.optJSONObject("coordinates");
        if (coordinates != null) {
            lat = coordinates.optDouble("latitude", coordinates.optDouble("lat", lat));
            lon = coordinates.optDouble("longitude", coordinates.optDouble("lon", lon));
        }
        Object raw = object.opt("coordinates");
        if (raw instanceof String text) {
            String[] parts = text.split(",");
            if (parts.length >= 2) {
                try {
                    double first = Double.parseDouble(parts[0].trim());
                    double second = Double.parseDouble(parts[1].trim());
                    lat = Math.max(first, second);
                    lon = Math.min(first, second);
                } catch (NumberFormatException ignored) {
                    // Такой объект будет пропущен ниже.
                }
            }
        } else if (raw instanceof JSONArray array && array.length() >= 2) {
            double first = array.optDouble(0, Double.NaN);
            double second = array.optDouble(1, Double.NaN);
            if (!Double.isNaN(first) && !Double.isNaN(second)) {
                lat = Math.max(first, second);
                lon = Math.min(first, second);
            }
        }
        if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180) {
            return null;
        }
        return new double[]{lat, lon};
    }

    private static JSONObject findObjectWithArray(Object value, int depth, String... keys) {
        if (value == null || depth > 8) return null;
        if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject found = findObjectWithArray(array.opt(i), depth + 1, keys);
                if (found != null) return found;
            }
            return null;
        }
        if (!(value instanceof JSONObject object)) return null;
        for (String key : keys) if (object.optJSONArray(key) != null) return object;
        Iterator<String> iterator = object.keys();
        while (iterator.hasNext()) {
            JSONObject found = findObjectWithArray(object.opt(iterator.next()), depth + 1, keys);
            if (found != null) return found;
        }
        return null;
    }

    private static String readColor(Object value) {
        if (value instanceof String text && text.matches("#[0-9a-fA-F]{6}")) return text;
        if (value instanceof JSONObject object) {
            for (String key : new String[]{"value", "hex", "stringValue"}) {
                String text = object.optString(key, "");
                if (text.matches("#[0-9a-fA-F]{6}")) return text;
            }
        }
        return "#6D7CFF";
    }

    private static String readableId(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONObject object) {
            return object.optString("value", object.optString("stringValue", object.toString()));
        }
        return String.valueOf(value);
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
