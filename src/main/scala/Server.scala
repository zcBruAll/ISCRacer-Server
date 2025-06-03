import GameState.{gameRunning, gameStarted, initiated}
import LobbyState.Player
import cats.effect.unsafe.implicits.global
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.{IpAddress, IpLiteralSyntax, Port, SocketAddress}
import fs2.io.net.{Datagram, Network, Socket}
import cats.implicits.toFoldableOps
import fs2.{Chunk, Stream}

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Server extends IOApp {

  val defaultUUID: UUID = UUID.randomUUID()

  val players: Ref[IO, Map[UUID, Player]] = Ref.of[IO, Map[UUID, Player]](Map.empty).unsafeRunSync()
  val playerInputs: Ref[IO, Map[UUID, PlayerInput]] = Ref.of[IO, Map[UUID, PlayerInput]](Map.empty).unsafeRunSync()
  val playerStates: Ref[IO, Map[UUID, PlayerState]] = Ref.of[IO, Map[UUID, PlayerState]](Map.empty).unsafeRunSync()
  val carStates: Ref[IO, Map[UUID, CarState]] = Ref.of[IO, Map[UUID, CarState]](Map(defaultUUID -> CarState(defaultUUID, 321, 456, -0.5, 0.4, math.toRadians(54)))).unsafeRunSync()

  val tickDt: FiniteDuration = 12.millis
  val dtSeconds: Double      = tickDt.toMillis.toDouble / 1000.0

  object MsgType {
    val Handshake: Byte = 0x01
    val ReadyUpdate: Byte = 0x02
    val LobbyUpdate: Byte = 0x03
    val GameInit: Byte = 0x04
    val GameStart: Byte = 0x05
    val CarState: Byte = 0x11
    val PlayerInput: Byte = 0x12
    val PlayerState: Byte = 0x13
  }

  def broadcastToAllSockets(payload: Chunk[Byte]): IO[Unit] = {
    for {
      _ <- players.get.flatMap { playerMap =>
        playerMap.values.toList.traverse_ { entry =>
          entry.socket.write(payload)
        }
      }
    } yield ()
  }

  def tcpLobbyServer(port: Port): Stream[IO, Unit] = {
    // Stream of client sockets listening on the given port
    Network[IO].server(address = None, port = Some(port))
      // For each accepted client socket, create a Stream to handle it:
      .map { clientSocket: Socket[IO] =>
        var playerUuid: UUID = null

        Stream.eval(IO.println(s"[TCP] Client connected: ${clientSocket.remoteAddress.unsafeRunSync()}")) ++
          // Stream to periodically send lobby state to this client
          Stream.awakeEvery[IO](500.millis).evalMap { _ =>
            for {
              _ <-
                if (!gameStarted && !initiated) {
                  broadcastToAllSockets(LobbyState.serializeState(players.get.unsafeRunSync()))
                } else if (!gameRunning) {
                  broadcastToAllSockets(GameState.sendGameTimer())
                } else IO.unit
            } yield ()
            }
            .concurrently {
              Stream
                .repeatEval {
                  // read the 2‐byte length header
                  clientSocket.readN(2).map(_.toArray).flatMap { headerBytes =>
                    val len = ByteBuffer
                      .wrap(headerBytes)
                      .order(ByteOrder.BIG_ENDIAN)
                      .getShort & 0xffff

                    // now read that many bytes of payload
                    clientSocket.readN(len).map(_.toArray)
                  }
                }
                // once we have a full payload byte[], parse it and run the appropriate IO
                .evalMap { payload =>
                  val buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
                  buf.get() match {
                    case MsgType.Handshake =>
                      val msb = buf.getLong
                      val lsb = buf.getLong
                      val uuid = new UUID(msb, lsb)
                      playerUuid = uuid
                      val nameLen = buf.get().toInt & 0xff
                      val nameBytes = new Array[Byte](nameLen)
                      buf.get(nameBytes)
                      val username = new String(nameBytes, StandardCharsets.UTF_8)
                      val p = Player(uuid, clientSocket, username, isReady = false)

                      IO.println(s"[TCP] Received HandShake: ${uuid.toString} - $username") >>
                      players.update(_.updated(uuid, p))

                    case MsgType.ReadyUpdate =>
                      val msb   = buf.getLong
                      val lsb   = buf.getLong
                      val uuid  = new UUID(msb, lsb)
                      val ready = buf.get() != 0

                      IO.println(s"[TCP] Received ReadyUpdate: ${uuid.toString} - is Ready? $ready") >>
                      players.update { mp =>
                        mp.get(uuid) match {
                          case Some(old) => mp.updated(uuid, old.copy(isReady = ready))
                          case None      => mp
                        }
                      }

                    case MsgType.GameStart =>
                      val msb = buf.getLong
                      val lsb = buf.getLong
                      val uuid = new UUID(msb, lsb)
                      val ready = buf.get() != 0
                      IO.println(s"[TCP] Received GameReadyUpdate: ${uuid.toString} - isReady? $ready") >>
                      players.update { mp =>
                        mp.get(uuid) match {
                          case Some(old) => mp.updated(uuid, old.copy(isReadyToStart = ready))
                          case None      => mp
                        }
                      }
                    case _ =>
                      IO.println(s"[TCP] Received unknown message type: ${payload.toList}")
                  }
                }
            }
            .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
              Stream.eval(IO.println(s"[TCP] error: ${err.getMessage}"))
            }
            .onFinalize {
              if (players.get.unsafeRunSync().isEmpty) GameState.resetGame()
              // Cleanup when client disconnects
              players.update { map =>
                map.removed(playerUuid)
              } >>
              IO.println(s"[TCP] Client disconnected: ${clientSocket.remoteAddress.unsafeRunSync()}")
            }
      }
      // Run all client streams in parallel (handle multiple clients concurrently)
      .parJoinUnbounded
  }

  def udpInputOutputServer(port: Port, clientRef: Ref[IO, Map[SocketAddress[IpAddress], Long]]): Stream[IO, Unit] = {
    // Acquire a UDP socket bound to the given port
    Stream.resource(Network[IO].openDatagramSocket(address = None, port = Some(port))).flatMap { socket =>
      // Process incoming UDP packets
      socket.reads   // Stream[IO, Datagram] of incoming packets
        .evalMap { datagram =>
          val now = System.currentTimeMillis()

          val maybeInput = PlayerInput.decode(datagram.bytes.toArray)

          val updatePlayers: IO[Unit] =
            maybeInput.traverse_(inp =>
              playerInputs.update(_.updated(inp.uuid, inp)))

          for {
            // Always register / update the client timestamp
            _ <- clientRef.update(_.updated(datagram.remote, now))

            // Only update the PlayerInput map if decoding succeeded
            _ <- updatePlayers

            //_ <- IO.println(s"[UDP] Registered/Updated client ${datagram.remote} at $now")
            _ <- maybeInput match {
              case Some(inp) =>
                IO.println(s"[UDP] Received valid input $inp from ${datagram.remote}")
              case None =>
                IO.println(s"[UDP] Ignored invalid packet from ${datagram.remote}")
            }
          } yield ()
        }
        .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
          Stream.eval(IO.println(s"[UDP] Input error: ${err.getMessage}"))
        }.concurrently {
          // Stream that ticks approximately every 33ms (30 times per second)
          Stream.awakeEvery[IO](tickDt).evalFilter(_ => GameState.arePlayersReady(players)).evalMap { _ =>
            for {
              players <- players.get
              clients <- clientRef.get
              inputs <- playerInputs.get
              oldCarStates <- carStates.get
              oldPlayersStates <- playerStates.get

              updatedCarStates = players.map { case (id, _) =>
                val cp0 = GameState.getCP0
                val prevState = oldCarStates.getOrElse(id, CarState(id, cp0.x, cp0.y, 0, 0, 0))
                val input = inputs.getOrElse(id, PlayerInput(id, 0, 0, drift = false))
                id -> CarState.physicStep(prevState, input, dtSeconds)
              }

              updatePlayerState = players.map { case (id, _) =>
                val cp0 = GameState.getCP0
                val carState = oldCarStates.getOrElse(id, CarState(id, cp0.x, cp0.y, 0, 0, 0))
                val prevPlayerState = oldPlayersStates.getOrElse(id, PlayerState(id, System.currentTimeMillis(), 0, 0f, 0, 0f, 0L, 0L, 0L, 0L))
                id -> PlayerState.distanceStep(prevPlayerState, carState)
              }

              _ <- carStates.set(updatedCarStates)
              _ <- playerStates.set(updatePlayerState)
              payload = CarState.serializeCarStates(updatedCarStates)

              _ <- clients.keys.toList.traverse_ { addr =>
                socket.write(Datagram(addr, payload))
              }
            } yield ()
          }
          .handleErrorWith { err =>
            Stream.eval(IO.println(s"[UDP] Broadcast error: ${err.getMessage}"))
          }
        }.concurrently {
          // Stream that ticks approximately every 33ms (30 times per second)
          Stream.awakeEvery[IO](33.millis).evalFilter(_ => GameState.arePlayersReady(players)).evalMap { _ =>
              for {
                states <- playerStates.get
                clients <- clientRef.get

                _ <- clients.keys.toList.traverse_ { addr =>
                  socket.write(Datagram(addr, PlayerState.serializePlayerState(states)))
                }
              } yield ()
            }
            .handleErrorWith { err =>
              Stream.eval(IO.println(s"[UDP] Broadcast error: ${err.getMessage}"))
            }
        }
    }
  }

  def cleanupInactiveClients(clientRef: Ref[IO, Map[SocketAddress[IpAddress], Long]]): Stream[IO, Unit] = {
    Stream.awakeEvery[IO](1.second).evalMap { _ =>
      val cutoff = System.currentTimeMillis() - 2000
      clientRef.updateAndGet { currentMap =>
        currentMap.filter { case (_, lastSeen) => lastSeen >= cutoff }
      }.flatMap { newMap =>
        IO.println(s"[Cleanup] Active clients: ${newMap.keys.toList}")
      }
    }
  }



  def run(args: List[String]): IO[ExitCode] = {
    // Define the ports to use
    val tcpPort  = port"9000"
    val udpInPort  = port"5555"

    val clientRef = Ref.of[IO, Map[SocketAddress[IpAddress], Long]](Map.empty).unsafeRunSync()

    // Combine the three server streams to run in parallel
    val combinedServers: Stream[IO, Unit] = Stream(
      tcpLobbyServer(tcpPort),                // TCP lobby server stream
      udpInputOutputServer(udpInPort, clientRef),             // UDP I/O listener stream
      cleanupInactiveClients(clientRef)
    ).parJoinUnbounded   // run all streams concurrently

    // Run the combined servers stream indefinitely
    combinedServers
      .compile                            // compile the stream down to an effect
      .drain                              // drain the stream (run it forever, or until error)
      .as(ExitCode.Success)
  }
}
