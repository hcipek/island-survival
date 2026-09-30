package com.example.testproject.live;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;

/// The live connection for one player in one world, at
/// `/ws/worlds/{worldId}?userId={userId}`. See [ClientMessage] for what
/// clients send and [LiveEvents] for what they get back.
@Component
public class WorldSocketHandler extends TextWebSocketHandler {

    private final LiveWorldService liveWorldService;
    private final WorldSessionRegistry registry;
    private final JsonMapper jsonMapper;

    public WorldSocketHandler(LiveWorldService liveWorldService, WorldSessionRegistry registry, JsonMapper jsonMapper) {
        this.liveWorldService = liveWorldService;
        this.registry = registry;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        URI uri = session.getUri();
        try {
            String path = uri.getPath();
            long worldId = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
            String userId = UriComponentsBuilder.fromUri(uri).build().getQueryParams().getFirst("userId");
            liveWorldService.connect(session, worldId, Long.parseLong(userId));
        } catch (NumberFormatException | NullPointerException e) {
            rejectConnection(session, "Expected /ws/worlds/{worldId}?userId={userId}");
        } catch (ResponseStatusException e) {
            rejectConnection(session, e.getReason());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            ClientMessage msg = jsonMapper.readValue(message.getPayload(), ClientMessage.class);
            String type = msg.type() == null ? "" : msg.type();
            switch (type) {
                case "join" -> liveWorldService.join(session, msg.position());
                case "move" -> liveWorldService.move(session, msg.from(), msg.path());
                case "board" -> liveWorldService.board(session, msg.boatId());
                case "sail" -> liveWorldService.sail(session, msg.boatId(), msg.from(), msg.path());
                case "land" -> liveWorldService.land(session, msg.boatId(), msg.position());
                case "gather" -> liveWorldService.gather(session, msg.nodeId());
                default -> registry.send(session, LiveEvents.error("Unknown message type: " + type));
            }
        } catch (ResponseStatusException e) {
            registry.send(session, LiveEvents.error(e.getReason()));
        } catch (JacksonException e) {
            registry.send(session, LiveEvents.error("Malformed message"));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        liveWorldService.disconnect(session);
    }

    private void rejectConnection(WebSocketSession session, String reason) throws IOException {
        registry.send(session, LiveEvents.error(reason));
        session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
    }
}
