import com.esotericsoftware.kryo.Kryo
import com.typesafe.scalalogging.Logger
import com.esotericsoftware.kryonet.{Connection, Listener, Server}

object Server extends App {
  val logger = Logger("ISCRacerLogger")

  val server: Server = new Server()
  server.start()
  server.bind(7897, 7898)

  val kryo: Kryo = server.getKryo
  kryo.register(classOf[SomeRequest])
  kryo.register(classOf[SomeResponse])

  logger.info("Server running!")

  server.addListener(new Listener() {
    override def received(connection: Connection, `object`: AnyRef): Unit = {
      `object` match {
        case request: SomeRequest =>
          logger.info("Received: " + request.text)
          val response = new SomeResponse
          response.text = "Thanks"
          connection.sendTCP(response)
        case _ =>
      }
    }
  })
}

class SomeRequest {
  var text: String = null
}

class SomeResponse {
  var text: String = null
}
