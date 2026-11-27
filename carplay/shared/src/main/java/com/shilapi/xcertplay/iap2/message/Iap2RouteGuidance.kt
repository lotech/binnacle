package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2Parameter
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.iap2.wire.Iap2ProtocolException
import java.time.Instant

/**
 * iAP2 route guidance (issue #180): the display component declared at identification, the
 * StartRouteGuidanceUpdates request that names it, and a log-only decode of what the iPhone sends.
 *
 * The parameter numbers follow public reverse-engineering notes, not Apple's specification, so
 * the decode never trusts a type: each value is shown by its length (a string when it is text, a
 * number when it is 1, 2, 4 or 8 bytes, otherwise hex), with the name it is believed to have.
 */
object Iap2RouteGuidance {
    /** IdentificationInformation parameter of the RouteGuidanceDisplayComponent group. */
    const val DISPLAY_COMPONENT = 30

    /** A component ID no other declared component uses (transports 0/1, vehicle 3/4). */
    const val COMPONENT_ID = 6
    const val MAX_NAME_LENGTH = 128
    const val MAX_MANEUVERS = 8

    const val ROUTE_GUIDANCE_UPDATE = 0x5201
    const val MANEUVER_UPDATE = 0x5202

    /** StartRouteGuidanceUpdates naming the declared component; empty without one, as before. */
    fun start(componentId: Int?): Iap2Frame = Iap2Messages.build(Iap2Endpoints.START_ROUTE_GUIDANCE_UPDATES) {
        if (componentId != null) u16(0, componentId)
    }

    /** One log line for a 0x5201 or 0x5202 frame, or null for any other message. */
    fun describe(frame: Iap2Frame): String? {
        val (label, fields) = when (frame.messageId) {
            ROUTE_GUIDANCE_UPDATE -> "route-guidance" to ROUTE_FIELDS
            MANEUVER_UPDATE -> "maneuver" to MANEUVER_FIELDS
            else -> return null
        }
        val parameters = try {
            Iap2ParameterList.parse(frame.payload).asList()
        } catch (_: Iap2ProtocolException) {
            return "iap2 $label undecodable (${frame.payload.size} bytes): ${hex(frame.payload, MAX_HEX_BYTES)}"
        }
        return buildString {
            append("iap2 ").append(label)
            parameters.forEach { parameter ->
                val field = fields[parameter.id]
                append(' ').append(field?.name ?: "p${parameter.id}").append('=')
                append(value(parameter, field?.kind ?: Kind.NUMBER))
            }
        }
    }

    private enum class Kind { TEXT, NUMBER, METRES, EPOCH, ROUTE_STATE, MANEUVER_STATE, UNITS }

    private class Field(val name: String, val kind: Kind)

    private fun value(parameter: Iap2Parameter, kind: Kind): String {
        val bytes = parameter.payload
        if (bytes.isEmpty()) return "(present)"
        if (kind == Kind.TEXT) text(bytes)?.let { return "\"$it\"" }
        val number = number(bytes) ?: return text(bytes)?.let { "\"$it\"" } ?: "0x${hex(bytes, MAX_HEX_BYTES)}"
        return when (kind) {
            Kind.METRES -> "${number}m"
            Kind.EPOCH -> if (number in 1..MAX_EPOCH) "$number(${Instant.ofEpochSecond(number)})" else number.toString()
            Kind.ROUTE_STATE -> named(number, ROUTE_STATES)
            Kind.MANEUVER_STATE -> named(number, MANEUVER_STATES)
            Kind.UNITS -> named(number, UNITS)
            else -> number.toString()
        }
    }

    private fun named(value: Long, names: List<String>): String =
        if (value in 0 until names.size) "$value(${names[value.toInt()]})" else value.toString()

    /** Unsigned big-endian for 1, 2, 4 or 8 bytes (an 8-byte value is shown as signed). */
    internal fun number(bytes: ByteArray): Long? {
        if (bytes.size !in setOf(1, 2, 4, 8)) return null
        return bytes.fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
    }

    /** NUL-terminated UTF-8 with no other control characters, or null. */
    internal fun text(bytes: ByteArray): String? {
        if (bytes.last() != 0.toByte()) return null
        val decoded = bytes.copyOf(bytes.size - 1).decodeToString()
        if (decoded.any { it.isISOControl() || it == '�' }) return null
        return if (decoded.length > MAX_TEXT_CHARS) decoded.take(MAX_TEXT_CHARS) + "…" else decoded
    }

    private fun hex(bytes: ByteArray, limit: Int): String =
        bytes.take(limit).joinToString("") { "%02x".format(it.toInt() and 0xff) } +
            if (bytes.size > limit) "…(${bytes.size} bytes)" else ""

    private const val MAX_HEX_BYTES = 48
    private const val MAX_TEXT_CHARS = 128
    private const val MAX_EPOCH = 253_402_300_799L // 9999-12-31

    private val ROUTE_STATES = listOf(
        "no-route", "route-set", "arrived", "loading", "locating", "rerouting", "proceed-to-route",
    )
    private val MANEUVER_STATES = listOf("continue", "initial", "prepare", "execute")
    private val UNITS = listOf("km", "mi", "m", "yd", "ft")

