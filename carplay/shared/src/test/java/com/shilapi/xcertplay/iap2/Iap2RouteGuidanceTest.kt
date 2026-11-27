package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.message.Iap2ControlMessages
import com.shilapi.xcertplay.iap2.message.Iap2Messages
import com.shilapi.xcertplay.iap2.message.Iap2Route
import com.shilapi.xcertplay.iap2.message.Iap2RouteAccumulator
import com.shilapi.xcertplay.iap2.message.Iap2RouteGuidance
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import com.shilapi.xcertplay.transport.Iap2IdentificationClient
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import com.shilapi.xcertplay.transport.Iap2WirelessIdentification
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iap2RouteGuidanceTest {
    @Test
    fun startWithoutComponentKeepsTheEmptyBody() {
        val start = Iap2ControlMessages.subscriptions().single { it.messageId == 0x5200 }
        assertEquals(0, start.payload.size)
    }

    @Test
    fun startNamesTheDeclaredComponent() {
        val start = Iap2ControlMessages.subscriptions(Iap2RouteGuidance.COMPONENT_ID).single { it.messageId == 0x5200 }
        val parameter = Iap2ParameterList.parse(start.payload).asList().single()
        assertEquals(0, parameter.id)
        assertArrayEquals(byteArrayOf(0, Iap2RouteGuidance.COMPONENT_ID.toByte()), parameter.payload)
    }

    @Test
    fun identificationDeclaresTheComponentOnlyWhenEnabled() {
        val off = parameters(Iap2IdentificationClient.identificationInformation(CONFIG))
        assertTrue(off.none { it.id == Iap2RouteGuidance.DISPLAY_COMPONENT })

        val on = parameters(Iap2IdentificationClient.identificationInformation(CONFIG.copy(routeGuidanceEnabled = true)))
        assertEquals(Iap2RouteGuidance.DISPLAY_COMPONENT, on.last().id)
        val component = Iap2ParameterList.parse(on.last().payload).asList()
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6), component.map { it.id })
        assertArrayEquals(byteArrayOf(0, Iap2RouteGuidance.COMPONENT_ID.toByte()), component[0].payload)
        assertEquals("xcertplay-test\u0000", component[1].payload.decodeToString())
        // Nothing else changes: the same lists and components as without it.
        assertEquals(off.map { it.id }, on.dropLast(1).map { it.id })
    }

    @Test
    fun describesARouteGuidanceUpdate() {
        val frame = Iap2Messages.buildRaw(0x5201) {
            u16(0, 6)
            u8(1, 1)
            string(4, "Gare de Lyon")
            u64(5, 1_800_000_000L)
            u32(7, 12_345L)
            string(8, "12.3")
            u8(9, 0)
            raw(13, byteArrayOf(0, 1, 0, 2, 0, 3))
        }
        assertEquals(
            "iap2 route-guidance component=6 state=1(route-set) destination=\"Gare de Lyon\" " +
                "eta=1800000000(2027-01-15T08:00:00Z) distanceRemaining=12345m " +
                "distanceRemainingText=\"12.3\" distanceRemainingUnits=0(km) maneuverList=0x000100020003",
            Iap2RouteGuidance.describe(frame),
        )
    }

    @Test
    fun describesAManeuverAndToleratesUnexpectedTypes() {
        val frame = Iap2Messages.buildRaw(0x5202) {
            u16(1, 2)
            string(2, "Turn right")
            u8(4, 7) // not text: shown as a number
            void(40)
        }
        assertEquals(
            "iap2 maneuver index=2 description=\"Turn right\" afterRoad=7 p40=(present)",
            Iap2RouteGuidance.describe(frame),
        )
    }

    @Test
    fun outOfRangeTimesAndEmptyTextDoNotBreakTheDecode() {
        val frame = Iap2Messages.buildRaw(0x5201) {
            raw(3, byteArrayOf(0))
            u64(5, Long.MAX_VALUE)
        }
        assertEquals(
            "iap2 route-guidance road=\"\" eta=${Long.MAX_VALUE}",
            Iap2RouteGuidance.describe(frame),
        )
    }

    @Test
    fun ignoresOtherMessagesAndReportsAMalformedBody() {
        assertNull(Iap2RouteGuidance.describe(Iap2Frame(0x5001, ByteArray(0))))
        assertEquals(
            "iap2 route-guidance undecodable (3 bytes): 000901",
            Iap2RouteGuidance.describe(Iap2Frame(0x5201, byteArrayOf(0, 9, 1))),
        )
    }

    @Test
    fun accumulatesTheRouteAppleMapsSent() {
        val routes = Iap2RouteAccumulator()
        // the 27 Nov car test: "proceed to route", 4,259 m, 660 s, ETA, destination
        val update = Iap2Messages.buildRaw(0x5201) {
            u16(0, 6)
            u8(1, 6)
            string(4, "JB Hi-Fi")
            u64(5, 1_795_822_440L)
            u64(6, 660)
            u32(7, 4_259L)
            string(8, "4.3")
            u8(9, 0)
        }
        val expected = Iap2Route(4_259, 660, 1_795_822_440L, "JB Hi-Fi")
        assertEquals(expected, routes.update(update))
        // a later frame without the destination keeps it; a new distance replaces the old one
        assertEquals(
            expected.copy(distanceMetres = 4_000),
            routes.update(Iap2Messages.buildRaw(0x5201) { u32(7, 4_000L) }),
        )
        // stopping the route (state 0) clears it, and so does arriving (2)
        assertNull(routes.update(Iap2Messages.buildRaw(0x5201) { u8(1, 0) }))
        assertNull(routes.update(Iap2Messages.buildRaw(0x5201) { u8(1, 1) }))   // no distance yet
        assertEquals(Iap2Route(900), routes.update(Iap2Messages.buildRaw(0x5201) { u32(7, 900L) }))
        assertNull(routes.update(Iap2Messages.buildRaw(0x5201) { u8(1, 2) }))
    }

    @Test
    fun aRouteNeedsAnActiveStateAndSurvivesBadFrames() {
        val routes = Iap2RouteAccumulator()
        assertNull(routes.update(Iap2Messages.buildRaw(0x5201) { u8(1, 3); u32(7, 500L) }))   // loading
        assertEquals(Iap2Route(500), routes.update(Iap2Messages.buildRaw(0x5201) { u8(1, 5) }))   // rerouting
        assertEquals(Iap2Route(500), routes.update(Iap2Frame(0x5201, byteArrayOf(0, 9, 1))))
        assertEquals(Iap2Route(500), routes.update(Iap2Messages.buildRaw(0x5202) { u8(1, 0) }))
        routes.reset()
        assertNull(routes.update(Iap2Messages.buildRaw(0x5201) { u32(7, 500L) }))
    }

    private fun parameters(frame: Iap2Frame) = Iap2ParameterList.parse(frame.payload).asList()

    private companion object {
        val CONFIG = Iap2IdentificationConfig(
            name = "xcertplay-test",
            modelIdentifier = "xcertplay-test",
            manufacturer = "xcertplay",
            serialNumber = "0000001",
            firmwareVersion = "1.0.0",
            hardwareVersion = "1.0",
            wireless = Iap2WirelessIdentification(bluetoothMac = "02:00:00:00:00:01", ssid = "xcertplay-test"),
        )
    }
}
