package com.bonbon.backend.authentication.service;

import java.time.Duration;
import java.util.Locale;

import com.bonbon.backend.common.ratelimit.RateLimiter;
import com.bonbon.backend.common.settings.SystemSettingsService;
import org.springframework.stereotype.Component;

/**
 * Brute-force guard shared by every password check (login, admin login, adding a role with a password):
 * failures are counted per email and per IP in 15-minute windows; above the threshold a solved CAPTCHA is
 * required before the password is even compared. Deliberately no lockout (a lockout is a DoS on the victim).
 */
@Component
public class LoginAttemptGuard {

    private static final Duration WINDOW = Duration.ofMinutes(15);

    private final RateLimiter counters;
    private final SystemSettingsService settings;

    LoginAttemptGuard(RateLimiter counters, SystemSettingsService settings) {
        this.counters = counters;
        this.settings = settings;
    }

    public boolean requiresCaptcha(String email, String ip) {
        return counters.current(emailKey(email)) >= settings.getInt("auth.login_fail_email_threshold", 3)
                || counters.current(ipKey(ip)) >= settings.getInt("auth.login_fail_ip_threshold", 10);
    }

    public void recordFailure(String email, String ip) {
        counters.increment(emailKey(email), WINDOW);
        counters.increment(ipKey(ip), WINDOW);
    }

    public void recordSuccess(String email, String ip) {
        counters.reset(emailKey(email));
        counters.reset(ipKey(ip));
    }

    private static String emailKey(String email) {
        return "login_fail:email:" + email.trim().toLowerCase(Locale.ROOT);
    }

    private static String ipKey(String ip) {
        return "login_fail:ip:" + ip;
    }
}
