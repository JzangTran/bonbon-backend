/**
 * Chat between a customer and a shop (flows/messaging/): conversations created by the first message, text with one image
 * and an optional reply, unread marks per side, history. The module owns conversations and messages; the shop and the
 * customer's name come from {@link com.bonbon.backend.marketplace.merchant.ShopOrdering}, {@link com.bonbon.backend.marketplace.merchant.ShopNames}
 * and {@link com.bonbon.backend.identity.authentication.UserNames}. Other modules hear about a message through {@link MessageSent}.
 */
@NamedInterface("messaging")
package com.bonbon.backend.communication.messaging;

import org.springframework.modulith.NamedInterface;
