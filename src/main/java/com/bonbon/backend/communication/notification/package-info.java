/**
 * Telling people about things outside the screen they are on: the stored in-app list and push to phones. It only
 * listens to events of other modules (today: order changes) and no module depends on it.
 */
@NamedInterface("notification")
package com.bonbon.backend.communication.notification;

import org.springframework.modulith.NamedInterface;
