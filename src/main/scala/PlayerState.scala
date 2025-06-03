import GameState.{cumulativeDistances, getCPList, segmentLengths}
import Server.MsgType
import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

case class PlayerState(uuid: UUID, ts: Long, segment: Int, segmentDist: Float, laps: Int, lapsDist : Float, lapTime: Long, totalTime: Long, bestLap: Long, lastLap: Long)

object PlayerState {
  def distanceStep(old: PlayerState, carState: CarState): PlayerState = {
    require(old.uuid.eq(carState.uuid), "UUIDs should be equals")

    val now = System.currentTimeMillis()

    val p = (carState.x, carState.y)
    var bestIndex = 0
    var bestT = 0f
    var minDist = Float.MaxValue

    val cpList = getCPList
    for (i <- 0 until cpList.length - 1) {
      val a = (cpList(i).x, cpList(i).y)
      val b = (cpList(i+1).x, cpList(i+1).y)
      val ab = (b._1 - a._1, b._2 - a._2)
      val ap = (p._1 - a._1, p._2 - a._2)

      val abLenSq = math.sqrt(ab._1 * ab._1 + ab._2 * ab._2)
      val t = (ap._1 * ab._1 + ap._2 * ab._2) / abLenSq

      val clampedT = t.max(0f).min(1f)
      val proj = (a._1 + clampedT * ab._1, a._2 + clampedT * ab._2)
      val dist = math.pow(p._1 - proj._1, 2) + math.pow(p._2 - proj._2, 2)

      if (dist < minDist) {
        minDist = dist.toFloat
        bestIndex = i
        bestT = clampedT.toFloat
      }
    }

    val distanceSoFar = cumulativeDistances(bestIndex) + segmentLengths(bestIndex) * bestT

    var lapTime = old.lapTime + now - old.ts
    val totalTime = old.totalTime + now - old.ts

    var laps = old.laps
    var bestLap = old.bestLap
    var lastLap = old.lastLap

    if ((bestIndex == 0 && old.segment == cpList.length - 2) || (bestIndex == 1 && old.segment == cpList.length - 1)) {
      laps += 1
      if (bestLap > lapTime) bestLap = lapTime
      lastLap = lapTime
      lapTime = 0L
    }

    PlayerState(
      uuid = old.uuid,
      ts = now,
      segment = bestIndex,
      segmentDist = bestT,
      laps = laps,
      lapsDist = distanceSoFar,
      lapTime = lapTime,
      totalTime = totalTime,
      bestLap = bestLap,
      lastLap = lastLap
    )
  }

  def serializePlayerState(playersStates: Map[UUID, PlayerState]): Chunk[Byte] = {
    // Number of players
    val count = playersStates.size

    val recordSize = 16 + 4 * 4 + 4 * 8
    val bufferSize = 1 + 2 + count * recordSize
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.put(MsgType.PlayerState)

    buf.putShort(count.toShort)

    // Write each player's data
    playersStates.foreach { case (playerId, state) =>
      buf.putLong(playerId.getMostSignificantBits)
      buf.putLong(playerId.getLeastSignificantBits)

      buf.putInt(state.segment)
      buf.putFloat(state.segmentDist)
      buf.putInt(state.laps)
      buf.putFloat(state.lapsDist)
      buf.putLong(state.lapTime)
      buf.putLong(state.totalTime)
      buf.putLong(state.bestLap)
      buf.putLong(state.lastLap)
    }

    buf.flip()  // prepare buffer for reading/transmission
    // Wrap into an fs2.Chunk for sending
    Chunk.byteBuffer(buf)
  }
}
