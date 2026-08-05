package org.openpnp.gui.operator;

import java.util.HashSet;
import java.util.Set;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Feeder;

public class OperatorJobReset {
    public static void resetForFreshOperatorRun(Job job) {
        if (job == null) {
            return;
        }
        job.removeAllPlacedStatus();
        job.removeAllEnabledState();

        Set<Part> jobParts = new HashSet<>();
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                if (placement.getType() == Placement.Type.Placement
                        || placement.getType() == Placement.Type.Dispense) {
                    placement.setEnabled(true);
                    if (placement.getPart() != null) {
                        jobParts.add(placement.getPart());
                    }
                }
            }
        }

        for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
            if (!jobParts.contains(feeder.getPart())) {
                continue;
            }
            if (feeder instanceof ReferenceTrayFeeder) {
                ((ReferenceTrayFeeder) feeder).setFeedCount(0);
            }
            else if (feeder instanceof JEDEC_TrayFeeder) {
                ((JEDEC_TrayFeeder) feeder).setFeedCount(0);
            }
        }
    }
}
