package ru.salarevo.buswidget;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

final class RouteConfig {
    final String id;
    final String groupLabel;
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
    final String mergeId;

    RouteConfig(String id, String groupLabel, String stopId, String stopName,
                double latitude, double longitude, String routeNumber,
                String routeId, String pathId, String destination,
                String nextStop, String color) {
        this(id, groupLabel, stopId, stopName, latitude, longitude, routeNumber,
                routeId, pathId, destination, nextStop, color, "");
    }

    RouteConfig(String id, String groupLabel, String stopId, String stopName,
                double latitude, double longitude, String routeNumber,
                String routeId, String pathId, String destination,
                String nextStop, String color, String mergeId) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.groupLabel = clean(groupLabel, "Автобусы");
        this.stopId = clean(stopId, "");
        this.stopName = clean(stopName, "Остановка");
        this.latitude = latitude;
        this.longitude = longitude;
        this.routeNumber = clean(routeNumber, "?");
        this.routeId = clean(routeId, "");
        this.pathId = clean(pathId, "");
        this.destination = clean(destination, "Направление не указано");
        this.nextStop = clean(nextStop, "");
        this.color = normalizeColor(color);
        this.mergeId = clean(mergeId, "");
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("id", id)
                .put("groupLabel", groupLabel)
                .put("stopId", stopId)
                .put("stopName", stopName)
                .put("latitude", latitude)
                .put("longitude", longitude)
                .put("routeNumber", routeNumber)
                .put("routeId", routeId)
                .put("pathId", pathId)
                .put("destination", destination)
                .put("nextStop", nextStop)
                .put("color", color)
                .put("mergeId", mergeId);
    }

    static RouteConfig fromJson(JSONObject json) {
        return new RouteConfig(
                json.optString("id"),
                json.optString("groupLabel"),
                json.optString("stopId"),
                json.optString("stopName"),
                json.optDouble("latitude"),
                json.optDouble("longitude"),
                json.optString("routeNumber"),
                json.optString("routeId"),
                json.optString("pathId"),
                json.optString("destination"),
                json.optString("nextStop"),
                json.optString("color"),
                json.optString("mergeId"));
    }

    static RouteConfig create(String groupLabel, String stopId, String stopName,
                              double latitude, double longitude, String routeNumber,
                              String routeId, String pathId, String destination,
                              String nextStop, String color) {
        return new RouteConfig(UUID.randomUUID().toString(), groupLabel, stopId, stopName,
                latitude, longitude, routeNumber, routeId, pathId, destination, nextStop, color);
    }

    RouteConfig withMergeId(String value) {
        return new RouteConfig(id, groupLabel, stopId, stopName, latitude, longitude,
                routeNumber, routeId, pathId, destination, nextStop, color, value);
    }

    RouteConfig withGroupLabel(String value) {
        return new RouteConfig(id, value, stopId, stopName, latitude, longitude,
                routeNumber, routeId, pathId, destination, nextStop, color, mergeId);
    }

    private static String clean(String value, String fallback) {
        if (value == null || value.trim().isEmpty()) return fallback;
        return value.trim();
    }

    private static String normalizeColor(String value) {
        if (value != null && value.matches("#[0-9a-fA-F]{6}")) return value;
        return "#6D7CFF";
    }
}
