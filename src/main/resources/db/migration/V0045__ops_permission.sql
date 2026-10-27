-- The infrastructure area (ops.*) is gated by its own permission, separate from the content and data administration (console.*).
INSERT INTO role_permissions (role, permission) VALUES ('ADMIN', 'ops:read') ON CONFLICT DO NOTHING;
