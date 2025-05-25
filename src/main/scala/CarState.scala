import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

case class CarState(uuid: UUID, x: Double, y: Double, vx: Double, vy: Double, direction: Double)

object CarState {
  def physicStep(
                old: CarState,
                input: PlayerInput,
                dt: Double,
                maxAccel: Double = 10D,
                maxSteerRate: Double = 90D
                ): CarState = {
    val accel = input.throttle * maxAccel
    val dirRad = Math.toRadians(old.direction)

    val newVX = old.vx + accel * Math.cos(dirRad) * dt
    val newVY = old.vy + accel * Math.sin(dirRad) * dt

    val newX = old.x + newVX * dt
    val newY = old.y + newVY * dt

    val newDir = old.direction + input.steer * maxSteerRate * dt

    CarState(input.uuid, newX, newY, newVX, newVY, newDir)
  }

  def serializeCarStates(carStates: Map[UUID, CarState]): Chunk[Byte] = {
    // Number of players
    val count = carStates.size
    // Calculate required buffer size: 2 bytes for count + count * (ID + state fields)
    // Each CarState: ID (16 bytes) + 5 floats (5*4 = 20 bytes)
    val recordSize = 16 + 5 * 4
    val bufferSize = 2 + count * recordSize
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)
    buf.putShort(count.toShort)  // write player count (assuming count <= 32767)
    // Write each player's data
    carStates.foreach { case (playerId, state) =>
      // Write 16-byte UUID (mostSigBits + leastSigBits)
      buf.putLong(playerId.getMostSignificantBits)
      buf.putLong(playerId.getLeastSignificantBits)
      // Write position (x, y) and velocity (vx, vy) as 32-bit floats
      buf.putFloat(state.x.toFloat)
      buf.putFloat(state.y.toFloat)
      buf.putFloat(state.vx.toFloat)
      buf.putFloat(state.vy.toFloat)
      // Write direction (e.g., orientation angle) as float (or smaller if suitable)
      buf.putFloat(state.direction.toFloat)
    }
    buf.flip()  // prepare buffer for reading/transmission
    // Wrap into an fs2.Chunk for sending
    Chunk.byteBuffer(buf)
  }
}
