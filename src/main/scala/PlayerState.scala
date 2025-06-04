import GameState.track
import Server.MsgType
import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

case class Vec2(x: Double, y: Double) {
  def -(b: Vec2): Vec2 = Vec2(x - b.x, y - b.y)
  def +(b: Vec2): Vec2 = Vec2(x + b.x, y + b.y)
  def *(s: Double): Vec2 = Vec2(x*s, y*s)
  def dot(b: Vec2): Double = x*b.x + y*b.y
  def length2: Double = this dot this
  def length: Double = math.sqrt(length2)
  def distanceTo(other: Vec2): Double = (this - other).length
}

// Track with sequential checkpoints (closed loop)
class Track(val points: IndexedSeq[Vec2]) {
  private val n = points.length
  def next(i: Int): Int = (i + 1) % n

  val segmentLength: Array[Double] = points.indices.map { i =>
    val a = points(i)
    val b = points(next(i))
    a.distanceTo(b)
  }.toArray

  val cumulativeDistance: Array[Double] = segmentLength.scanLeft(0D)(_ + _)
  val totalTrackLength: Double = cumulativeDistance.last

  // Project point P onto segment i -> next(i), return (t, projectedPoint, distance)
  def projectOntoSegment(P: Vec2, i: Int): (Double, Vec2, Double) = {
    val A = points(i)
    val B = points(next(i))
    val AB = B - A
    val denom = AB.length2

    if (denom == 0) return (0.0, A, P.distanceTo(A))  // degenerate segment

    // Unclamped projection parameter
    val tRaw = (P - A).dot(AB) / denom
    // Clamp t into [0, 1]
    val tClamped = Math.max(0.0, Math.min(1.0, tRaw))
    // Closest point on segment
    val closest = A + (AB * tClamped)
    val distance = P.distanceTo(closest)

    (tClamped, closest, distance)
  }

  // Find the best segment and position for a given point
  def findBestSegment(playerPos: Vec2): (Int, Double, Double) = {
    var bestSegment = 0
    var bestT = 0.0
    var minDistance = Double.MaxValue

    // Check all segments to find the closest one
    for (i <- points.indices) {
      val (t, _, distance) = projectOntoSegment(playerPos, i)
      if (distance < minDistance) {
        minDistance = distance
        bestSegment = i
        bestT = t
      }
    }

    (bestSegment, bestT, minDistance)
  }
}

case class PlayerState(uuid: UUID, username: String, ts: Long, segment: Int, segmentDist: Float, laps: Int, lapsDist : Float, lapTime: Long, totalTime: Long, bestLap: Long, lastLap: Long, hasStarted: Boolean = false)

object PlayerState {
  // Find current position on track
  def updatePosition(playerPos: Vec2, track: Track, previousSegment: Option[Int] = None): (Int, Double) = {
    previousSegment match {
      case Some(prevSeg) =>
        // First check the previous segment and adjacent ones for efficiency
        val candidateSegments = Seq(
          (prevSeg + track.points.length - 1) % track.points.length,  // previous
          prevSeg,                                                     // current
          (prevSeg + 1) % track.points.length                        // next
        )

        var bestSegment = prevSeg
        var bestT = 0.0
        var minDistance = Double.MaxValue

        for (seg <- candidateSegments) {
          val (t, _, distance) = track.projectOntoSegment(playerPos, seg)
          if (distance < minDistance) {
            minDistance = distance
            bestSegment = seg
            bestT = t
          }
        }

        // If we're still far from these segments, do a full search
        if (minDistance > track.segmentLength(bestSegment) * 0.5) {
          track.findBestSegment(playerPos) match {
            case (seg, t, dist) if dist < minDistance => (seg, t)
            case _ => (bestSegment, bestT)
          }
        } else {
          (bestSegment, bestT)
        }

      case None =>
        // No previous segment info, search all segments
        val (seg, t, _) = track.findBestSegment(playerPos)
        (seg, t)
    }
  }

  def distanceStep(old: PlayerState, carState: CarState, track: Track): PlayerState = {
    require(old.uuid == carState.uuid, "UUIDs should be equal")

    val now = System.currentTimeMillis()
    val playerPos = Vec2(carState.x, carState.y)

    // Use previous segment info for better tracking
    val (currentSeg, t) = updatePosition(playerPos, track, Some(old.segment))

    // Calculate distance along track
    val distanceInCurrentSegment = track.segmentLength(currentSeg) * t
    val distanceSoFar = track.cumulativeDistance(currentSeg) + distanceInCurrentSegment

    var lapTime = old.lapTime + (now - old.ts)
    val totalTime = old.totalTime + (now - old.ts)

    var laps = old.laps
    var bestLap = old.bestLap
    var lastLap = old.lastLap

    val hasStarted = old.hasStarted || currentSeg > 2 || distanceSoFar > track.totalTrackLength * 0.1

    // Detect lap completion - crossing from last segment to first segment
    val lastSegmentIndex = track.points.length - 1
    val lapCompleted = hasStarted && lapTime > 30_000 && ((old.segment == lastSegmentIndex && currentSeg == 0) ||
      (old.segment >= lastSegmentIndex - 1 && currentSeg == 0 &&
        old.lapsDist > track.totalTrackLength * 0.8)) // Additional safety check

    if (lapCompleted) {
      laps += 1
      if (bestLap == 0 || (lapTime > 0 && lapTime < bestLap)) {
        bestLap = lapTime
      }
      lastLap = lapTime
      lapTime = 0L
    }

    PlayerState(
      uuid = old.uuid,
      username = old.username,
      ts = now,
      segment = currentSeg,
      segmentDist = t.toFloat,
      laps = laps,
      lapsDist = distanceSoFar.toFloat,
      lapTime = lapTime,
      totalTime = totalTime,
      bestLap = bestLap,
      lastLap = lastLap,
      hasStarted = hasStarted
    )
  }

  def serializePlayerState(playersStates: Map[UUID, PlayerState]): Chunk[Byte] = {
    // Number of players
    val count = playersStates.size

    val recordSize = 16 + 4 * 4 + 4 * 8
    val bufferSize = 1 + 2 + count * (recordSize + 2) + playersStates.map(_._2.username.length).sum
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.put(MsgType.PlayerState)

    buf.putShort(count.toShort)

    // Write each player's data
    playersStates.foreach { case (playerId, state) =>
      buf.putLong(playerId.getMostSignificantBits)
      buf.putLong(playerId.getLeastSignificantBits)

      buf.put(state.username.length.toByte)
      buf.put(state.username.getBytes)

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
