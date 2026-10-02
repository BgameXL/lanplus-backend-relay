# LAN+ Backend and Relay

This repository contains the two standalone server components used by LAN+:

- `backend`: the HTTP and WebSocket service for accounts, presence, friends, profiles, public data, and relay ticket
  validation. Persistent state is stored in SQLite.
- `relay`: the TCP/TLS tunnel for hosted Minecraft worlds and also the optional UDP voice relay.

## Requirements

- Java 21, the backend targets Java 21, the relay targets Java 17 and also runs with Java 21.

## Local setup

The applications read configuration from process environment variables, and they do not load `.env` files automatically.

### 1. Start the backend

Create a directory for local persistent data and run the backend:

```bash
mkdir -p run/backend

LANPLUS_BACKEND_BIND=127.0.0.1:8080 \
LANPLUS_BACKEND_BASE_DOMAIN=lanplus.local \
LANPLUS_BACKEND_RELAY_HOST=127.0.0.1 \
LANPLUS_BACKEND_RELAY_PORT=8443 \
LANPLUS_BACKEND_DATA_FILE=run/backend/lanplus.db \
LANPLUS_BACKEND_ALLOW_OFFLINE=true \
java -jar backend/build/libs/lanplus-backend-1.0.0.jar
```

The backend is ready when it prints `LAN+ backend up`, you can confirm the public API is responding with:

```bash
curl http://127.0.0.1:8080/public/worlds
```

### 2. Start the relay

For local development, disable relay TLS but keep ticket validation connected to the backend:

```bash
LANPLUS_RELAY_BIND=127.0.0.1:8443 \
LANPLUS_RELAY_MC_BIND=127.0.0.1:25565 \
LANPLUS_RELAY_VOICE_BIND=127.0.0.1:24454 \
LANPLUS_RELAY_BASE_DOMAIN=lanplus.local \
LANPLUS_RELAY_TLS=false \
LANPLUS_RELAY_BACKEND_URL=http://127.0.0.1:8080 \
java -jar relay/build/libs/lanplus-relay-1.0.0.jar
```

The relay is ready when it prints `LAN+ relay up` and the default local ports are:

| Port    | Protocol | Purpose                        |
|---------|----------|--------------------------------|
| `8080`  | TCP      | Backend HTTP and WebSocket API |
| `8443`  | TCP      | Relay control connection       |
| `25565` | TCP      | Minecraft player connections   |
| `24454` | UDP      | Voice relay                    |

Set `LANPLUS_RELAY_VOICE=false` when voice support is not needed.

### Relay-only smoke test

The relay can run without a backend for isolated local testing:

```bash
LANPLUS_RELAY_NO_AUTH=true \
LANPLUS_RELAY_TLS=false \
LANPLUS_RELAY_BIND=127.0.0.1:8443 \
LANPLUS_RELAY_MC_BIND=127.0.0.1:25565 \
LANPLUS_RELAY_VOICE=false \
java -jar relay/build/libs/lanplus-relay-1.0.0.jar
```

`LANPLUS_RELAY_NO_AUTH=true` disables ticket validation, is only intended for test environments.

## Production checklist

1. Build both jars with `./gradlew build`.
2. Put the backend behind an HTTPS reverse proxy, the backend itself listens for HTTP and WebSocket traffic.
3. Set `LANPLUS_BACKEND_ALLOW_OFFLINE=false` so authentication requires a verified Minecraft session.
4. Store `LANPLUS_BACKEND_DATA_FILE` on persistent storage and back it up.
5. Run the relay with authentication enabled and set`LANPLUS_RELAY_BACKEND_URL` to the backend URL reachable from the
   relay.
6. Enable relay TLS and provide readable PEM certificate and private-key files through `LANPLUS_RELAY_CERT` and
   `LANPLUS_RELAY_KEY`.
7. Expose only the ports required by the deployment. Voice uses UDP, the other services use TCP.
8. Keep admin keys, webhooks, certificates, and private keys out of git of course.

## Backend configuration

