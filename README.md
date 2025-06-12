# ISCRacer-Server
ISCRacer-Server is a lightweight game server written in Scala and built on top of [FS2](https://github.com/typelevel/fs2) and [cats-effect](https://github.com/typelevel/cats-effect). It powers the networked multiplayer mode of the [ISCRacer](https://github.com/zcBruAll/ISCRacer) racing game.

The server exposes two different endpoints:

- **TCP (port 9000)** – handles lobby communication such as player handshake and ready status.
- **UDP (port 5555)** – receives player inputs and broadcasts game state during the race.

This repository contains only the server component. The game client is maintained in a [separate project](https://github.com/zcBruAll/ISCRacer).

## Requirements

- Java 17 or higher
- [sbt](https://www.scala-sbt.org/)

## Building

Run the following command from the project root to produce a runnable fat jar. The [`sbt-assembly`](https://github.com/sbt/sbt-assembly) plugin is already configured.

```bash
sbt assembly
```

The assembled jar will be created under `target/scala-2.13/` with a name similar to:

```
ISCRacer-Server-assembly-0.1.0-SNAPSHOT.jar
```

## Running

Launch the server with Java:

```bash
java -jar target/scala-2.13/ISCRacer-Server-assembly-0.1.0-SNAPSHOT.jar
```

By default the server listens on `TCP 9000` for lobby traffic and `UDP 5555` for in‑game messages. You can change these ports by editing `Server.scala` before building.

## Protocol overview

The server and client communicate using a simple custom binary protocol. Each message begins with a 2‑byte length header followed by a message type byte. Key message types (defined in `Server.MsgType`) include:

- `Handshake` – a client announces its `UUID` and username over TCP.
- `ReadyUpdate` – players mark themselves ready in the lobby.
- `GameInit` / `GameStart` – the server tells clients when the race is about to begin.
- `PlayerInput` (UDP) – sent from clients every frame with throttle/steer/drift values.
- `CarState` and `PlayerState` – broadcast by the server to update all clients with game progress.

Refer to the source files under `src/main/scala/` for the exact binary layout of each message.

## Development notes

The core game loop lives in `Server.scala`. Car physics are implemented in `CarState.scala` and player progression (laps, segments, timings) is handled in `PlayerState.scala`. The `GameState` object orchestrates race start and end conditions.

When running locally it may be helpful to watch the console output for events such as player connections or errors. Lobby and race state is also logged.
