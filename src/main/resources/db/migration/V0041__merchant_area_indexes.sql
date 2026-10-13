-- Shops in the area (ShopCatalogService.shopsInArea): an index-served latitude band, as wide as the largest approved
-- delivery radius, before the per-shop bounding box and Haversine check. Load test (bonbon-backend#149): with 20 000
-- shops the area query went from a 12.5 ms sequential scan to 0.6 ms.
CREATE INDEX vendors_area_lat_idx ON vendors (lat)
    WHERE status = 'APPROVED' AND lng IS NOT NULL AND delivery_radius_km IS NOT NULL;

-- max(delivery_radius_km) over approved shops, read by the same query, from the index end.
CREATE INDEX vendors_approved_radius_idx ON vendors (delivery_radius_km) WHERE status = 'APPROVED';
