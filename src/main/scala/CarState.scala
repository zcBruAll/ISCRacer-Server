import Server.MsgType
import fs2.Chunk

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID
import scala.math._

case class CarState(uuid: UUID, x: Double, y: Double, vx: Double, vy: Double, direction: Double)

object CarState {
  private val MAX_ACCELERATION = 800.0     // pixels/second²
  private val MAX_DECELERATION = 800.0     // pixels/second² (braking)
  private val DRAG_COEFFICIENT = 0.98       // air resistance per frame
  private val LATERAL_GRIP = 0.85           // how much the car grips the road sideways
  private val DRIFT_GRIP_REDUCTION = 0.2    // how much grip is reduced in drift mode
  private val HAND_BRAKE_DECELERATION = 800.0 // additional deceleration when drifting
  private val DDRIFT_TURN_MULTIPLIER = 1.5  // more rotation when handbrake is used
  private val MAX_TURN_RATE = 1.8           // radians per second at speed
  private val MIN_TURN_SPEED = 30.0         // minimum speed for effective turning
  private val MAX_SPEED = 1000.0            // maximum forward speed
  private val MAP_SIZE = 4096               // map size in px

  def physicStep(state: CarState, input: PlayerInput, track: Track, dt: Double): CarState = {
    // Ensure UUIDs match
    require(state.uuid == input.uuid, "State and input UUIDs must match")

    // Calculate current speed and normalize direction
    val currentSpeed = sqrt(state.vx * state.vx + state.vy * state.vy)
    val normalizedDirection = normalizeAngle(state.direction)

    // Calculate forward and lateral vectors based on car direction
    val forwardX = cos(normalizedDirection)
    val forwardY = sin(normalizedDirection)
    val lateralX = -sin(normalizedDirection)
    val lateralY = cos(normalizedDirection)

    // Project current velocity onto forward and lateral axes
    val forwardVelocity = state.vx * forwardX + state.vy * forwardY
    val lateralVelocity = state.vx * lateralX + state.vy * lateralY

    val surface = track.surfaceQuality(state.x, state.y)
    val accelFactor = 0.6 + 0.4 * surface

    // Calculate acceleration based on throttle input
    val acceleration = calculateAcceleration(input.throttle, forwardVelocity) * accelFactor
    val newForwardVelocity = applyAcceleration(forwardVelocity, acceleration, dt)

    val forwardAfterBrake =
      if (input.drift) applyHandBrake(newForwardVelocity, dt) else newForwardVelocity

    // Handle steering and drift mechanics
    val (newDirection, adjustedLateralVelocity) = handleSteering(
      normalizedDirection,
      lateralVelocity,
      input.steer,
      input.drift,
      currentSpeed,
      surface,
      dt
    )

    // Convert back to world coordinates
    val newForwardX = cos(newDirection)
    val newForwardY = sin(newDirection)
    val newLateralX = -sin(newDirection)
    val newLateralY = cos(newDirection)

    val newVx = forwardAfterBrake * newForwardX + adjustedLateralVelocity * newLateralX
    val newVy = forwardAfterBrake * newForwardY + adjustedLateralVelocity * newLateralY

    // Apply drag
    val draggedVx = newVx * pow(DRAG_COEFFICIENT, dt * 60)
    val draggedVy = newVy * pow(DRAG_COEFFICIENT, dt * 60)

    // Slow down if off track
    val slowF = 1 - 0.05 * (1 - track.surfaceQuality(state.x + draggedVx * dt, state.y + draggedVy * dt))
    val finalVx = draggedVx * slowF
    val finalVy = draggedVy * slowF

    // Update position
    var newX = state.x + finalVx * dt
    newX = if (newX < 0) MAP_SIZE + newX else newX % MAP_SIZE

    var newY = state.y + finalVy * dt
    newY = if (newY < 0) MAP_SIZE + newY else newY % MAP_SIZE

    CarState(
      uuid = state.uuid,
      x = newX,
      y = newY,
      vx = finalVx,
      vy = finalVy,
      direction = newDirection
    )
  }

