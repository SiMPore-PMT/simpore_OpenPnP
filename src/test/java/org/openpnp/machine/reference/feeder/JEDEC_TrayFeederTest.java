package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class JEDEC_TrayFeederTest {
    @Test
    public void defaultPickRotationAddsDetectedOffsetToNominalPocketRotation() {
        assertEquals(176.0, JEDEC_TrayFeeder.calculatePickRotation(false, 0.0, 180.0, -4.0), 0.0001);
        assertEquals(92.0, JEDEC_TrayFeeder.calculatePickRotation(false, 0.0, 90.0, 2.0), 0.0001);
        assertEquals(-4.0, JEDEC_TrayFeeder.calculatePickRotation(false, 0.0, 0.0, -4.0), 0.0001);
    }

    @Test
    public void legacyPickRotationUsesDetectedAngleWhenExplicitlyEnabled() {
        assertEquals(4.0, JEDEC_TrayFeeder.calculatePickRotation(true, 0.0, 180.0, -4.0), 0.0001);
    }
}
