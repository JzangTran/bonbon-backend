package com.bonbon.backend.common.geo;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** OSRM on OpenStreetMap roads: {@code /route} for the way there, {@code /trip} for the order of several stops. */
class OsrmRoutePlanner implements RoutePlanner {

    private static final Logger log = LoggerFactory.getLogger(OsrmRoutePlanner.class);
    private static final int OUTBOUND_PER_MINUTE = 60;

    private final RestClient http;
    private final RateLimiter rateLimiter;

    OsrmRoutePlanner(RestClient http, RateLimiter rateLimiter) {
        this.http = http;
        this.rateLimiter = rateLimiter;
    }

    @Override
    public List<Route> routes(Point from, Point to) {
        RouteResponse response = call(() -> http.get().uri("/route/v1/driving/" + coordinates(List.of(from, to)) + "?overview=false&alternatives=true")
                .retrieve().body(RouteResponse.class));
        return response == null || response.routes() == null ? List.of()
                : response.routes().stream().map(r -> new Route(kilometres(r.distance()), minutes(r.duration()))).toList();
    }

    @Override
    public Tour optimize(List<Point> stops) {
        TripResponse response = call(() -> http.get()
                .uri("/trip/v1/driving/" + coordinates(stops) + "?overview=false&roundtrip=false&source=first&destination=any")
                .retrieve().body(TripResponse.class));
        if (response == null || response.trips() == null || response.trips().isEmpty() || response.waypoints() == null) {
            throw new GeocodingUnavailableException();
        }
        List<Integer> order = IntStream.range(0, response.waypoints().size()).boxed()
                .sorted(Comparator.comparingInt(i -> response.waypoints().get(i).waypointIndex())).toList();
        Leg trip = response.trips().get(0);
        return new Tour(order, kilometres(trip.distance()), minutes(trip.duration()));
    }

    private static String coordinates(List<Point> points) {
        return points.stream().map(p -> p.lng() + "," + p.lat()).collect(Collectors.joining(";"));
    }

    private static double kilometres(double metres) {
        return Math.round(metres / 100) / 10.0;
    }

    private static double minutes(double seconds) {
        return Math.round(seconds / 6) / 10.0;
    }

    private <T> T call(Supplier<T> request) {
        if (!rateLimiter.tryAcquire("osrm:outbound", OUTBOUND_PER_MINUTE, Duration.ofMinutes(1))) {
            log.warn("OSRM outbound limit reached");
            throw new GeocodingUnavailableException();
        }
        try {
            return request.get();
        } catch (RestClientException e) {
            log.warn("OSRM request failed: {}", e.getMostSpecificCause().getMessage());
            throw new GeocodingUnavailableException();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RouteResponse(List<Leg> routes) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TripResponse(List<Leg> trips, List<Waypoint> waypoints) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Leg(double distance, double duration) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Waypoint(@JsonProperty("waypoint_index") int waypointIndex) {
    }
}
