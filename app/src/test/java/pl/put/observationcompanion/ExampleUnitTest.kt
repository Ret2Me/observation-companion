package pl.put.observationcompanion

import pl.put.observationcompanion.domain.model.Satellite
import pl.put.observationcompanion.domain.model.Tle
import pl.put.observationcompanion.domain.orbit.SatPropagator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertTrue(2 + 2 == 4)
    }

    /**
     * Smoke-tests the predict4java-backed propagator against ISS over Poznan -
     * a healthy LEO sat should produce *some* passes over 48h with a 5 deg floor,
     * and every pass should obey AOS <= TCA <= LOS with sane azimuths.
     */
    @Test
    fun issOverPoznan_producesAtLeastOnePass() {
        val propagator = SatPropagator()
        val tle = Tle(
            noradId = "25544",
            line1 = "1 25544U 98067A   26144.19669721  .00007438  00000-0  14130-3 0  9992",
            line2 = "2 25544  51.6327  58.8652 0007496  92.3340 267.8507 15.49341091568031"
        )
        val satellite = Satellite(
            id = "25544",
            name = "ISS",
            noradId = "25544",
            isActive = true
        )
        val station = SatPropagator.GroundStation(
            latDegrees = 52.4064,
            lonDegrees = 16.9252,
            altMeters = 80.0
        )

        val passes = propagator.predictPasses(
            satellite = satellite,
            tle = tle,
            station = station,
            startTime = Instant.now(),
            durationHours = 48L,
            minElevationDegrees = 5.0
        )

        assertFalse("expected ISS passes over 48h", passes.isEmpty())
        for (p in passes) {
            assertTrue("AOS before TCA", p.aos <= p.tca)
            assertTrue("TCA before LOS", p.tca <= p.los)
            assertTrue("max elevation positive", p.maxElevation > 0)
            assertTrue("start az in range", p.startAzimuth in 0.0..360.0)
            assertTrue("end az in range", p.endAzimuth in 0.0..360.0)
        }
    }
}
