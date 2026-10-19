-- A shop's address is now chosen as province + ward (codes from the administrative list the application ships), the detail the
-- owner types, and the point the owner confirmed on the map. The names stay in `province` and `ward`, as before.
ALTER TABLE vendors ADD COLUMN province_code INTEGER;
ALTER TABLE vendors ADD COLUMN ward_code INTEGER;
ALTER TABLE vendors ADD CONSTRAINT vendors_ward_needs_province CHECK (ward_code IS NULL OR province_code IS NOT NULL);
