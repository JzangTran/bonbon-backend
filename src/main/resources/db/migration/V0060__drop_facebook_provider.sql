-- Facebook sign-in is dropped from the scope: Google is the only external identity provider.
-- No Facebook identity was ever stored (the API always refused it), so only the check narrows.
ALTER TABLE identity.user_auth_providers DROP CONSTRAINT user_auth_providers_provider_check;
ALTER TABLE identity.user_auth_providers ADD CONSTRAINT user_auth_providers_provider_check CHECK (provider IN ('GOOGLE'));
