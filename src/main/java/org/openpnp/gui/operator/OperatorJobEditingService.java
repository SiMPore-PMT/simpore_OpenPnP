package org.openpnp.gui.operator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.machine.reference.feeder.ReferenceTrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Part;
import org.openpnp.model.PanelLocation;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Feeder;

public class OperatorJobEditingService {
    private final Set<String> globallyDisabledDispenseKeys = new HashSet<>();

    public void resetBoardsOnly(Job job) throws Exception {
        resetJob(job, false);
    }

    public void resetJob(Job job, boolean resetTrayProgress) throws Exception {
        requireJob(job);
        job.removeAllPlacedStatus();
        globallyDisabledDispenseKeys.clear();
        Set<Part> jobParts = new HashSet<>();
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            boardLocation.setLocallyEnabled(true);
            job.storeEnabledState(boardLocation, null, true);
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                placement.setEnabled(true);
                job.storeEnabledState(boardLocation, placement, true);
                if (placement.getPart() != null) {
                    jobParts.add(placement.getPart());
                }
            }
        }
        if (resetTrayProgress && Configuration.get().getMachine() != null) {
            for (Feeder feeder : Configuration.get().getMachine().getFeeders()) {
                if (!jobParts.contains(feeder.getPart())) {
                    continue;
                }
                if (feeder instanceof JEDEC_TrayFeeder) {
                    JEDEC_TrayFeeder tray = (JEDEC_TrayFeeder) feeder;
                    tray.setFeedCount(0);
                    saveTrayProgress(job, tray);
                }
                else if (feeder instanceof ReferenceTrayFeeder) {
                    ((ReferenceTrayFeeder) feeder).setFeedCount(0);
                }
            }
        }
        persistJob(job);
    }

    public void setBoardEnabled(Job job, PlacementsHolderLocation<?> boardLocation, boolean enabled) throws Exception {
        requireJob(job);
        boardLocation.setLocallyEnabled(enabled);
        job.storeEnabledState(boardLocation, null, enabled);
        persistJob(job);
    }

    /** Applies a panel parent state as one atomic operator edit and persists once. */
    public void setPanelEnabled(Job job, PanelLocation panelLocation, boolean enabled) throws Exception {
        requireJob(job);
        setLocationTreeEnabled(job, panelLocation, enabled);
        persistJob(job);
    }

    private void setLocationTreeEnabled(Job job, PlacementsHolderLocation<?> location, boolean enabled) {
        location.setLocallyEnabled(enabled);
        job.storeEnabledState(location, null, enabled);
        if (location instanceof PanelLocation) {
            for (PlacementsHolderLocation<?> child : ((PanelLocation) location).getChildren()) {
                setLocationTreeEnabled(job, child, enabled);
            }
        }
    }

    public void setPlacementEnabled(Job job, PlacementsHolderLocation<?> boardLocation,
            Placement placement, boolean enabled) throws Exception {
        requireJob(job);
        placement.setEnabled(enabled);
        job.storeEnabledState(boardLocation, placement, enabled);
        persistJob(job);
    }

    public void setPlacementPlaced(Job job, PlacementsHolderLocation<?> boardLocation,
            Placement placement, boolean placed) throws Exception {
        requireJob(job);
        job.storePlacedStatus(boardLocation, placement.getId(), placed);
        persistJob(job);
    }

    public int setDispenseGloballyEnabled(Job job, boolean enabled) throws Exception {
        requireJob(job);
        int changed = 0;
        if (!enabled) {
            globallyDisabledDispenseKeys.clear();
            for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
                for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                    if (placement.getType() == Placement.Type.Dispense && placement.isEnabled()) {
                        placement.setEnabled(false);
                        job.storeEnabledState(boardLocation, placement, false);
                        globallyDisabledDispenseKeys.add(key(boardLocation, placement));
                        changed++;
                    }
                }
            }
        }
        else {
            Set<String> restoredKeys = new HashSet<>(globallyDisabledDispenseKeys);
            for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
                for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                    if (placement.getType() == Placement.Type.Dispense
                            && restoredKeys.contains(key(boardLocation, placement))) {
                        placement.setEnabled(true);
                        job.storeEnabledState(boardLocation, placement, true);
                        changed++;
                    }
                }
            }
            globallyDisabledDispenseKeys.clear();
        }
        persistJob(job);
        return changed;
    }

    public int resetJedecTray(Job job, JEDEC_TrayFeeder feeder) throws IOException {
        feeder.setFeedCount(0);
        saveTrayProgress(job, feeder);
        return feeder.getFeedCount();
    }

    public int setJedecTrayStartingPosition(Job job, JEDEC_TrayFeeder feeder, int displayPosition) throws IOException {
        int max = Math.max(1, feeder.getEffectiveTrayCountRows() * feeder.getEffectiveTrayCountCols());
        int position = Math.max(1, Math.min(displayPosition, max));
        // feedCount is the number of pockets already consumed. JEDEC_TrayFeeder.feed()
        // increments it before picking, so displayed pocket N must store N - 1. This also
        // keeps the canvas's "Next pick" marker on the pocket chosen by the operator.
        feeder.setFeedCount(position - 1);
        saveTrayProgress(job, feeder);
        return feeder.getFeedCount();
    }

    public void setFeederEnabled(Feeder feeder, boolean enabled) throws Exception {
        feeder.setEnabled(enabled);
        // Feeder enabled state is part of machine configuration in OpenPnP, not job state.
        Configuration.get().save();
    }

    public void restoreTrayProgress(Job job, Iterable<JEDEC_TrayFeeder> feeders) throws IOException {
        if (job == null || job.getFile() == null) {
            return;
        }
        Properties properties = loadProgress(job);
        for (JEDEC_TrayFeeder feeder : feeders) {
            String value = properties.getProperty(feederKey(feeder));
            if (value != null) {
                try {
                    feeder.setFeedCount(Integer.parseInt(value));
                }
                catch (NumberFormatException ignored) {
                    // Ignore corrupt operator progress and leave the live feeder state untouched.
                }
            }
        }
    }

    public static String key(PlacementsHolderLocation<?> boardLocation, Placement placement) {
        return boardLocation.getUniqueId() + PlacementsHolderLocation.ID_DELIMITTER + placement.getId();
    }

    public static int displayPositionToFeedCount(int displayPosition) {
        return Math.max(1, displayPosition);
    }

    public static File progressFile(Job job) {
        File file = job.getFile();
        return new File(file.getParentFile(), file.getName() + ".operator-progress.properties");
    }

    void persistJob(Job job) throws Exception {
        if (job.getFile() != null) {
            Configuration.get().saveJob(job, job.getFile());
        }
        else {
            job.setDirty(true);
        }
    }

    private void saveTrayProgress(Job job, JEDEC_TrayFeeder feeder) throws IOException {
        if (job == null || job.getFile() == null) {
            return;
        }
        Properties properties = loadProgress(job);
        properties.setProperty(feederKey(feeder), Integer.toString(feeder.getFeedCount()));
        try (FileOutputStream out = new FileOutputStream(progressFile(job))) {
            properties.store(out, "OpenPnP operator tray progress");
        }
    }

    private Properties loadProgress(Job job) throws IOException {
        Properties properties = new Properties();
        File file = progressFile(job);
        if (file.isFile()) {
            try (FileInputStream in = new FileInputStream(file)) {
                properties.load(in);
            }
        }
        return properties;
    }

    private String feederKey(JEDEC_TrayFeeder feeder) {
        return "jedec." + (feeder.getId() == null ? feeder.getName() : feeder.getId()) + ".feedCount";
    }

    private void requireJob(Job job) {
        if (job == null) {
            throw new IllegalStateException("No operator job is loaded.");
        }
    }
}
