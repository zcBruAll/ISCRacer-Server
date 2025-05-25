import cats.effect.unsafe.implicits.global
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.{IpAddress, IpLiteralSyntax, Port, SocketAddress}
import fs2.io.net.{Datagram, Network, Socket}
import cats.implicits.toFoldableOps
import fs2.Chunk.ByteBuffer
import fs2.{Chunk, Stream, text}

import java.nio.charset.StandardCharsets
import java.util.UUID
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.{DurationInt, FiniteDuration}

object Server extends IOApp {

  case class Player(uuid: UUID, username: String)

  case class PlayerState(segment: Int, segmentDist: Double, laps: Int, lapsDist : Double, lapTime: Long, totalTime: Long, bestLap: Long, lastLap: Long)

  val defaultUUID = UUID.randomUUID()

  val players = Ref.of[IO, ArrayBuffer[Player]](ArrayBuffer.empty[Player]).unsafeRunSync()
  val playerInputs: Ref[IO, Map[UUID, PlayerInput]] = Ref.of[IO, Map[UUID, PlayerInput]](Map.empty).unsafeRunSync()
  val playerStates = Ref.of[IO, Map[UUID, PlayerState]](Map.empty).unsafeRunSync()
  val carStates: Ref[IO, Map[UUID, CarState]] = Ref.of[IO, Map[UUID, CarState]](Map(defaultUUID -> CarState(defaultUUID, 321, 456, -0.5, 0.4, math.toRadians(54)))).unsafeRunSync()

  val tickDt: FiniteDuration = 33.millis
  val dtSeconds: Double      = tickDt.toMillis.toDouble / 1000.0

  def tcpLobbyServer(port: Port): Stream[IO, Unit] = {
    // Stream of client sockets listening on the given port
    Network[IO].server(address = None, port = Some(port))
      // For each accepted client socket, create a Stream to handle it:
      .map { clientSocket: Socket[IO] =>
        // Log new connection (for demo purposes)
        Stream.eval(IO.println(s"[TCP] Client connected: ${clientSocket.remoteAddress.unsafeRunSync()}")) ++
          // Stream to periodically send lobby state to this client
          Stream.awakeEvery[IO](1.seconds).evalMap { _ =>
              // (In a real app, compute or retrieve the current lobby state here)
              val lobbyUpdateMsg = "Lobby update: current lobby state...\n"
              clientSocket.write(Chunk.array(lobbyUpdateMsg.getBytes(StandardCharsets.UTF_8)))
            }
            .concurrently {
              // Concurrently, you could also listen for any messages from the client:
              clientSocket.reads                        // Stream of incoming bytes
                .through(text.utf8.decode)              // decode bytes to String
                .evalMap { msg =>
                  msg.split(";") match {
                    case Array(uuidStr, username) =>
                      try {
                        val uuid = UUID.fromString(uuidStr)
                        players.update { currentPlayers =>
                          currentPlayers += Player(uuid, username)
                        }
                        IO.println(s"[TCP] Player added: $uuid, $username")
                      } catch {
                        case e: Exception =>
                          IO.println(s"[TCP] Error parsing player data: ${e.getMessage}")
                      }
                    case _ =>
                      IO.println(s"[TCP] Invalid message format: $msg")
                  }
                  IO.println(s"Received: $msg")
                }
                .handleErrorWith(_ => Stream.empty)     // ignore errors (e.g. client disconnect)
                .drain
            }
            .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
              Stream.eval(IO.println(s"[TCP] error: ${err.getMessage}"))
            }
            .onFinalize {
              // Cleanup when client disconnects
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
          Stream.awakeEvery[IO](33.millis).evalMap { _ =>
            for {
              players <- players.get
              clients <- clientRef.get
              inputs <- playerInputs.get

              _ <- carStates.update { oldStates =>
                players.foldLeft(oldStates) { case (stateMap, player) =>
                  val id = player.uuid
                  val prevState = stateMap.getOrElse(id, CarState(id, 0, 0, 0, 0, 0))
                  val input = inputs.getOrElse(id, PlayerInput(id, 0, 0, drift = false))
                  val newState = CarState.physicStep(prevState, input, dtSeconds)
                  stateMap.updated(id, newState)
                }
              }

              updatedStates <- carStates.get
              payload = CarState.serializeCarStates(updatedStates)

              _ <- clients.keys.toList.traverse_ { addr =>
                socket.write(Datagram(addr, payload))

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
