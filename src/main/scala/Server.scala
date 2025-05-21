import cats.effect.unsafe.implicits.global
import cats.effect.{ExitCode, IO, IOApp, Ref}
import com.comcast.ip4s.{IpAddress, IpLiteralSyntax, Port, SocketAddress}
import fs2.io.net.{Datagram, Network, Socket}
import cats.implicits.toFoldableOps
import fs2.{Chunk, Stream, text}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

object Server extends IOApp {

  case class PlayerInput(throttle: Double, steer: Double, drift: Boolean)
  case class CarState(x: Double, y: Double, vx: Double, vy: Double, direction: Double)

  def tcpLobbyServer(port: Port): Stream[IO, Unit] = {
    // Stream of client sockets listening on the given port
    Network[IO].server(address = None, port = Some(port))
      // For each accepted client socket, create a Stream to handle it:
      .map { clientSocket: Socket[IO] =>
        // Log new connection (for demo purposes)
        Stream.eval(IO.println(s"TCP client connected: ${clientSocket.remoteAddress}")) ++
          // Stream to periodically send lobby state to this client
          Stream.awakeEvery[IO](5.seconds).evalMap { _ =>
              // (In a real app, compute or retrieve the current lobby state here)
              val lobbyUpdateMsg = "Lobby update: current lobby state...\n"
              clientSocket.write(Chunk.array(lobbyUpdateMsg.getBytes(StandardCharsets.UTF_8)))
            }
            .concurrently {
              // Concurrently, you could also listen for any messages from the client:
              clientSocket.reads                        // Stream of incoming bytes
                .through(text.utf8.decode)              // decode bytes to String
                .evalMap(msg => IO.println(s"Received from TCP client: $msg"))
                .handleErrorWith(_ => Stream.empty)     // ignore errors (e.g. client disconnect)
                .drain
            }
            .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
              Stream.eval(IO.println(s"TCP error: ${err.getMessage}"))
            }
            .onFinalize {
              // Cleanup when client disconnects
              IO.println(s"TCP client disconnected: ${clientSocket.remoteAddress}")
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
          // Decode the bytes to a string (assuming UTF-8 text for demo input)
          val rawMsg    = new String(datagram.bytes.toArray, StandardCharsets.UTF_8).trim

          val registerClient = clientRef.update { map =>
            map.updated(datagram.remote, System.currentTimeMillis())
          }

          // Parse the message into a PlayerInput case class (simple protocol: "throttle;steer;drift")
          val parts     = rawMsg.split(";", 3)
          val inputObj  = if (parts.length == 3) {
            PlayerInput(0.8, 0.2, true)
          } else {
            PlayerInput(0.4, 0.5, true)  // fallback if format is unexpected
          }

          //IO.println(s"UDP received: $inputObj")
          // Here we simply print the parsed input. In a real server, you might store this in game state.
          registerClient *>
            IO.println(s"Registered / Updated ${datagram.remote}") *>
            IO.println(s"UDP received: $inputObj from ${datagram.remote}")
        }
        .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
          Stream.eval(IO.println(s"UDP input error: ${err.getMessage}"))
        }.concurrently {
          // Stream that ticks approximately every 33ms (30 times per second)
          Stream.awakeEvery[IO](33.millis).evalMap { _ =>
              // Compute or retrieve the current CarState(s). Here we fabricate an example state:
              val states = List[CarState](CarState(x = 12.34, y = 56.78, vx = 158, vy = 80, direction = 90.0))
              val outBytes = states.toString.getBytes(StandardCharsets.UTF_8)
              // Send the state to all client addresses
              clientRef.get.flatMap { clientMap =>
                val clientList = clientMap.keys.toList
                clientList.traverse_ { addr =>
                  val datagram = Datagram(addr, Chunk.array(outBytes))
                  IO.println(s"[Broadcast] Sending to $addr") *>
                  socket.write(datagram)  // send the UDP packet to this address
                }
              }
            }
            .handleErrorWith { err =>
              Stream.eval(IO.println(s"UDP broadcast error: ${err.getMessage}"))
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
