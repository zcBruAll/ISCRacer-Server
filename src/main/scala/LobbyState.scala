import cats.effect.{IO, Ref}
import fs2.Chunk

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

object LobbyState {
  case class Player(uuid: UUID, username: String, var isReady: Boolean)

  def serializeState(players: Map[UUID, Player]): Chunk[Byte] = {
    val count = players.size
    val nbReady  = nbPlayersReady(players)

    val recordSize = 2 + 2 + 4 + players.values.map(_.username.length).sum + (count * 2)
    val bufferSize = 2 + recordSize

    // record ; total ; players ready ; time before start ; [username length ; username ; ready] ...

    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(recordSize.toShort)
    buf.putShort(count.toShort)
    buf.putShort(nbReady.toShort)
    buf.putInt(if (nbReady == count) 0 else 99)
    players.values.foreach { u =>
      buf.put(u.username.length.toByte)
      buf.put(u.username.getBytes(StandardCharsets.UTF_8))
      buf.put(if (u.isReady) 1.toByte else 0.toByte)
    }

    buf.flip()

    Chunk.byteBuffer(buf)
  }

  def arePlayersReady(players: Ref[IO, Map[UUID, Player]]): IO[Boolean] = {
    players.get.map { m =>
      m.nonEmpty && m.values.forall(_.isReady)
    }
  }

  def nbPlayersReady(players: Map[UUID, Player]): Int = {
    players.count(_._2.isReady)
  }
}
