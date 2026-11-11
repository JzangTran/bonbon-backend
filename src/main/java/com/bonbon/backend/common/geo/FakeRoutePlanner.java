package com.bonbon.backend.common.geo;

import java.util.List;
import java.util.stream.IntStream;

/** Straight lines at 20 km/h, in the order given: for dev and tests without a routing server. */
class FakeRoutePlanner implements RoutePlanner {

    @Override
    public List<Route> routes(Point from, Point to) {
        double km = GeoDistance.haversineKm(from.lat(), from.lng(), to.lat(), to.lng());
        return List.of(new Route(km, km * 3));
    }

    @Override
    public Tour optimize(List<Point> stops) {
        double km = IntStream.range(1, stops.size()).mapToDouble(i -> routes(stops.get(i - 1), stops.get(i)).get(0).distanceKm()).sum();
        return new Tour(IntStream.range(0, stops.size()).boxed().toList(), km, km * 3);
    }
}
