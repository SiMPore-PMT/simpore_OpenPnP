package org.openpnp.vision.cnn;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Set;

import org.openpnp.machine.pandaplacer.AbstractPandaplacerVisionFeeder;
import org.openpnp.machine.reference.ReferenceNozzleTip;
import org.openpnp.machine.reference.camera.ReferenceCamera;
import org.openpnp.machine.reference.feeder.AdvancedLoosePartFeeder;
import org.openpnp.machine.reference.feeder.BlindsFeeder;
import org.openpnp.machine.reference.feeder.ReferenceHeapFeeder;
import org.openpnp.machine.reference.feeder.ReferenceLoosePartFeeder;
import org.openpnp.machine.reference.feeder.ReferencePushPullFeeder;
import org.openpnp.machine.reference.feeder.ReferenceStripFeeder;
import org.openpnp.machine.reference.vision.ReferenceFiducialLocator;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.Configuration;
import org.openpnp.spi.Camera;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.NozzleTip;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.stages.CNN;
import org.pmw.tinylog.Logger;

/** Finds CNN references in configuration-owned pipelines. Runtime clones remain covered by load-on-use. */
public final class CnnPipelineDiscovery {
    private CnnPipelineDiscovery() {
    }

    public static Set<String> findReferencedModelIds(Machine machine) {
        if (machine == null) {
            return Collections.emptySet();
        }
        Set<String> modelIds = new LinkedHashSet<>();
        Set<CvPipeline> visited = Collections.newSetFromMap(new IdentityHashMap<CvPipeline, Boolean>());

        for (AbstractVisionSettings settings : Configuration.get().getVisionSettings()) {
            try {
                collect(settings.getPipeline(), visited, modelIds);
            }
            catch (RuntimeException e) {
                Logger.warn(e, "Unable to inspect vision settings \"{}\" for CNN references.",
                        settings.getId());
            }
        }
        for (Feeder feeder : machine.getFeeders()) {
            try {
                collectFeeder(feeder, visited, modelIds);
            }
            catch (RuntimeException e) {
                Logger.warn(e, "Unable to inspect feeder \"{}\" for CNN references.", feeder.getName());
            }
        }
        if (machine.getFiducialLocator() instanceof ReferenceFiducialLocator) {
            try {
                collect(((ReferenceFiducialLocator) machine.getFiducialLocator()).getPipeline(),
                        visited, modelIds);
            }
            catch (RuntimeException e) {
                Logger.warn(e, "Unable to inspect fiducial pipeline for CNN references.");
            }
        }
        for (NozzleTip nozzleTip : machine.getNozzleTips()) {
            if (nozzleTip instanceof ReferenceNozzleTip) {
                try {
                    collect(((ReferenceNozzleTip) nozzleTip).getCalibration().getPipeline(), visited,
                            modelIds);
                }
                catch (RuntimeException e) {
                    Logger.warn(e, "Unable to inspect nozzle-tip calibration for CNN references.");
                }
            }
        }
        for (Camera camera : machine.getAllCameras()) {
            if (camera instanceof ReferenceCamera) {
                try {
                    collect(((ReferenceCamera) camera).getAdvancedCalibration().getPipeline(), visited,
                            modelIds);
                }
                catch (RuntimeException e) {
                    Logger.warn(e, "Unable to inspect camera calibration for CNN references.");
                }
            }
        }
        return Collections.unmodifiableSet(modelIds);
    }

    private static void collectFeeder(Feeder feeder, Set<CvPipeline> visited, Set<String> modelIds) {
        if (feeder instanceof AbstractPandaplacerVisionFeeder) {
            collect(((AbstractPandaplacerVisionFeeder) feeder).getPipeline(), visited, modelIds);
        }
        else if (feeder instanceof AdvancedLoosePartFeeder) {
            AdvancedLoosePartFeeder typed = (AdvancedLoosePartFeeder) feeder;
            collect(typed.getPipeline(), visited, modelIds);
            collect(typed.getTrainingPipeline(), visited, modelIds);
        }
        else if (feeder instanceof ReferenceLoosePartFeeder) {
            collect(((ReferenceLoosePartFeeder) feeder).getPipeline(), visited, modelIds);
        }
        else if (feeder instanceof ReferenceHeapFeeder) {
            ReferenceHeapFeeder typed = (ReferenceHeapFeeder) feeder;
            collect(typed.getFeederPipeline(), visited, modelIds);
            collect(typed.getTrainingPipeline(), visited, modelIds);
            // Drop boxes are machine configuration shared by all heap feeders.
            for (ReferenceHeapFeeder.DropBox dropBox : ReferenceHeapFeeder.getDropBoxes()) {
                collect(dropBox.getPartPipeline(), visited, modelIds);
            }
        }
        else if (feeder instanceof ReferencePushPullFeeder) {
            collect(((ReferencePushPullFeeder) feeder).getPipeline(), visited, modelIds);
        }
        else if (feeder instanceof ReferenceStripFeeder) {
            collect(((ReferenceStripFeeder) feeder).getPipeline(), visited, modelIds);
        }
        else if (feeder instanceof BlindsFeeder) {
            collect(((BlindsFeeder) feeder).getPipeline(), visited, modelIds);
        }
    }

    private static void collect(CvPipeline pipeline, Set<CvPipeline> visited, Set<String> modelIds) {
        if (pipeline == null || !visited.add(pipeline)) {
            return;
        }
        for (CvStage stage : pipeline.getStages()) {
            if (stage instanceof CNN && stage.isEnabled()) {
                String modelId = ((CNN) stage).getModelId();
                if (modelId != null && !modelId.trim().isEmpty()) {
                    modelIds.add(modelId.trim());
                }
                else {
                    Logger.warn("Enabled CNN stage \"{}\" has no model identifier.", stage.getName());
                }
            }
        }
    }
}
