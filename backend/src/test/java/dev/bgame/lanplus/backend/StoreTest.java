package dev.bgame.lanplus.backend;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {

    @TempDir
    Path tempDir;

    private Store store;
    private Path database;

    @BeforeEach
    void createStore() {
        database = tempDir.resolve("lanplus.db");
        store = new Store(45_000, "example.test", database.toString(),
                "https://sessionserver.example.test", false, 60_000,
                new AssetCatalog(tempDir.resolve("backgrounds"), "/backgrounds/"),
                new AssetCatalog(tempDir.resolve("banners"), "/banners/"),
                new AssetCatalog(tempDir.resolve("announcements"), "/announcements/img/"), "");
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    @Test
    void rejectsFriendAcceptanceWithoutIncomingRequest() {
        UUID recipient = UUID.randomUUID();
        UUID sender = UUID.randomUUID();

        assertFalse(store.acceptRequest(recipient, sender));
        assertFalse(store.friendsOf(recipient).contains(sender));
    }

    @Test
    void acceptsExistingIncomingFriendRequest() {
        UUID recipient = UUID.randomUUID();
        UUID sender = UUID.randomUUID();
        assertEquals(Store.AddResult.REQUESTED, store.addFriendRequest(sender, recipient));

        assertTrue(store.acceptRequest(recipient, sender));
        assertTrue(store.friendsOf(recipient).contains(sender));
        assertFalse(store.acceptRequest(recipient, sender));
    }

    @Test
    void rejectsUnownedCosmetic() {
        UUID user = UUID.randomUUID();

        assertFalse(store.equipCosmetic(user, "HEAD", "crown"));
        assertTrue(store.cosmeticLoadout(user).isEmpty());
    }

    @Test
    void equipsOwnedCosmetic() {
        UUID user = UUID.randomUUID();
        assertEquals(0, store.purchaseCosmetic(user, "crown", 0));

        assertTrue(store.equipCosmetic(user, "HEAD", "crown"));
        assertEquals("crown", store.cosmeticLoadout(user).get("HEAD"));
    }

    @Test
    void hidesLegacyLoadoutWithoutOwnership() throws Exception {
        UUID user = UUID.randomUUID();
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
             var statement = connection.prepareStatement(
                     "INSERT INTO cosmetic_loadout (uuid, slot, cosmetic_id, equipped_at) VALUES (?,?,?,?)")) {
            statement.setString(1, user.toString());
            statement.setString(2, "HEAD");
            statement.setString(3, "crown");
            statement.setLong(4, System.currentTimeMillis());
            statement.executeUpdate();
        }

        assertTrue(store.cosmeticLoadout(user).isEmpty());
    }

    @Test
    void logsSessionServerRejectionStatus() throws Exception {
        HttpServer sessionServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        sessionServer.createContext("/session/minecraft/hasJoined", exchange -> {
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        sessionServer.start();

        Store authStore = new Store(45_000, "example.test", tempDir.resolve("auth.db").toString(),
                "http://127.0.0.1:" + sessionServer.getAddress().getPort(), false, 60_000,
                new AssetCatalog(tempDir.resolve("auth-backgrounds"), "/backgrounds/"),
                new AssetCatalog(tempDir.resolve("auth-banners"), "/banners/"),
                new AssetCatalog(tempDir.resolve("auth-announcements"), "/announcements/img/"), "");
        PrintStream originalOut = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output));
            assertNull(authStore.authVerify("MissingPlayer", authStore.newChallenge()));
        } finally {
            System.setOut(originalOut);
            authStore.close();
            sessionServer.stop(0);
        }

        assertTrue(output.toString(StandardCharsets.UTF_8)
                .contains("hasJoined returned HTTP 204 for MissingPlayer"));
    }
}
