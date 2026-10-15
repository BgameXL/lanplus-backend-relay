package dev.bgame.lanplus.relay;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

final class MinecraftListener {

    private static final int REJECT_TIMEOUT_MS = 5000;

    private final RoutingTable table;
    private final RateLimiter limiter;
    private final GuestTokenValidator guestTokens;

    MinecraftListener(RoutingTable table, RateLimiter limiter, GuestTokenValidator guestTokens) {
        this.table = table;
        this.limiter = limiter;
        this.guestTokens = guestTokens;
    }

    void handle(Socket player) {
        boolean parked = false;
        try {
            player.setTcpNoDelay(true);
            String ip = ((InetSocketAddress) player.getRemoteSocketAddress()).getAddress().getHostAddress();
            if (!limiter.allow(ip)) {
                return;
            }
            InputStream in = player.getInputStream();
            MinecraftHandshake hs = MinecraftHandshake.read(in);
            if (hs == null || hs.serverAddress == null) {
                return;
            }
            RouteResult routed = route(hs.serverAddress);
            if (routed.denied) {
                String mode = routed.host == null ? null : routed.host.mode();
                player.setSoTimeout(REJECT_TIMEOUT_MS);
                MinecraftReject.send(player, hs.nextState, hs.protocolVersion, motdJson(mode), kickJson(mode));
                return;
            }
            if (routed.host == null) {
                return;
            }
            table.noteVoiceIp(ip, routed.host);
            String sid = table.addPending(player, hs.raw);
            if (!routed.host.send(JSON.obj("type", "SESSION", "id", sid))) {
                table.claimPending(sid);
                return;
            }
            parked = true;
        } catch (IOException e) {
        } finally {
            if (!parked) {
                Pump.closeQuietly(player);
            }
        }
    }

    private record RouteResult(HostSession host, boolean denied) {
    }

    private RouteResult route(String serverAddress) {
        HostSession direct = table.lookup(serverAddress);
        if (direct != null) {
            if (!direct.requireToken() || "EVERYONE".equals(direct.mode())) {
                return new RouteResult(direct, false);
            }
            return new RouteResult(direct, true);
        }
        int dot = serverAddress.indexOf('.');
        String token = dot < 0 ? serverAddress : serverAddress.substring(0, dot);
        String domain = guestTokens.validate(token);
        HostSession viaToken = domain == null ? null : table.lookup(domain);
        return new RouteResult(viaToken, false);
    }

    private static String modeLabel(String mode) {
        return switch (mode == null ? "" : mode) {
            case "FRIENDS" -> "Friends only";
            case "FRIENDS_OF_FRIENDS" -> "Friends of friends";
            case "INVITED" -> "Invite only";
            default -> "Private world";
        };
    }

    private static String motdJson(String mode) {
        return "{\"text\":\"\",\"extra\":[{\"text\":\"LAN+\",\"color\":\"green\",\"bold\":true},"
                + "{\"text\":\" \\u00b7 \",\"color\":\"dark_gray\"},"
                + "{\"text\":\"" + modeLabel(mode) + "\",\"color\":\"yellow\"}]}";
    }

    private static String kickJson(String mode) {
        String reason = switch (mode == null ? "" : mode) {
            case "FRIENDS" -> "This LAN+ world is open to the host's friends only.";
            case "FRIENDS_OF_FRIENDS" -> "This LAN+ world is open to the host's friends and their friends only.";
            case "INVITED" -> "This LAN+ world is invite-only.";
            default -> "This LAN+ world is private.";
        };
        return "{\"text\":\"" + reason + " Ask the host for an invite to join.\",\"color\":\"red\"}";
    }
}
