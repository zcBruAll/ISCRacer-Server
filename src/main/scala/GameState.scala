import LobbyState.{Player, timeStart}
import PlayerState.serializeResults
import Server.{carStates, playerInputs, playerStates, players}
import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Ref}
import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID
import scala.io.Source

object GameState {
  private var _track: Track = _
  def track: Track = _track

  private var _gameStarted : Boolean = false
  private var _gameTime : Long = 0
  private var _gameRunning: Boolean = false

  private var _initiated: Boolean = false
  def initiated: Boolean = _initiated

  def gameStarted: Boolean = _gameStarted
  def gameTime: Long = _gameTime
  def gameRunning: Boolean = _gameRunning

  def arePlayersReady(players: Ref[IO, Map[UUID, Player]]): IO[Boolean] = {
    players.get.map { m =>
      m.nonEmpty && m.values.forall(_.isReadyToStart)
    }
  }

  def shouldRun(players: Ref[IO, Map[UUID, Player]]): IO[Boolean] = {
    if (_gameStarted) IO.pure(true) else arePlayersReady(players)
  }

  def startGame(): Unit = {
    // Reset some data
    timeStart = 0
    _initiated = false
    _gameRunning = false
    playerInputs.set(Map.empty).unsafeRunSync()

    _gameStarted = true
    _gameTime = System.currentTimeMillis() + 5_000
  }

  def resetGame(): Unit = {
    timeStart = 0
    _initiated = false
    _gameStarted = false
    _gameTime = 0
    _gameRunning = false

    players.update(m => m.map { case (id, ply) =>
      if (playerStates.get.unsafeRunSync().keySet.contains(id))
        id -> ply.copy(isReady = false, isReadyToStart = false)
      else
        id -> ply
    }).unsafeRunSync()
    playerStates.set(Map.empty).unsafeRunSync()
    carStates.set(Map.empty).unsafeRunSync()
    playerInputs.set(Map.empty).unsafeRunSync()
  }

  def serializeGameStart(): Chunk[Byte] = {
    val map = "map_1"

    _track = new Track(initCP(map))

    val x0 = track.points(0).x
    val y0 = track.points(0).y
    val x1 = track.points(track.next(0)).x
    val y1 = track.points(track.next(0)).y
    val direction = Math.atan2(y1 - y0, x1 - x0).toFloat

    val recordSize = 1 + 1 + map.length + 2 + 2 + 4
    val bufferSize = 2 + recordSize

    // record ; type ; map name length ; map name ; start x ; start y ; start direction
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

    buf.putShort(recordSize.toShort)
    buf.put(Server.MsgType.GameInit)
    buf.put(map.length.toByte)
    buf.put(map.getBytes)
    buf.putShort(x0.toShort)
    buf.putShort(y0.toShort)
    buf.putFloat(direction)

    buf.flip()

    _initiated = true

    Chunk.byteBuffer(buf)
  }

  def sendGameTimer(): Chunk[Byte] = {
    val recordSize = 1 + 2
    val bufferSize = 2 + recordSize

    // record ; type ; seconds
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

    buf.putShort(recordSize.toShort)
    buf.put(Server.MsgType.GameStart)

    val now = System.currentTimeMillis()

    if (!gameStarted && !arePlayersReady(Server.players).unsafeRunSync()) _gameTime = now + 99_000
    else if (!gameStarted) startGame()

    val timer = (gameTime - now) / 1000
    if (timer <= -1) _gameRunning = true
    buf.putShort(math.max(-1, timer).toShort)

    buf.flip()

    Chunk.byteBuffer(buf)
  }

  def endGame(playerStates: Map[UUID, PlayerState]): Chunk[Byte] = {
    val temp = serializeResults(playerStates)

    resetGame()
    temp
  }

  private def initCP(map: String): IndexedSeq[Vec2] = {
    val source = Source.fromResource(s"map/tracks/$map/points.json")("UTF-8")
    val content = try source.mkString finally source.close()

    val pointsPattern = "\\{\\s*\"x\"\\s*:\\s*(\\d+(\\.\\d+)?),\\s*\"y\"\\s*:\\s*(\\d+(\\.\\d+)?)\\s*}".r

    val points = pointsPattern.findAllIn(content).matchData.map { m =>
      val x = m.group(1).toInt
      val y = m.group(3).toInt
      Vec2(x, y)
    }.toIndexedSeq

    points
  }
}
