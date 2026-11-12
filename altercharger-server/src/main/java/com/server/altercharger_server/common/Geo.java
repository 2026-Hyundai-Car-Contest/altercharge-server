package com.server.altercharger_server.common;

public final class Geo {
    private static final double EARTH_RADIUS_METERS = 6_371_000;
    private static final double BOUND_PADDING_DEGREES = 1e-9;

    private Geo() {}

    public static boolean validCoordinates(double lat, double lng) {
        return Double.isFinite(lat) && lat >= -90 && lat <= 90
                && Double.isFinite(lng) && lng >= -180 && lng <= 180;
    }

    public static double distanceMeters(double lat, double lng, double targetLat, double targetLng) {
        double deltaLat = Math.toRadians(targetLat - lat);
        double deltaLng = Math.toRadians(targetLng - lng);
        double a = Math.pow(Math.sin(deltaLat / 2), 2)
                + Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(targetLat))
                * Math.pow(Math.sin(deltaLng / 2), 2);
        a = Math.max(0, Math.min(1, a));
        return EARTH_RADIUS_METERS * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Conservative database candidates only; callers still apply the exact Haversine radius. */
    public static BoundingBox boundingBox(double lat, double lng, double radiusMeters) {
        double angularRadius = radiusMeters / EARTH_RADIUS_METERS;
        double latitudeSpan = Math.toDegrees(angularRadius) + BOUND_PADDING_DEGREES;
        double south = Math.max(-90, lat - latitudeSpan);
        double north = Math.min(90, lat + latitudeSpan);
        if (south == -90 || north == 90) return new BoundingBox(south, north, -180, 180, false);

        double longitudeSpan = Math.toDegrees(Math.asin(Math.min(1,
                Math.sin(angularRadius) / Math.cos(Math.toRadians(lat))))) + BOUND_PADDING_DEGREES;
        double west = normalizeLongitude(lng - longitudeSpan);
        double east = normalizeLongitude(lng + longitudeSpan);
        return new BoundingBox(south, north, west, east, west > east);
    }

    private static double normalizeLongitude(double longitude) {
        double normalized = (longitude + 180) % 360;
        if (normalized < 0) normalized += 360;
        return normalized - 180;
    }

    public record BoundingBox(double south, double north, double west, double east, boolean wrapsLongitude) {}
}
