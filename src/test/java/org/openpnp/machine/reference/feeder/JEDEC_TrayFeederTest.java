package org.openpnp.machine.reference.feeder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

public class JEDEC_TrayFeederTest {
    @Test
    public void defaultPickRotationUsesNominalPocketRotation() {
        assertEquals(180, JEDEC_TrayFeeder.calculatePickRotation(false, 90, 180, 1.5), 0.0);
        assertEquals(180, JEDEC_TrayFeeder.calculatePickRotation(false, 90, 180, -1.5), 0.0);
    }

    @Test
    public void legacyPickRotationUsesDetectedAngleWhenExplicitlyEnabled() {
        assertEquals(-91.5, JEDEC_TrayFeeder.calculatePickRotation(true, 90, 180, 1.5), 0.0);
        assertEquals(-88.5, JEDEC_TrayFeeder.calculatePickRotation(true, 90, 180, -1.5), 0.0);
    }
}
