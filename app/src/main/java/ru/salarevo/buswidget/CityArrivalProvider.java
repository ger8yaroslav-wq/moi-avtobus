package ru.salarevo.buswidget;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ConnectException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

final class CityArrivalProvider {
    private static final String BASE = "https://api.moscowapp.mos.ru/v8.2/";
    private static final String API_HOST = "api.moscowapp.mos.ru";
    private static volatile String fallbackIp = "";
    private static volatile long fallbackIpExpiresAt;

    static final class Arrival {
        final long epochMs;
        final boolean live;
        final long nextEpochMs;
        final boolean nextLive;

        Arrival(long epochMs, boolean live, long nextEpochMs, boolean nextLive) {
            this.epochMs = epochMs;
            this.live = live;
            this.nextEpochMs = nextEpochMs;
            this.nextLive = nextLive;
        }
    }

    private static final class Forecast {
        final long epochMs;
        final boolean live;

        Forecast(long epochMs, boolean live) {
            this.epochMs = epochMs;
            this.live = live;
        }
    }

    private static final class PathMatch {
        final JSONObject path;
        final String stopId;

        PathMatch(JSONObject path, String stopId) {
            this.path = path;
            this.stopId = stopId;
        }
    }

    static final class FetchResult {
        final Map<String, Arrival> arrivals;
        final String diagnostic;
        final Set<String> unresolvedIds;

        FetchResult(Map<String, Arrival> arrivals, String diagnostic, Set<String> unresolvedIds) {
            this.arrivals = arrivals;
            this.diagnostic = diagnostic;
            this.unresolvedIds = unresolvedIds;
        }
    }

    static final class FetchException extends Exception {
        final String diagnostic;

        FetchException(String message, String diagnostic) {
            super(message);
            this.diagnostic = diagnostic;
        }

        FetchException(String message, String diagnostic, Throwable cause) {
            super(message, cause);
            this.diagnostic = diagnostic;
        }
    }

    private CityArrivalProvider() {}

    static FetchResult fetch(Context context) throws FetchException {
        List<RouteConfig> configs = ConfigurationStore.load(context);
        if (configs.isEmpty()) {
            return new FetchResult(new HashMap<>(), "Маршруты не выбраны", Collections.emptySet());
        }

        long started = System.currentTimeMillis();
        StringBuilder diagnostic = new StringBuilder("Источник: городской транспорт Москвы\n");
        Map<String, Arrival> arrivals = new HashMap<>();
        Set<String> unresolvedIds = new HashSet<>();
        Map<String, List<RouteConfig>> areas = groupByArea(configs);
        diagnostic.append("Точек запроса: ").append(areas.size()).append('\n');
        int failedAreas = 0;
        Throwable lastFailure = null;

        for (List<RouteConfig> areaConfigs : areas.values()) {
            try {
                RouteConfig anchor = areaConfigs.get(0);
                String url = BASE + "stop/near?p="
                        + String.format(Locale.US, "%.6f%%2C%.6f", anchor.latitude, anchor.longitude)
                        + "&zoom=18";
                HttpResult response = get(url);
                diagnostic.append("HTTP ").append(response.code).append(" · ")
                        .append(anchor.stopName).append('\n');
                if (response.code < 200 || response.code >= 300) {
                    throw new FetchException("Сервер транспорта ответил HTTP " + response.code,
                            diagnostic + shorten(response.body, 1000));
                }
                JSONArray stops = parseArrayResponse(response.body);
                // Forecast values are offsets from the response time, not from request start.
                long responseReceivedAt = System.currentTimeMillis();
                for (RouteConfig config : areaConfigs) {
                    PathMatch match = findPath(stops, config);
                    if (match == null) {
                        diagnostic.append(config.routeNumber).append(": направление не найдено\n");
                        continue;
                    }
                    Arrival arrival = fetchArrival(config, match, responseReceivedAt, diagnostic);
                    if (arrival == null) {
                        diagnostic.append(config.routeNumber).append(": времени сейчас нет\n");
                    } else {
                        arrivals.put(config.id, arrival);
                        diagnostic.append(config.routeNumber).append(" → ").append(config.destination)
                                .append(": ")
                                .append(describeArrival(arrival, responseReceivedAt)).append('\n');
                    }
                }
            } catch (Throwable error) {
                failedAreas++;
                lastFailure = error;
                for (RouteConfig config : areaConfigs) unresolvedIds.add(config.id);
                diagnostic.append("Точка временно недоступна: ")
                        .append(error.getClass().getSimpleName()).append(": ")
                        .append(error.getMessage() == null ? "ошибка соединения" : error.getMessage())
                        .append('\n');
            }
        }

        diagnostic.append("Получено времён: ").append(arrivals.size())
                .append(" из ").append(configs.size()).append('\n');
        if (failedAreas > 0 && failedAreas < areas.size()) {
            diagnostic.append("Частично обновлено: ")
                    .append(areas.size() - failedAreas).append(" из ").append(areas.size())
                    .append(" точек\n");
        }
        diagnostic.append("Время запроса: ").append(System.currentTimeMillis() - started).append(" мс");

        if (arrivals.isEmpty()) {
            if (failedAreas > 0) {
                throw new FetchException(friendlyNetworkError(lastFailure), diagnostic.toString(), lastFailure);
            } else {
                throw new FetchException("Маршруты найдены, но актуального времени сейчас нет",
                        diagnostic.toString());
            }
        }
        return new FetchResult(arrivals, diagnostic.toString(), unresolvedIds);
    }

