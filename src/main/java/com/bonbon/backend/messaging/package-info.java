/**
 * Chat between a customer and a shop (flows/messaging/): conversations created by the first message, text with one image
 * and an optional reply, unread marks per side, history. The module owns conversations and messages; the shop and the
 * customer's name come from {@link com.bonbon.backend.merchant.ShopOrdering}, {@link com.bonbon.backend.merchant.ShopNames}
 * and {@link com.bonbon.backend.authentication.UserNames}. Other modules hear about a message through {@link MessageSent}.
 */
@ApplicationModule(displayName = "Messaging")
package com.bonbon.backend.messaging;

import org.springframework.modulith.ApplicationModule;
