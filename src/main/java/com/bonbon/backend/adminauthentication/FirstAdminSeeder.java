package com.bonbon.backend.adminauthentication;

import com.bonbon.backend.authentication.AdminAccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Creates the first administrator from ADMIN_SEED_EMAIL / ADMIN_SEED_PASSWORD at startup if no administrator
 * exists yet; safe on every start. Not a migration, so no password hash ever lands in source control.
 */
@Component
class FirstAdminSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(FirstAdminSeeder.class);

    private final AdminAccountService admins;
    private final String email;
    private final String password;

    FirstAdminSeeder(AdminAccountService admins, @Value("${bonbon.admin.seed.email:}") String email,
            @Value("${bonbon.admin.seed.password:}") String password) {
        this.admins = admins;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (email.isBlank() || password.isBlank()) {
            return;
        }
        if (password.length() < 8) {
            log.warn("ADMIN_SEED_PASSWORD is shorter than 8 characters; first admin not created");
            return;
        }
        if (admins.seedFirstAdmin(email, password)) {
            log.info("First administrator created for {}", email);
        }
    }
}
