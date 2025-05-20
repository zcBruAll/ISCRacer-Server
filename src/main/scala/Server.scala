import cats.effect.{Concurrent, ExitCode, IO, IOApp}
import com.comcast.ip4s.{IpAddress, IpLiteralSyntax, Port, SocketAddress}
import fs2.io.net.{Datagram, Network, Socket}
import cats.implicits.toFoldableOps
import fs2.{Chunk, Stream, text}

import java.nio.charset.StandardCharsets
import scala.concurrent.duration.DurationInt

object Server extends IOApp {

  case class PlayerInput(playerId: Int, action: String)
  case class CarState(carId: Int, x: Double, y: Double, direction: Double)

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
            .onFinalize {
              // Cleanup when client disconnects
              IO.println(s"TCP client disconnected: ${clientSocket.remoteAddress}")
            }
      }
      // Run all client streams in parallel (handle multiple clients concurrently)
      .parJoinUnbounded
  }

  def udpInputServer(port: Port): Stream[IO, Unit] = {
    // Acquire a UDP socket bound to the given port
    Stream.resource(Network[IO].openDatagramSocket(address = None, port = Some(port))).flatMap { socket =>
      // Process incoming UDP packets
      socket.reads   // Stream[IO, Datagram] of incoming packets
        .evalMap { datagram =>
          // Decode the bytes to a string (assuming UTF-8 text for demo input)
          val rawMsg    = new String(datagram.bytes.toArray, StandardCharsets.UTF_8).trim
          // Parse the message into a PlayerInput case class (simple protocol: "playerId,action")
          val parts     = rawMsg.split(",", 2)
          val inputObj  = if (parts.length == 2) {
            val playerId = parts(0).toIntOption.getOrElse(-1)
            PlayerInput(playerId, parts(1))
          } else {
            PlayerInput(-1, rawMsg)  // fallback if format is unexpected
          }
          // Here we simply print the parsed input. In a real server, you might store this in game state.
          IO.println(s"UDP received: $inputObj from ${datagram.remote}")
        }
        .handleErrorWith { err =>  // Handle any errors (e.g. log and continue)
          Stream.eval(IO.println(s"UDP input error: ${err.getMessage}"))
        }
    }
  }

  def udpStateBroadcastServer(port: Port, clientAddresses: List[SocketAddress[IpAddress]]): Stream[IO, Unit] = {
    // Open a UDP socket for sending (bound to given port, or None for ephemeral)
    Stream.resource(Network[IO].openDatagramSocket(address = None, port = Some(port))).flatMap { socket =>
      // Stream that ticks approximately every 33ms (30 times per second)
      Stream.awakeEvery[IO](33.millis).evalMap { _ =>
          // Compute or retrieve the current CarState(s). Here we fabricate an example state:
          val state = CarState(carId = 1, x = 12.34, y = 56.78, direction = 90.0)
          val outBytes = state.toString.getBytes(StandardCharsets.UTF_8)
          // Send the state to all client addresses
          clientAddresses.traverse_ { addr =>
            val datagram = Datagram(addr, Chunk.array(outBytes))
            IO.println(s"UDP SENDIGN")
            socket.write(datagram)  // send the UDP packet to this address
          }
        }
        .handleErrorWith { err =>
          Stream.eval(IO.println(s"UDP broadcast error: ${err.getMessage}"))
        }
    }
  }

  // Predefine the client addresses for UDP broadcast (in practice, populate this dynamically)
  private val clientList: List[SocketAddress[IpAddress]] = List(
    SocketAddress(ip"127.0.0.1", port"6001"),
    SocketAddress(ip"193.5.233.252", port"11778")
  )

  def run(args: List[String]): IO[ExitCode] = {
    // Define the ports to use
    val tcpPort  = port"9000"
    val udpInPort  = port"5555"
    val udpOutPort = port"5556"

    // Combine the three server streams to run in parallel
    val combinedServers: Stream[IO, Unit] = Stream(
      tcpLobbyServer(tcpPort),                // TCP lobby server stream
      udpInputServer(udpInPort),             // UDP input listener stream
      udpStateBroadcastServer(udpOutPort, clientList)  // UDP state broadcaster stream
    ).parJoinUnbounded   // run all streams concurrently

    // Run the combined servers stream indefinitely
    combinedServers
      .compile                            // compile the stream down to an effect
      .drain                              // drain the stream (run it forever, or until error)
      .as(ExitCode.Success)
  }
}