    private val ROUTE_FIELDS = mapOf(
        0 to Field("component", Kind.NUMBER),
        1 to Field("state", Kind.ROUTE_STATE),
        2 to Field("maneuverState", Kind.MANEUVER_STATE),
        3 to Field("road", Kind.TEXT),
        4 to Field("destination", Kind.TEXT),
        5 to Field("eta", Kind.EPOCH),
        // Seconds left (660 with an ETA 11 minutes away, Apple Maps 27 Nov).
        6 to Field("timeRemaining", Kind.NUMBER),
        7 to Field("distanceRemaining", Kind.METRES),
        8 to Field("distanceRemainingText", Kind.TEXT),
        9 to Field("distanceRemainingUnits", Kind.UNITS),
        10 to Field("distanceToManeuver", Kind.METRES),
        11 to Field("distanceToManeuverText", Kind.TEXT),
        12 to Field("distanceToManeuverUnits", Kind.UNITS),
        13 to Field("maneuverList", Kind.NUMBER),
        14 to Field("maneuverCount", Kind.NUMBER),
        15 to Field("visibleInApp", Kind.NUMBER),
        16 to Field("laneIndex", Kind.NUMBER),
        17 to Field("laneTotal", Kind.NUMBER),
        18 to Field("laneShowing", Kind.NUMBER),
        19 to Field("source", Kind.TEXT),
        20 to Field("sourceSupportsRouteGuidance", Kind.NUMBER),
    )
    private val MANEUVER_FIELDS = mapOf(
        0 to Field("component", Kind.NUMBER),
        1 to Field("index", Kind.NUMBER),
        2 to Field("description", Kind.TEXT),
        3 to Field("type", Kind.NUMBER),
        4 to Field("afterRoad", Kind.TEXT),
        5 to Field("distanceBetween", Kind.METRES),
        6 to Field("distanceBetweenText", Kind.TEXT),
        7 to Field("distanceBetweenUnits", Kind.UNITS),
        8 to Field("drivingSide", Kind.NUMBER),
        9 to Field("junctionType", Kind.NUMBER),
        10 to Field("junctionAngle", Kind.NUMBER),
        11 to Field("exitAngle", Kind.NUMBER),
    )
}

/**
 * The route the iPhone is guiding to, for the dash (issue #180): [distanceMetres] left, and when
 * the iPhone sends them the time left, the ETA (seconds since 1970) and the destination's name.
 */
data class Iap2Route(
    val distanceMetres: Long,
    val timeRemainingSeconds: Long? = null,
    val etaEpochSeconds: Long? = null,
    val destination: String? = null,
)

/**
 * Keeps the latest route from RouteGuidanceUpdate (0x5201) frames. Fields the iPhone leaves out
 * keep their last value; "no route" (0) and "arrived" (2) clear it. A route counts as active in the
 * states route set (1), rerouting (5) and proceed to route (6), once a distance has arrived.
 * Checked against Apple Maps on 27 Nov 2026: p1 state, p4 destination, p5 ETA, p6 time left (s),
 * p7 distance left (m).
 */
class Iap2RouteAccumulator {
    private var state: Long? = null
    private var distance: Long? = null
    private var remaining: Long? = null
    private var eta: Long? = null
    private var destination: String? = null

    /** The route after [frame], or null when none is active. Malformed frames change nothing. */
    @Synchronized
    fun update(frame: Iap2Frame): Iap2Route? {
        if (frame.messageId != Iap2RouteGuidance.ROUTE_GUIDANCE_UPDATE) return current()
        val parameters = try {
            Iap2ParameterList.parse(frame.payload).asList()
        } catch (_: Iap2ProtocolException) {
            return current()
        }
        parameters.forEach { parameter ->
            val bytes = parameter.payload
            when (parameter.id) {
                STATE -> Iap2RouteGuidance.number(bytes)?.let { state = it }
                DISTANCE -> Iap2RouteGuidance.number(bytes)?.takeIf { it >= 0 }?.let { distance = it }
                TIME_REMAINING -> Iap2RouteGuidance.number(bytes)?.takeIf { it >= 0 }?.let { remaining = it }
                ETA -> Iap2RouteGuidance.number(bytes)?.takeIf { it > 0 }?.let { eta = it }
                DESTINATION -> Iap2RouteGuidance.text(bytes)?.let { destination = it.ifEmpty { null } }
            }
        }
        if (state == NO_ROUTE || state == ARRIVED) clear()
        return current()
    }

    @Synchronized
    fun reset() {
        state = null
        clear()
    }

    private fun clear() {
        distance = null
        remaining = null
        eta = null
        destination = null
    }

    private fun current(): Iap2Route? {
        val metres = distance ?: return null
        if (state !in ACTIVE) return null
        return Iap2Route(metres, remaining, eta, destination)
    }

    private companion object {
        const val STATE = 1
        const val DESTINATION = 4
        const val ETA = 5
        const val TIME_REMAINING = 6
        const val DISTANCE = 7
        const val NO_ROUTE = 0L
        const val ARRIVED = 2L
        val ACTIVE = setOf(1L, 5L, 6L)
    }
}
