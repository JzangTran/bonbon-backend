package com.bonbon.backend.notification.service;

import java.util.UUID;

import com.bonbon.backend.notification.entity.PushDevice;
import com.bonbon.backend.notification.repository.PushDeviceRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Which phones may be pushed to, per user (push-notifications.md "Lifecycle"). */
@Service
public class PushDeviceService {

    private final PushDeviceRepository devices;

    PushDeviceService(PushDeviceRepository devices) {
        this.devices = devices;
    }

    /**
     * Registers or refreshes a device. The token is unique: registering it again moves it to the current user, so a
     * shared phone never keeps pushing the previous account's orders.
     */
    @Transactional
    public void register(UUID userId, String token, String platform, String appVersion) {
        try {
            devices.findByKindAndToken("EXPO", token).ifPresentOrElse(d -> d.claim(userId, platform, appVersion),
                    () -> devices.saveAndFlush(new PushDevice(userId, token, platform, appVersion)));
        } catch (DataIntegrityViolationException e) {
            // Two registrations of the same token raced; the other one won, so claim it.
            devices.findByKindAndToken("EXPO", token).ifPresent(d -> d.claim(userId, platform, appVersion));
        }
    }

    /** Logout: the device stops receiving this user's pushes. Another user's token is left alone. */
    @Transactional
    public void revoke(UUID userId, String token) {
        devices.findByKindAndToken("EXPO", token).filter(d -> d.getUserId().equals(userId)).ifPresent(d -> d.mark(PushDevice.Status.REVOKED));
    }
}