    private static Map<String, List<RouteConfig>> groupByArea(List<RouteConfig> configs) {
        Map<String, List<RouteConfig>> result = new LinkedHashMap<>();
        for (RouteConfig config : configs) {
            String key = String.format(Locale.US, "%.3f,%.3f", config.latitude, config.longitude);
            result.computeIfAbsent(key, ignored -> new ArrayList<>()).add(config);
        }
        return result;
    }

    private static PathMatch findPath(JSONArray stops, RouteConfig config) {
        PathMatch fallback = null;
        for (int i = 0; i < stops.length(); i++) {
            JSONObject stop = stops.optJSONObject(i);
            if (stop == null) continue;
            boolean exactStop = config.stopId.equals(stop.optString("id"));
            boolean closeStop = normalize(config.stopName).equals(normalize(stop.optString("name")))
                    && distanceMeters(config.latitude, config.longitude,
                    stop.optDouble("lat"), stop.optDouble("lon")) < 180;
            if (!exactStop && !closeStop) continue;

            JSONArray paths = stop.optJSONArray("routePath");
            if (paths == null) paths = stop.optJSONArray("routes");
            if (paths == null) continue;
            for (int j = 0; j < paths.length(); j++) {
                JSONObject path = paths.optJSONObject(j);
                if (path == null || !config.routeNumber.equals(path.optString("number"))) continue;
                if (!config.pathId.isEmpty() && config.pathId.equals(path.optString("routePathId"))) {
                    return new PathMatch(path, stop.optString("id", config.stopId));
                }
                String actualDestination = normalize(path.optString("lastStopName"));
                String wantedDestination = normalize(config.destination);
                if (actualDestination.contains(wantedDestination)
                        || wantedDestination.contains(actualDestination)) {
                    PathMatch match = new PathMatch(path, stop.optString("id", config.stopId));
                    if (exactStop) return match;
                    fallback = match;
                }
            }
        }
        return fallback;
    }

