package org.openpnp.gui.operator;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;

public class OperatorJobEditingService {
    private final Set<String> globallyDisabledDispenseKeys = new HashSet<>();

    public void resetBoardsOnly(Job job) throws Exception {
        requireJob(job);
        job.removeAllPlacedStatus();
        for (PlacementsHolderLocation<?> boardLocation : job.getBoardLocations()) {
            setBoardEnabled(job, boardLocation, true);
            for (Placement placement : boardLocation.getPlacementsHolder().getPlacements()) {
                if (placement.getType() == Placement.Type.Placement
                        || placement.getType() == Placement.Type.Dispense) {
                    setPlacementEnabled(job, boardLocation, placement, true);
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
        // JEDEC_TrayFeeder uses feedCount 0 for a fresh tray. After the operator chooses
        // displayed pocket N as the next pocket, store N so previous positions render as consumed.
        feeder.setFeedCount(position);
        saveTrayProgress(job, feeder);
        return feeder.getFeedCount();
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

    private void persistJob(Job job) throws Exception {
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
