import LobbyState.{Player, timeStart}
import cats.effect.{IO, Ref}
import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID
import scala.io.Source

object GameState {
  case class PlayerState(segment: Int, segmentDist: Double, laps: Int, lapsDist : Double, lapTime: Long, totalTime: Long, bestLap: Long, lastLap: Long)

  case class Checkpoint(x: Int, y: Int)

  private var cpList: List[Checkpoint] = _

  private var _gameStarted : Boolean = false
  private var _gameTime : Long = 0
  private var _gameRunning: Boolean = false

  private var _initiated: Boolean = false
  def initiated: Boolean = _initiated

  def gameStarted: Boolean = _gameStarted
  def gameTime: Long = _gameTime
  def gameRunning: Boolean = _gameRunning

  def getCP0: Checkpoint = cpList.head

  def arePlayersReady(players: Ref[IO, Map[UUID, Player]]): IO[Boolean] = {
    players.get.map { m =>
      m.nonEmpty && m.values.forall(_.isReadyToStart)
    }
  }

  def startGame(): Unit = {
    _gameStarted = true
    _gameTime = System.currentTimeMillis() + 5_000
  }

  def resetGame(): Unit = {
    timeStart = 0
    _initiated = false
    _gameStarted = false
    _gameTime = 0
    _gameRunning = false
  }

  def serializeGameStart(): Chunk[Byte] = {
    val map = "map_1"

    cpList = initCP(map)

    val x0 = cpList.head.x
    val y0 = cpList.head.y
    val x1 = cpList(1).x
    val y1 = cpList(1).y
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
    if (!gameStarted) startGame()

    val recordSize = 1 + 2
    val bufferSize = 2 + recordSize

    // record ; type ; seconds
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

    buf.putShort(recordSize.toShort)
    buf.put(Server.MsgType.GameStart)
    val timer = (gameTime - System.currentTimeMillis()) / 1000
    if (timer <= -1) _gameRunning = true
    buf.putShort(math.max(-1, timer).toShort)

    buf.flip()

    Chunk.byteBuffer(buf)
  }

  private def initCP(map: String): List[Checkpoint] = {
    val file = s"src/main/resources/map/tracks/$map/points.json"
    val source = Source.fromFile(file)
    val content = try source.mkString finally source.close()

    val pointsPattern = "\\{\\s*\"x\"\\s*:\\s*(\\d+(\\.\\d+)?),\\s*\"y\"\\s*:\\s*(\\d+(\\.\\d+)?)\\s*}".r

    val points = pointsPattern.findAllIn(content).matchData.map { m =>
      val x = m.group(1).toInt
      val y = m.group(3).toInt
      Checkpoint(x, y)
    }.toList

    if (points.nonEmpty) points :+ points.head else points
  }
}
