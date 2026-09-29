package com.example.testproject.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/// Keeps track of which sockets are open for which world, and sends events
/// to them.
///
/// Each world has a lock that every send to that world goes through. A new
/// connection is registered and sent its snapshot while holding that lock,
/// so it can't miss or double up on an event fired at the same moment.
@Component
public class WorldSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WorldSessionRegistry.class);

    private static final String WORLD_ID = "worldId";
    private static final String USER_ID = "userId";
    private static final String JOINED = "joined";
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 512 * 1024;

    private final JsonMapper jsonMapper;
    private final Map<Long, Set<WebSocketSession>> sessionsByWorld = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    /// Wrapped versions of each session that are safe to send to from
    /// several threads at once, keyed by session id.
    private final Map<String, WebSocketSession> senders = new ConcurrentHashMap<>();

    public WorldSessionRegistry(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public Object lockFor(long worldId) {
        return locks.computeIfAbsent(worldId, id -> new Object());
    }

    /// Starts sending [worldId]'s events to [session]. Call while holding
    /// that world's lock, together with sending the snapshot.
    public void register(long worldId, long userId, WebSocketSession session) {
        session.getAttributes().put(WORLD_ID, worldId);
        session.getAttributes().put(USER_ID, userId);
        senders.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, SEND_BUFFER_LIMIT_BYTES));
        sessionsByWorld.computeIfAbsent(worldId, id -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(WebSocketSession session) {
        Long worldId = worldIdOf(session);
        if (worldId != null) {
            Set<WebSocketSession> sessions = sessionsByWorld.get(worldId);
            if (sessions != null) {
                sessions.remove(session);
            }
        }
        senders.remove(session.getId());
    }

    public Long worldIdOf(WebSocketSession session) {
        return (Long) session.getAttributes().get(WORLD_ID);
    }

    public Long userIdOf(WebSocketSession session) {
        return (Long) session.getAttributes().get(USER_ID);
    }

    /// Marks [session]'s player as in the world, i.e. visible to others.
    public void markJoined(WebSocketSession session) {
        session.getAttributes().put(JOINED, true);
    }

    public boolean isJoined(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get(JOINED));
    }

    /// Users with at least one joined session in [worldId].
    public Set<Long> joinedUserIds(long worldId) {
        return sessionsByWorld.getOrDefault(worldId, Set.of()).stream()
                .filter(this::isJoined)
                .map(this::userIdOf)
                .collect(Collectors.toSet());
    }

    /// Sends [event] to everyone connected to [worldId].
    public void broadcast(long worldId, Map<String, Object> event) {
        broadcastExcept(worldId, null, event);
    }

    /// Sends [event] to everyone connected to [worldId] except [excluded]
    /// (typically the session that caused it).
    public void broadcastExcept(long worldId, WebSocketSession excluded, Map<String, Object> event) {
        Set<WebSocketSession> sessions = sessionsByWorld.get(worldId);
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        TextMessage message = new TextMessage(jsonMapper.writeValueAsString(event));
        synchronized (lockFor(worldId)) {
            for (WebSocketSession session : sessions) {
                if (session != excluded) {
                    sendRaw(session, message);
                }
            }
        }
    }

    public void send(WebSocketSession session, Map<String, Object> event) {
        sendRaw(session, new TextMessage(jsonMapper.writeValueAsString(event)));
    }

    private void sendRaw(WebSocketSession session, TextMessage message) {
        WebSocketSession sender = senders.getOrDefault(session.getId(), session);
        if (!sender.isOpen()) {
            return;
        }
        try {
            sender.sendMessage(message);
        } catch (IOException | RuntimeException e) {
            // A slow or broken connection shouldn't stop everyone else from
            // getting the event - it'll be cleaned up when it closes.
            log.debug("Could not send to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
