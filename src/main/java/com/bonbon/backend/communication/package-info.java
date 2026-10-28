/**
 * How the parties hear from each other: chat between customer and shop, the live order socket, and notifications (in-app, push,
 * email). Notification only listens to events; no module depends on it.
 * <p>
 * Its parts (messaging, notification) are named interfaces: their top-level types are what other modules may use; their
 * sub-packages are internal to this module.
 */
@ApplicationModule(displayName = "Communication")
package com.bonbon.backend.communication;

import org.springframework.modulith.ApplicationModule;
