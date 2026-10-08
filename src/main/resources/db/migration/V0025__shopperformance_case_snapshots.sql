-- A case keeps the order number and the customer's delivery name it was filed with, so reading a case never needs the
-- order tables (the shopperformance module owns order_cases and reads orders only through the order module).
ALTER TABLE order_cases ADD COLUMN order_number BIGINT;
ALTER TABLE order_cases ADD COLUMN customer_name TEXT;
UPDATE order_cases c SET order_number = o.number, customer_name = o.delivery_name FROM orders o WHERE o.id = c.order_id;
ALTER TABLE order_cases ALTER COLUMN order_number SET NOT NULL;
ALTER TABLE order_cases ALTER COLUMN customer_name SET NOT NULL;
