import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

case class PlayerInput (
                       uuid: UUID,
                       throttle: Float,
                       steer: Float,
                       drift: Boolean
                       )

object PlayerInput {
  // Packet size: 16 (UUID) + 4 (throttle) + 4 (steer) + 1 (drift)
  private val PacketSize = 16 + 4 + 4 + 1

  def decode(bytes: Array[Byte]): Option[PlayerInput] = {
    if (bytes.length < PacketSize) return None

    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    val msb = bb.getLong
    val lsb = bb.getLong
    val uuid = new UUID(msb, lsb)

    val throttle = bb.getFloat
    val steer = bb.getFloat

    val drift = (bb.get() != 0)

    if (throttle < -1f || throttle > 1f) return None
    if (steer < -1f || steer > 1f) return None

    Some(PlayerInput(uuid, throttle, steer, drift))
  }
}

