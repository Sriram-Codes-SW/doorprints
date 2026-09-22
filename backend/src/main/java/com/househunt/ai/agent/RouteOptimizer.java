package com.househunt.ai.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure-Java route helpers for the visit planner: great-circle distance, a greedy nearest-neighbour ordering (good
 * enough for the 3-10 stops of a house-hunting afternoon; exact TSP is pointless at that size), and a walking-time
 * estimate.
 */
public final class RouteOptimizer {

    private static final double EARTH_RADIUS_M = 6_371_008.8;
    /** Average walking speed, metres per minute (~4.8 km/h). */
    static final double WALK_M_PER_MIN = 80.0;
    /** Streets are not straight lines: typical urban detour factor over crow-flies distance. */
    static final double DETOUR_FACTOR = 1.3;

    private RouteOptimizer() {
    }

    public record Point(String id, double lat, double lon) {
    }

    public record Leg(Point to, double meters, int walkMinutes) {
    }

    public static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    public static int estimateWalkMinutes(double meters) {
        if (meters <= 0) return 0;
        return (int) Math.ceil(meters * DETOUR_FACTOR / WALK_M_PER_MIN);
    }

    /** Greedy nearest neighbour from {@code start}; ties broken by input order so the result is deterministic. */
    public static List<Leg> nearestNeighbour(double startLat, double startLon, List<Point> stops) {
        var remaining = new ArrayList<>(stops);
        var legs = new ArrayList<Leg>(stops.size());
        double lat = startLat, lon = startLon;
        while (!remaining.isEmpty()) {
            int bestIdx = 0;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < remaining.size(); i++) {
                var p = remaining.get(i);
                double d = haversineMeters(lat, lon, p.lat(), p.lon());
                if (d < best) {
                    best = d;
                    bestIdx = i;
                }
            }
            var next = remaining.remove(bestIdx);
            legs.add(new Leg(next, best, estimateWalkMinutes(best)));
            lat = next.lat();
            lon = next.lon();
        }
        return legs;
    }

    /** Legs for a route in the given order (used to price the model's final order). */
    public static List<Leg> legsInOrder(double startLat, double startLon, List<Point> stops) {
        var legs = new ArrayList<Leg>(stops.size());
        double lat = startLat, lon = startLon;
        for (var p : stops) {
            double d = haversineMeters(lat, lon, p.lat(), p.lon());
            legs.add(new Leg(p, d, estimateWalkMinutes(d)));
            lat = p.lat();
            lon = p.lon();
        }
        return legs;
    }
}
