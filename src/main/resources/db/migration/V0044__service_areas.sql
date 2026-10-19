-- Service areas: the places bonbon serves, curated by administrators (a residential block, an industrial park). A shop belongs to the
-- area that contains its map point (nearest centre when areas overlap), and its own delivery radius cannot exceed the area's limit.
-- An area is a circle, so everything stays a Haversine distance: no polygons.
CREATE TABLE service_areas (
    id                     UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   TEXT             NOT NULL CHECK (char_length(name) BETWEEN 2 AND 100),
    center_lat             DOUBLE PRECISION NOT NULL CHECK (center_lat BETWEEN 6 AND 24),
    center_lng             DOUBLE PRECISION NOT NULL CHECK (center_lng BETWEEN 102 AND 118),
    -- how far from the centre a shop may be to belong to the area
    radius_km              NUMERIC(5,1)     NOT NULL CHECK (radius_km >= 0.1 AND radius_km <= 50),
    -- the largest delivery radius a shop in this area may set
    max_delivery_radius_km NUMERIC(4,1)     NOT NULL CHECK (max_delivery_radius_km >= 0.1 AND max_delivery_radius_km <= 50),
    active                 BOOLEAN          NOT NULL DEFAULT true,
    created_by_id          UUID,
    created_at             TIMESTAMPTZ      NOT NULL,
    updated_at             TIMESTAMPTZ      NOT NULL
);
CREATE UNIQUE INDEX service_areas_name_key ON service_areas (lower(name));

-- Shops approved before areas existed have none; their radius is then held to the platform-wide limit as before.
ALTER TABLE vendors ADD COLUMN service_area_id UUID REFERENCES service_areas (id);
CREATE INDEX vendors_service_area_idx ON vendors (service_area_id) WHERE service_area_id IS NOT NULL;