  private def calculateAcceleration(throttle: Float, forwardVelocity: Double): Double = {
    val clampedThrottle = math.max(-1.0f, math.min(1.0f, throttle))

    if (clampedThrottle > 0) {
      // Forward acceleration with speed-based reduction
      val speedRatio = math.max(0, math.min(1, forwardVelocity / MAX_SPEED))
      MAX_ACCELERATION * clampedThrottle * (1.0 - speedRatio * 0.8)
    } else {
      // Braking or reverse
      MAX_DECELERATION * clampedThrottle
    }
  }

  private def applyAcceleration(velocity: Double, acceleration: Double, dt: Double): Double = {
    val newVelocity = velocity + acceleration * dt

    // Clamp to maximum speeds
    if (newVelocity > 0) {
      math.min(newVelocity, MAX_SPEED)
    } else {
      math.max(newVelocity, -MAX_SPEED * 0.5) // Reverse is slower
    }
  }

  private def applyHandBrake(velocity: Double, dt: Double): Double = {
    val decel = HAND_BRAKE_DECELERATION * dt
    if (velocity > 0) math.max(0.0, velocity - decel)
    else math.min(0.0, velocity + decel)
  }

  private def handleSteering(
                              direction: Double,
                              lateralVelocity: Double,
                              steer: Float,
                              drift: Boolean,
                              speed: Double,
                              surface: Double,
                              dt: Double
                            ): (Double, Double) = {

    val clampedSteer = math.max(-1.0f, math.min(1.0f, steer))

    // Calculate turn rate based on speed (slower at high speeds, ineffective at very low speeds)
    val speedFactor = if (speed > MIN_TURN_SPEED) {
      math.min(1.0, MIN_TURN_SPEED / speed + 0.3)
    } else {
      speed / MIN_TURN_SPEED * 0.5
    }

    val turnRate = MAX_TURN_RATE * clampedSteer * speedFactor * (if (drift) DDRIFT_TURN_MULTIPLIER else 1)
    val newDirection = normalizeAngle(direction + turnRate * dt)

    // Handle lateral grip and drift
    val isDrifting = drift
    val gripFactor = if (isDrifting) DRIFT_GRIP_REDUCTION else LATERAL_GRIP

    // Reduce lateral velocity based on grip
    val adjustedLateralVelocity = lateralVelocity * (1.0 - gripFactor * surface * dt * 10)

    (newDirection, adjustedLateralVelocity)
  }

  private def normalizeAngle(angle: Double): Double = {
    val normalized = angle % (2 * Pi)
    if (normalized > Pi) normalized - 2 * Pi
    else if (normalized < -Pi) normalized + 2 * Pi
    else normalized
  }

  def serializeCarStates(carStates: Map[UUID, CarState]): Chunk[Byte] = {
    // Number of players
    val count = carStates.size
    // Calculate required buffer size: 2 bytes for count + count * (ID + state fields)
    // Each CarState: ID (16 bytes) + 5 floats (5*4 = 20 bytes)
    val recordSize = 16 + 5 * 4
    val bufferSize = 1 + 2 + count * recordSize
    val buf = ByteBuffer.allocate(bufferSize).order(ByteOrder.BIG_ENDIAN)

    buf.put(MsgType.CarState)

    buf.putShort(count.toShort)

    carStates.foreach { case (playerId, state) =>
      buf.putLong(playerId.getMostSignificantBits)
      buf.putLong(playerId.getLeastSignificantBits)

      buf.putFloat(state.x.toFloat)
      buf.putFloat(state.y.toFloat)
      buf.putFloat(state.vx.toFloat)
      buf.putFloat(state.vy.toFloat)
      buf.putFloat(state.direction.toFloat)
    }
    buf.flip()  // prepare buffer for reading/transmission
    // Wrap into an fs2.Chunk for sending
    Chunk.byteBuffer(buf)
  }
}
