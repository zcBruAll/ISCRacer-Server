import cats.effect.{IO, Ref}
import fs2.Chunk
import fs2.io.net.Socket

import java.nio.charset.StandardCharsets
import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

object LobbyState {
  case class Player(uuid: UUID, socket: Socket[IO], username: String, var isReady: Boolean, var isReadyToStart : Boolean = false)

  var timeStart: Long = 0//System.currentTimeMillis()

  def serializeState(players: Map[UUID, Player]): Chunk[Byte] = {
    val temp = System.currentTimeMillis()
    if (timeStart != 0 && timeStart - temp <= 0 && !GameState.initiated) {
      return GameState.serializeGameStart()
    }

    val count = players.size
    val nbReady = nbPlayersReady(players)

    val recordSize = 1 + 2 + 2 + 2 + players.values.map(1 + _.username.length + 1 + 1).sum
    val bufferSize = 2 + recordSize

    // record ; total ; players ready ; time before start ; [username length ; username ; ready] ...

    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(recordSize.toShort)
    buf.put(Server.MsgType.LobbyUpdate)
    buf.putShort(count.toShort)
    buf.putShort(nbReady.toShort)
    buf.putShort(if (nbReady == count) {
      if (timeStart == 0)
        timeStart = temp + 10_000
      ((timeStart - temp) / 1000).toShort
    }
    else {
      timeStart = 0
      99
    })

    players.values.foreach { u =>
      buf.put(u.username.length.toByte)
      buf.put(u.username.getBytes(StandardCharsets.UTF_8))
      buf.put(0.toByte)
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

  def serializeRaceStatus(players: Map[UUID, Player], playerStates: Map[UUID, PlayerState]): Chunk[Byte] = {
    val count      = players.size
    val recordSize = 1 + 2 + 2 + 2 + players.values.map { p =>
      val base = 1 + p.username.length + 1
      if (playerStates.contains(p.uuid)) base + 2 else base + 1
    }.sum
    val bufferSize = 2 + recordSize

    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(recordSize.toShort)
    buf.put(Server.MsgType.LobbyUpdate)
    buf.putShort(count.toShort)

    buf.putShort((nbPlayersReady(players) - playerStates.size).toShort)
    buf.putShort(99.toShort)

    players.values.foreach { p =>
      buf.put(p.username.length.toByte)
      buf.put(p.username.getBytes(StandardCharsets.UTF_8))
      playerStates.get(p.uuid) match {
        case Some(ps) =>
          buf.put(1.toByte)
          buf.putShort(ps.laps.toShort)
        case None =>
          buf.put(0.toByte)
          buf.put(if (p.isReady) 1.toByte else 0.toByte)
      }
    }

    buf.flip()

    Chunk.byteBuffer(buf)
  }
}
