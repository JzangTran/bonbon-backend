-- Test data only (spring.flyway.locations in application-test.yaml): one service area around the fake geocoder's origin, so the
-- shops the tests open (about 300 m from it) belong to an area, as every new shop must.
INSERT INTO marketplace.service_areas (name, center_lat, center_lng, radius_km, max_delivery_radius_km, active, created_at, updated_at)
VALUES ('Khu thử nghiệm Hà Nội', 21.0285, 105.8542, 15, 3, true, now(), now());
