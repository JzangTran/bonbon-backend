-- Revenue and best-seller reports read the delivered orders of one shop over a time range (flows/statistics/).
CREATE INDEX orders_vendor_status_finished_idx ON orders (vendor_id, status, finished_at);
