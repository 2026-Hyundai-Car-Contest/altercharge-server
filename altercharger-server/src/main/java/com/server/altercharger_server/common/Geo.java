package com.server.altercharger_server.common;

public final class Geo {
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
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }
}
