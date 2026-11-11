package com.bonbon.backend.common.geo;

import java.util.List;

/** Roads between points. Implementations throw {@link GeocodingUnavailableException} when the provider cannot answer. */
public interface RoutePlanner {

    record Point(double lat, double lng) {
    }

    record Route(double distanceKm, double durationMinutes) {
    }

    /** {@code order} lists the stops by their index in the request, in the order to visit them; the first stop stays first. */
    record Tour(List<Integer> order, double distanceKm, double durationMinutes) {
    }

    /** The best way there first, then any alternatives. */
    List<Route> routes(Point from, Point to);

    /** The order of visiting that shortens the trip, starting at the first stop and not coming back. */
    Tour optimize(List<Point> stops);
}
