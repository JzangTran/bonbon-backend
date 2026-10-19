-- Length backstops for text people type. The API already limits every field (Bean Validation on the request records); these
-- limits sit well above those and are only the last line if a request path ever forgets one. Limits count characters.

ALTER TABLE users ADD CONSTRAINT users_phone_len CHECK (phone IS NULL OR char_length(phone) <= 30);

ALTER TABLE vendors ADD CONSTRAINT vendors_phone_len CHECK (phone IS NULL OR char_length(phone) <= 30);
ALTER TABLE vendors ADD CONSTRAINT vendors_email_len CHECK (email IS NULL OR char_length(email) <= 255);
ALTER TABLE vendors ADD CONSTRAINT vendors_formatted_address_len CHECK (formatted_address IS NULL OR char_length(formatted_address) <= 1000);
ALTER TABLE vendors ADD CONSTRAINT vendors_province_len CHECK (province IS NULL OR char_length(province) <= 200);
ALTER TABLE vendors ADD CONSTRAINT vendors_ward_len CHECK (ward IS NULL OR char_length(ward) <= 200);
ALTER TABLE vendors ADD CONSTRAINT vendors_rejection_reason_len CHECK (rejection_reason IS NULL OR char_length(rejection_reason) <= 2000);
ALTER TABLE vendor_invoice_emails ADD CONSTRAINT vendor_invoice_emails_email_len CHECK (char_length(email) <= 255);

ALTER TABLE addresses ADD CONSTRAINT addresses_formatted_address_len CHECK (formatted_address IS NULL OR char_length(formatted_address) <= 1000);
ALTER TABLE addresses ADD CONSTRAINT addresses_province_len CHECK (province IS NULL OR char_length(province) <= 200);
ALTER TABLE addresses ADD CONSTRAINT addresses_ward_len CHECK (ward IS NULL OR char_length(ward) <= 200);
ALTER TABLE addresses ADD CONSTRAINT addresses_recipient_phone_len CHECK (recipient_phone IS NULL OR char_length(recipient_phone) <= 30);

ALTER TABLE orders ADD CONSTRAINT orders_vendor_name_len CHECK (char_length(vendor_name) <= 200);
ALTER TABLE orders ADD CONSTRAINT orders_delivery_name_len CHECK (char_length(delivery_name) <= 200);
ALTER TABLE orders ADD CONSTRAINT orders_delivery_phone_len CHECK (char_length(delivery_phone) <= 30);
ALTER TABLE orders ADD CONSTRAINT orders_delivery_address_len CHECK (char_length(delivery_address) <= 1500);
ALTER TABLE order_items ADD CONSTRAINT order_items_name_len CHECK (char_length(name) <= 200);
ALTER TABLE order_item_options ADD CONSTRAINT order_item_options_group_name_len CHECK (char_length(group_name) <= 200);
ALTER TABLE order_item_options ADD CONSTRAINT order_item_options_option_name_len CHECK (char_length(option_name) <= 200);
ALTER TABLE order_status_history ADD CONSTRAINT order_status_history_reason_len CHECK (reason IS NULL OR char_length(reason) <= 1000);

ALTER TABLE notifications ADD CONSTRAINT notifications_title_len CHECK (char_length(title) <= 300);
ALTER TABLE notifications ADD CONSTRAINT notifications_body_len CHECK (char_length(body) <= 2000);

ALTER TABLE reviews ADD CONSTRAINT reviews_reviewer_name_len CHECK (reviewer_name IS NULL OR char_length(reviewer_name) <= 200);
ALTER TABLE reviews ADD CONSTRAINT reviews_hidden_reason_len CHECK (hidden_reason IS NULL OR char_length(hidden_reason) <= 1000);
ALTER TABLE review_responses ADD CONSTRAINT review_responses_hidden_reason_len CHECK (hidden_reason IS NULL OR char_length(hidden_reason) <= 1000);
ALTER TABLE review_moderation_log ADD CONSTRAINT review_moderation_log_reason_len CHECK (reason IS NULL OR char_length(reason) <= 1000);

