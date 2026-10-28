-- One schema per module (docs/reference/architecture/project-structure.md): the tables a module owns live in its schema, so ownership
-- is visible in the database and a module could later move to a database of its own by taking its schema along. Only the tables
-- move; data, indexes, constraints, owned sequences and triggers move with them. Foreign keys across schemas stay for now: in one
-- database they still protect integrity, and are only worth replacing by checks or events the day a module actually leaves.
-- The connections set search_path to every module schema (spring.datasource.hikari.connection-init-sql), so SQL keeps using bare
-- table names; JPA entities name their schema. Functions and extensions (unaccent, the append-only and admin-role triggers) stay in
-- public, which stays last on the path. Flyway's own history stays in public.

CREATE SCHEMA IF NOT EXISTS identity;
CREATE SCHEMA IF NOT EXISTS marketplace;
CREATE SCHEMA IF NOT EXISTS ordering;
CREATE SCHEMA IF NOT EXISTS payments;
CREATE SCHEMA IF NOT EXISTS communication;
CREATE SCHEMA IF NOT EXISTS support;
CREATE SCHEMA IF NOT EXISTS operations;

-- Identity & Access: accounts, sign-in, roles, addresses, legal documents and consent
ALTER TABLE users SET SCHEMA identity;
ALTER TABLE user_roles SET SCHEMA identity;
ALTER TABLE role_permissions SET SCHEMA identity;
ALTER TABLE user_auth_providers SET SCHEMA identity;
ALTER TABLE refresh_tokens SET SCHEMA identity;
ALTER TABLE email_verification_tokens SET SCHEMA identity;
ALTER TABLE password_reset_tokens SET SCHEMA identity;
ALTER TABLE addresses SET SCHEMA identity;
ALTER TABLE legal_documents SET SCHEMA identity;
ALTER TABLE legal_document_log SET SCHEMA identity;
ALTER TABLE consent_records SET SCHEMA identity;

-- Marketplace: shops, their applications and review, menus, categories, service areas
ALTER TABLE vendors SET SCHEMA marketplace;
ALTER TABLE vendor_identity SET SCHEMA marketplace;
ALTER TABLE vendor_tax_info SET SCHEMA marketplace;
ALTER TABLE vendor_invoice_emails SET SCHEMA marketplace;
ALTER TABLE vendor_payout_accounts SET SCHEMA marketplace;
ALTER TABLE vendor_review_decisions SET SCHEMA marketplace;
ALTER TABLE identity_document_access_log SET SCHEMA marketplace;
ALTER TABLE merchant_suspensions SET SCHEMA marketplace;
ALTER TABLE shop_opening_hours SET SCHEMA marketplace;
ALTER TABLE menu_sections SET SCHEMA marketplace;
ALTER TABLE menu_items SET SCHEMA marketplace;
ALTER TABLE option_groups SET SCHEMA marketplace;
ALTER TABLE options SET SCHEMA marketplace;
ALTER TABLE menu_item_option_groups SET SCHEMA marketplace;
ALTER TABLE categories SET SCHEMA marketplace;
ALTER TABLE dish_category_cases SET SCHEMA marketplace;
ALTER TABLE service_areas SET SCHEMA marketplace;

-- Ordering: orders and their history, reviews, cases, faults and penalties
ALTER TABLE orders SET SCHEMA ordering;
ALTER TABLE order_items SET SCHEMA ordering;
ALTER TABLE order_item_options SET SCHEMA ordering;
ALTER TABLE order_status_history SET SCHEMA ordering;
ALTER TABLE reviews SET SCHEMA ordering;
ALTER TABLE review_responses SET SCHEMA ordering;
ALTER TABLE review_moderation_log SET SCHEMA ordering;
ALTER TABLE order_cases SET SCHEMA ordering;
ALTER TABLE order_case_items SET SCHEMA ordering;
ALTER TABLE order_case_photos SET SCHEMA ordering;
ALTER TABLE order_case_log SET SCHEMA ordering;
ALTER TABLE shop_fault_events SET SCHEMA ordering;
ALTER TABLE shop_penalties SET SCHEMA ordering;
ALTER TABLE shop_performance_state SET SCHEMA ordering;

-- Payments & Settlement: payments, refunds, commission, the ledger
ALTER TABLE payments SET SCHEMA payments;
ALTER TABLE payment_attempts SET SCHEMA payments;
ALTER TABLE payment_refunds SET SCHEMA payments;
ALTER TABLE payment_refund_log SET SCHEMA payments;
ALTER TABLE ledger_entries SET SCHEMA payments;
ALTER TABLE settlement_case_holds SET SCHEMA payments;
ALTER TABLE commission_rate_history SET SCHEMA payments;
ALTER TABLE commission_statements SET SCHEMA payments;
ALTER TABLE commission_notices SET SCHEMA payments;

-- Communication: chat and notifications
ALTER TABLE conversations SET SCHEMA communication;
ALTER TABLE messages SET SCHEMA communication;
ALTER TABLE notifications SET SCHEMA communication;
ALTER TABLE notification_preferences SET SCHEMA communication;
ALTER TABLE notification_quiet_hours SET SCHEMA communication;
ALTER TABLE push_devices SET SCHEMA communication;

-- Support: help center, tickets, the public contact form, the lookup audit
ALTER TABLE help_articles SET SCHEMA support;
ALTER TABLE help_article_keywords SET SCHEMA support;
ALTER TABLE support_tickets SET SCHEMA support;
ALTER TABLE support_ticket_messages SET SCHEMA support;
ALTER TABLE contact_requests SET SCHEMA support;
ALTER TABLE contact_request_answers SET SCHEMA support;
ALTER TABLE admin_lookup_audit SET SCHEMA support;

-- Operations: app versions, maintenance, platform settings
ALTER TABLE app_version_policies SET SCHEMA operations;
ALTER TABLE app_config_log SET SCHEMA operations;
ALTER TABLE system_settings SET SCHEMA operations;
