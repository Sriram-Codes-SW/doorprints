package com.househunt.ai.agent;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RouteOptimizerTest {

    @Test
    void haversineMatchesKnownDistance() {
        // 0.001 degree of latitude is ~111 m everywhere.
        assertThat(RouteOptimizer.haversineMeters(12.9716, 77.5946, 12.9726, 77.5946)).isCloseTo(111.2, within(0.5));
        assertThat(RouteOptimizer.haversineMeters(12.9716, 77.5946, 12.9716, 77.5946)).isZero();
    }

    @Test
    void walkMinutesUsesDetourAndSpeed() {
        assertThat(RouteOptimizer.estimateWalkMinutes(0)).isZero();
        // 800 m * 1.3 / 80 m/min = 13 min
        assertThat(RouteOptimizer.estimateWalkMinutes(800)).isEqualTo(13);
        assertThat(RouteOptimizer.estimateWalkMinutes(1)).isEqualTo(1);
    }

    @Test
    void nearestNeighbourVisitsClosestFirst() {
        // Points along a line north of the start, given out of order.
        var far = new RouteOptimizer.Point("far", 12.9760, 77.5946);
        var near = new RouteOptimizer.Point("near", 12.9720, 77.5946);
        var mid = new RouteOptimizer.Point("mid", 12.9740, 77.5946);
        var legs = RouteOptimizer.nearestNeighbour(12.9716, 77.5946, List.of(far, near, mid));
        assertThat(legs).extracting(l -> l.to().id()).containsExactly("near", "mid", "far");
        assertThat(legs.get(0).meters()).isCloseTo(44.5, within(1.0));
        assertThat(legs.get(1).meters()).isCloseTo(222.4, within(1.0));
    }

    @Test
    void nearestNeighbourIsNeverWorseThanInputOrderHere() {
        var a = new RouteOptimizer.Point("a", 12.9800, 77.6000);
        var b = new RouteOptimizer.Point("b", 12.9700, 77.5900);
        var c = new RouteOptimizer.Point("c", 12.9790, 77.6010);
        var nn = RouteOptimizer.nearestNeighbour(12.9700, 77.5890, List.of(a, b, c));
        var given = RouteOptimizer.legsInOrder(12.9700, 77.5890, List.of(a, b, c));
        double nnTotal = nn.stream().mapToDouble(RouteOptimizer.Leg::meters).sum();
        double givenTotal = given.stream().mapToDouble(RouteOptimizer.Leg::meters).sum();
        assertThat(nn).extracting(l -> l.to().id()).containsExactly("b", "a", "c");
        assertThat(nnTotal).isLessThan(givenTotal);
    }

    @Test
    void emptyInputGivesEmptyRoute() {
        assertThat(RouteOptimizer.nearestNeighbour(0, 0, List.of())).isEmpty();
    }
}