ALTER TABLE payment_refunds ADD CONSTRAINT payment_refunds_destination_bank_len CHECK (destination_bank IS NULL OR char_length(destination_bank) <= 200);
ALTER TABLE payment_refunds ADD CONSTRAINT payment_refunds_destination_name_len CHECK (destination_name IS NULL OR char_length(destination_name) <= 200);
ALTER TABLE payment_refunds ADD CONSTRAINT payment_refunds_bank_reference_len CHECK (bank_reference IS NULL OR char_length(bank_reference) <= 200);

ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_reference_len CHECK (reference IS NULL OR char_length(reference) <= 200);
ALTER TABLE ledger_entries ADD CONSTRAINT ledger_entries_note_len CHECK (note IS NULL OR char_length(note) <= 1000);
ALTER TABLE commission_statements ADD CONSTRAINT commission_statements_extension_reason_len CHECK (extension_reason IS NULL OR char_length(extension_reason) <= 1000);

ALTER TABLE order_case_items ADD CONSTRAINT order_case_items_item_name_len CHECK (char_length(item_name) <= 200);
ALTER TABLE shop_penalties ADD CONSTRAINT shop_penalties_reason_len CHECK (reason IS NULL OR char_length(reason) <= 1000);
ALTER TABLE shop_penalties ADD CONSTRAINT shop_penalties_decision_reason_len CHECK (decision_reason IS NULL OR char_length(decision_reason) <= 1000);
ALTER TABLE merchant_suspensions ADD CONSTRAINT merchant_suspensions_reason_len CHECK (reason IS NULL OR char_length(reason) <= 1000);
ALTER TABLE merchant_suspensions ADD CONSTRAINT merchant_suspensions_authority_reference_len CHECK (authority_reference IS NULL OR char_length(authority_reference) <= 600);
ALTER TABLE merchant_suspensions ADD CONSTRAINT merchant_suspensions_end_reason_len CHECK (end_reason IS NULL OR char_length(end_reason) <= 1000);
ALTER TABLE dish_category_cases ADD CONSTRAINT dish_category_cases_dish_name_len CHECK (char_length(dish_name) <= 200);
ALTER TABLE dish_category_cases ADD CONSTRAINT dish_category_cases_shop_name_len CHECK (char_length(shop_name) <= 200);
ALTER TABLE dish_category_cases ADD CONSTRAINT dish_category_cases_decision_reason_len CHECK (decision_reason IS NULL OR char_length(decision_reason) <= 1000);

ALTER TABLE messages ADD CONSTRAINT messages_text_len CHECK (text IS NULL OR char_length(text) <= 2000);

ALTER TABLE legal_documents ADD CONSTRAINT legal_documents_title_len CHECK (char_length(title) <= 300);
ALTER TABLE legal_documents ADD CONSTRAINT legal_documents_summary_len CHECK (summary IS NULL OR char_length(summary) <= 4000);
ALTER TABLE legal_documents ADD CONSTRAINT legal_documents_content_len CHECK (char_length(content) <= 400000);
ALTER TABLE legal_document_log ADD CONSTRAINT legal_document_log_note_len CHECK (note IS NULL OR char_length(note) <= 1000);
ALTER TABLE app_version_policies ADD CONSTRAINT app_version_policies_message_len CHECK (message IS NULL OR char_length(message) <= 1000);
ALTER TABLE admin_lookup_audit ADD CONSTRAINT admin_lookup_audit_query_len CHECK (query IS NULL OR char_length(query) <= 500);
ALTER TABLE admin_lookup_audit ADD CONSTRAINT admin_lookup_audit_reason_note_len CHECK (reason_note IS NULL OR char_length(reason_note) <= 1000);
