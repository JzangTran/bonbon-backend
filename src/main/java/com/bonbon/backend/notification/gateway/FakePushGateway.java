package com.bonbon.backend.notification.gateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Used in dev and tests: remembers what would have been sent. Nothing leaves the machine. Tokens containing "Dead" count as dead. */
public class FakePushGateway implements PushGateway {

    private final List<PushMessage> sent = new CopyOnWriteArrayList<>();

    @Override
    public List<String> send(List<PushMessage> messages) {
        sent.addAll(messages);
        return messages.stream().map(PushMessage::token).filter(t -> t.contains("Dead")).toList();
    }

    public List<PushMessage> sent() {
        return List.copyOf(sent);
    }
}