| Environment variable                      | Default               | Purpose                                    |
|-------------------------------------------|-----------------------|--------------------------------------------|
| `LANPLUS_BACKEND_BIND`                    | `:8080`               | Backend bind address                       |
| `LANPLUS_BACKEND_BASE_DOMAIN`             | `lanplus.dev`         | Base domain used by backend responses      |
| `LANPLUS_BACKEND_RELAY_HOST`              | `relay.lanplus.dev`   | Relay host advertised to clients           |
| `LANPLUS_BACKEND_RELAY_PORT`              | `8443`                | Relay control port advertised to clients   |
| `LANPLUS_BACKEND_HEARTBEAT_TTL_SECONDS`   | `45`                  | Presence heartbeat lifetime                |
| `LANPLUS_BACKEND_DATA_FILE`               | `lanplus.db`          | SQLite database path                       |
| `LANPLUS_BACKEND_SESSION_SERVER`          | Mojang session server | Minecraft session verification service     |
| `LANPLUS_BACKEND_ALLOW_OFFLINE`           | `true`                | Allow offline authentication               |
| `LANPLUS_BACKEND_SESSION_TTL_SECONDS`     | `2592000`             | Backend session lifetime                   |
| `LANPLUS_BACKEND_BACKGROUNDS_DIR`         | `backgrounds`         | Profile background assets                  |
| `LANPLUS_BACKEND_BANNERS_DIR`             | `banners`             | Profile banner assets                      |
| `LANPLUS_BACKEND_ANNOUNCEMENT_IMAGES_DIR` | `announcement-images` | Announcement images                        |
| `LANPLUS_BACKEND_COSMETICS_DIR`           | `cosmetics`           | Cosmetic assets                            |
| `LANPLUS_BACKEND_REQUEST_TIMEOUT_MS`      | `60000`               | Accepted socket read timeout               |
| `LANPLUS_BACKEND_ADMIN_UUIDS`             | empty                 | Comma- or whitespace-separated admin UUIDs |
| `LANPLUS_BACKEND_ADMIN_KEY`               | empty                 | Secret used by administrative routes       |
| `LANPLUS_BACKEND_DISCORD_WEBHOOK`         | empty                 | Optional moderation/report webhook         |
| `LANPLUS_BACKEND_LATEST_VERSION`          | empty                 | Initial advertised LAN+ version            |
| `LANPLUS_BACKEND_DOWNLOAD_URL`            | empty                 | Advertised LAN+ download URL               |

For production, review every default instead of relying on the local development values. In particular disable offline
authentication and set a durable database path.

## Relay configuration

| Environment variable            | Default         | Purpose                                            |
|---------------------------------|-----------------|----------------------------------------------------|
| `LANPLUS_RELAY_BIND`            | `:8443`         | Relay control bind address                         |
| `LANPLUS_RELAY_MC_BIND`         | `:25565`        | Minecraft connection bind address                  |
| `LANPLUS_RELAY_TLS`             | enabled         | Enable TLS on the relay control connection         |
| `LANPLUS_RELAY_CERT`            | empty           | Readable PEM certificate path; required with TLS   |
| `LANPLUS_RELAY_KEY`             | empty           | Readable PEM private-key path; required with TLS   |
| `LANPLUS_RELAY_NO_AUTH`         | `false`         | Disable ticket validation for isolated local tests |
| `LANPLUS_RELAY_BACKEND_URL`     | empty           | Backend URL; required while authentication is on   |
| `LANPLUS_RELAY_BASE_DOMAIN`     | `lanplus.local` | Base domain advertised by the relay                |
| `LANPLUS_RELAY_MC_RATE_PER_MIN` | `30`            | Per-source Minecraft connection rate limit         |
| `LANPLUS_RELAY_VOICE`           | `true`          | Enable the UDP voice relay                         |
| `LANPLUS_RELAY_VOICE_BIND`      | `:24454`        | Voice relay UDP bind address                       |
| `LANPLUS_RELAY_VOICE_HOST`      | empty           | Optional advertised voice host override            |

TLS is enabled by default unless `LANPLUS_RELAY_NO_AUTH=true`. When TLS is enabled, both certificate paths must exist.
When authentication is enabled, `LANPLUS_RELAY_BACKEND_URL` is required.