    private static Arrival fetchArrival(RouteConfig config, PathMatch match, long nearResponseAt,
                                        StringBuilder diagnostic) {
        JSONObject path = match.path;
        String stopId = match.stopId.isEmpty() ? config.stopId : match.stopId;
        List<Forecast> embedded = embeddedForecasts(path, nearResponseAt);
        String directionId = path.optString("routePathId", config.pathId);
        List<Forecast> forecasts = new ArrayList<>();

        if (!directionId.isEmpty() && !stopId.isEmpty()) {
            try {
                HttpResult response = get(BASE + "direction/forecasts?directionId=" + directionId
                        + "&stopId=" + stopId);
                if (response.code >= 200 && response.code < 300) {
                    forecasts.addAll(parseDirectionForecasts(response.body, System.currentTimeMillis()));
                } else {
                    diagnostic.append(config.routeNumber).append(": forecasts HTTP ")
                            .append(response.code).append('\n');
                }
            } catch (Throwable error) {
                diagnostic.append(config.routeNumber).append(": forecasts ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
        }

        forecasts = mergeForecasts(forecasts, embedded);
        if (forecasts.size() < 2 && !directionId.isEmpty() && !stopId.isEmpty()) {
            try {
                String date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(new Date());
                HttpResult response = get(BASE + "direction/schedule?directionId=" + directionId
                        + "&stopId=" + stopId + "&date=" + date);
                if (response.code >= 200 && response.code < 300) {
                    forecasts = mergeForecasts(forecasts,
                            parseScheduleForecasts(response.body, System.currentTimeMillis()));
                } else {
                    diagnostic.append(config.routeNumber).append(": schedule HTTP ")
                            .append(response.code).append('\n');
                }
            } catch (Throwable error) {
                diagnostic.append(config.routeNumber).append(": schedule ")
                        .append(error.getClass().getSimpleName()).append('\n');
            }
        }
        return toArrival(forecasts);
    }

    private static List<Forecast> embeddedForecasts(JSONObject path, long now) {
        JSONArray external = path.optJSONArray("externalForecastTime");
        if (external == null) external = path.optJSONArray("externalForecast");
        List<Forecast> forecasts = new ArrayList<>();
        if (external != null) {
            for (int i = 0; i < external.length(); i++) {
                JSONObject item = external.optJSONObject(i);
                if (item == null) continue;
                long seconds = item.optLong("time", 0L);
                if (seconds <= 0L || seconds > 21_600L) continue;
                forecasts.add(new Forecast(now + seconds * 1000L,
                        isLiveForecast(item) || isLiveForecast(path)));
            }
        }
        JSONArray secondsArray = path.optJSONArray("timeArrivalSecond");
        if (secondsArray != null) {
            boolean live = isLiveForecast(path);
            for (int i = 0; i < secondsArray.length(); i++) {
                long seconds = secondsArray.optLong(i, 0L);
                if (seconds > 0L && seconds <= 21_600L) {
                    forecasts.add(new Forecast(now + seconds * 1000L, live));
                }
            }
        }
        return mergeForecasts(forecasts, Collections.emptyList());
    }

    private static List<Forecast> parseDirectionForecasts(String body, long now) throws Exception {
        JSONArray array = parseArrayResponse(body);
        List<Forecast> forecasts = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            long seconds = item.optLong("time", 0L);
            if (seconds <= 0L || seconds > 21_600L) continue;
            forecasts.add(new Forecast(now + seconds * 1000L, isLiveForecast(item)));
        }
        return mergeForecasts(forecasts, Collections.emptyList());
    }

    private static List<Forecast> parseScheduleForecasts(String body, long nowMs) throws Exception {
        JSONArray groups = parseArrayResponse(body);
        Calendar now = Calendar.getInstance();
        now.setTimeInMillis(nowMs);
        List<Forecast> forecasts = new ArrayList<>();
        for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) continue;
            int hour = group.optInt("hour", -1);
            JSONArray minutes = group.optJSONArray("minutes");
            if (hour < 0 || minutes == null) continue;
            for (int j = 0; j < minutes.length(); j++) {
                int minute = minutes.optInt(j, -1);
                if (minute < 0) continue;
                Calendar arrival = (Calendar) now.clone();
                arrival.set(Calendar.HOUR_OF_DAY, hour);
                arrival.set(Calendar.MINUTE, minute);
                arrival.set(Calendar.SECOND, 0);
                arrival.set(Calendar.MILLISECOND, 0);
                long delta = arrival.getTimeInMillis() - nowMs;
                if (delta > 5_000L && delta <= 21_600_000L) {
                    forecasts.add(new Forecast(arrival.getTimeInMillis(), false));
                }
            }
        }
        return mergeForecasts(forecasts, Collections.emptyList());
    }

    private static boolean isLiveForecast(JSONObject item) {
        if (item.optBoolean("isOnline", false) || item.optBoolean("online", false)) return true;
        Object telemetry = item.opt("byTelemetry");
        if (telemetry instanceof Boolean) return (Boolean) telemetry;
        if (telemetry instanceof Number) return ((Number) telemetry).intValue() == 1;
        return "1".equals(String.valueOf(telemetry))
                || "true".equalsIgnoreCase(String.valueOf(telemetry));
    }

    private static List<Forecast> mergeForecasts(List<Forecast> first, List<Forecast> second) {
        List<Forecast> all = new ArrayList<>(first);
        all.addAll(second);
        all.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));
        List<Forecast> unique = new ArrayList<>();
        for (Forecast forecast : all) {
            if (unique.isEmpty()
                    || Math.abs(forecast.epochMs - unique.get(unique.size() - 1).epochMs) > 75_000L) {
                unique.add(forecast);
            } else if (forecast.live && !unique.get(unique.size() - 1).live) {
                unique.set(unique.size() - 1, forecast);
            }
        }
        return unique;
    }

    private static Arrival toArrival(List<Forecast> forecasts) {
        if (forecasts.isEmpty()) return null;
        List<Forecast> prioritized = prioritizeForecasts(forecasts);
        Forecast first = prioritized.get(0);
        Forecast second = prioritized.size() > 1 ? prioritized.get(1) : null;
        return new Arrival(first.epochMs, first.live,
                second == null ? 0L : second.epochMs,
                second != null && second.live);
    }

    private static List<Forecast> prioritizeForecasts(List<Forecast> forecasts) {
        List<Forecast> live = new ArrayList<>();
        List<Forecast> scheduled = new ArrayList<>();
        for (Forecast forecast : forecasts) {
            (forecast.live ? live : scheduled).add(forecast);
        }
        live.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));
        scheduled.sort((left, right) -> Long.compare(left.epochMs, right.epochMs));

        List<Forecast> result = new ArrayList<>();
        for (Forecast forecast : live) {
            result.add(forecast);
            if (result.size() == 2) return result;
        }
        long after = result.isEmpty() ? Long.MIN_VALUE : result.get(0).epochMs + 75_000L;
        for (Forecast forecast : scheduled) {
            if (forecast.epochMs <= after) continue;
            result.add(forecast);
            if (result.size() == 2) break;
        }
        return result;
    }

    private static String describeArrival(Arrival arrival, long now) {
        long first = Math.max(1, (arrival.epochMs - now + 59_999L) / 60_000L);
        StringBuilder text = new StringBuilder().append(first).append(" мин · ")
                .append(arrival.live ? "живое" : "расписание");
        if (arrival.nextEpochMs > 0L) {
            long second = Math.max(1, (arrival.nextEpochMs - now + 59_999L) / 60_000L);
            text.append("; следующий ").append(second).append(" мин · ")
                    .append(arrival.nextLive ? "живое" : "расписание");
        }
        return text.toString();
    }

    static HttpResult get(String url) throws Exception {
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                return getWithDnsFallback(url);
            } catch (ConnectException | SocketTimeoutException transientError) {
                last = transientError;
                if (attempt == 0) Thread.sleep(650L);
            }
        }
        throw last;
    }

    private static HttpResult getWithDnsFallback(String url) throws Exception {
        try {
            return getOnce(url, null);
        } catch (UnknownHostException first) {
            try {
                Thread.sleep(350L);
                return getOnce(url, null);
            } catch (UnknownHostException second) {
                URL original = new URL(url);
                if (!API_HOST.equalsIgnoreCase(original.getHost())) throw second;
                return getOnce(url, resolveApiHostWithDoh());
            }
        }
    }

    private static HttpResult getOnce(String url, String directIp) throws Exception {
        URL original = new URL(url);
        HttpURLConnection connection;
        if (directIp == null || directIp.isEmpty()) {
            connection = (HttpURLConnection) original.openConnection();
        } else {
            URL direct = new URL(original.getProtocol(), directIp, original.getPort(), original.getFile());
            HttpsURLConnection secure = (HttpsURLConnection) direct.openConnection();
            SSLSocketFactory delegate = HttpsURLConnection.getDefaultSSLSocketFactory();
            secure.setSSLSocketFactory(new SniSocketFactory(delegate, API_HOST));
            HostnameVerifier verifier = HttpsURLConnection.getDefaultHostnameVerifier();
            secure.setHostnameVerifier((ignored, session) -> verifier.verify(API_HOST, session));
            secure.setRequestProperty("Host", API_HOST);
            connection = secure;
        }
        try {
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(25_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("locale", "ru");
            connection.setRequestProperty("User-Agent", "MyBusWidget/3.8 Android");
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            return new HttpResult(code, stream == null ? "" : readFully(stream));
        } finally {
            connection.disconnect();
        }
    }

    private static String resolveApiHostWithDoh() throws Exception {
        long now = System.currentTimeMillis();
        if (!fallbackIp.isEmpty() && fallbackIpExpiresAt > now) return fallbackIp;
        URL url = new URL("https://1.1.1.1/dns-query?name=" + API_HOST + "&type=A");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(8_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("Accept", "application/dns-json");
            connection.setRequestProperty("User-Agent", "MyBusWidget/3.8 Android");
            if (connection.getResponseCode() != 200) {
                throw new UnknownHostException("Резервный DNS недоступен");
            }
            JSONObject response = new JSONObject(readFully(connection.getInputStream()));
            JSONArray answers = response.optJSONArray("Answer");
            if (answers != null) {
                for (int i = 0; i < answers.length(); i++) {
                    JSONObject answer = answers.optJSONObject(i);
                    if (answer == null || answer.optInt("type") != 1) continue;
                    String value = answer.optString("data", "");
                    if (value.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) {
                        fallbackIp = value;
                        long ttl = Math.max(60L, answer.optLong("TTL", 300L));
                        fallbackIpExpiresAt = now + Math.min(ttl, 3_600L) * 1_000L;
                        return value;
                    }
                }
            }
            throw new UnknownHostException("Резервный DNS не вернул адрес сервера");
        } finally {
            connection.disconnect();
        }
    }

    private static final class SniSocketFactory extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        private final String tlsHost;

        SniSocketFactory(SSLSocketFactory delegate, String tlsHost) {
            this.delegate = delegate;
            this.tlsHost = tlsHost;
        }

        @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

        private Socket wrap(Socket socket, int port) throws java.io.IOException {
            return delegate.createSocket(socket, tlsHost, port, true);
        }

        @Override
        public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                throws java.io.IOException {
            return delegate.createSocket(socket, tlsHost, port, autoClose);
        }

        @Override
        public Socket createSocket(String host, int port) throws java.io.IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 15_000);
            return wrap(socket, port);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localAddress, int localPort)
                throws java.io.IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(host, port), 15_000);
            return wrap(socket, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port) throws java.io.IOException {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress(address, port), 15_000);
            return wrap(socket, port);
        }

        @Override
        public Socket createSocket(InetAddress address, int port,
                                   InetAddress localAddress, int localPort) throws java.io.IOException {
            Socket socket = new Socket();
            socket.bind(new InetSocketAddress(localAddress, localPort));
            socket.connect(new InetSocketAddress(address, port), 15_000);
            return wrap(socket, port);
        }
    }

    static JSONArray parseArrayResponse(String body) throws Exception {
        String trimmed = body == null ? "" : body.trim();
        if (trimmed.startsWith("[")) return new JSONArray(trimmed);
        JSONObject root = new JSONObject(trimmed);
        for (String key : new String[]{"stops", "data", "result", "response", "items"}) {
            Object nested = root.opt(key);
            if (nested instanceof JSONArray) return (JSONArray) nested;
            if (nested instanceof JSONObject) {
                JSONObject object = (JSONObject) nested;
                for (String nestedKey : new String[]{"stops", "items", "data"}) {
                    JSONArray array = object.optJSONArray(nestedKey);
                    if (array != null) return array;
                }
            }
        }
        throw new Exception("В ответе нет списка");
    }

    static final class HttpResult {
        final int code;
        final String body;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body;
        }
    }

    private static String readFully(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > 2_000_000) throw new Exception("Ответ сервера слишком большой");
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replace('ё', 'е').replaceAll("[^а-яa-z0-9]", "");
    }

    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static String friendlyNetworkError(Throwable error) {
        if (error == null) return "Ошибка соединения";
        String type = error.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        if (type.contains("timeout")) return "Сервер транспорта не ответил вовремя";
        if (type.contains("unknownhost")) return "Не удалось найти сервер транспорта — проверьте интернет";
        if (type.contains("connect")) return "Не удалось подключиться к серверу транспорта — повторим позже";
        if (type.contains("ssl")) return "Не удалось установить защищённое соединение";
        return error.getMessage() == null ? "Ошибка соединения" : error.getMessage();
    }

    private static String shorten(String value, int max) {
        if (value == null || value.isEmpty()) return "(пустой ответ)";
        return value.length() <= max ? value : value.substring(0, max) + "\n…";
    }
}
