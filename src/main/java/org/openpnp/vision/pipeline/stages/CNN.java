package org.openpnp.vision.pipeline.stages;

import java.util.ArrayList;
import java.util.List;

import javax.swing.SwingUtilities;

import org.opencv.core.Mat;
import org.openpnp.model.Configuration;
import org.openpnp.vision.cnn.CnnModelManager;
import org.openpnp.vision.cnn.ProbabilityMap;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.CvStage;
import org.openpnp.vision.pipeline.Property;
import org.openpnp.vision.pipeline.Stage;
import org.openpnp.vision.pipeline.TerminalException;
import org.openpnp.vision.pipeline.ui.PipelinePropertySheetTable;
import org.simpleframework.xml.Attribute;

import com.l2fprod.common.beans.editor.ComboBoxPropertyEditor;

/** Native segmentation only; downstream stages decide which features are suitable for a job. */
@Stage(category = "Image Processing", description = "Run a shared CPU ONNX segmentation model. "
        + "The model result is a native-resolution CV_32FC1 probability Mat; the working image is preserved. "
        + "Use ScriptRun to interpret probabilities and return the geometry required by the consumer.")
public class CNN extends CvStage {
    @Attribute(required = false)
    @Property(description = "Identifier of a model installed under the OpenPnP configuration models directory.")
    private String modelId = "";

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    @Override
    public Result process(CvPipeline pipeline) throws Exception {
        try {
            if (SwingUtilities.isEventDispatchThread()) {
                throw new IllegalStateException("CNN inference must run off the Swing event-dispatch thread.");
            }
            ProbabilityMap probabilities = CnnModelManager.get(Configuration.get()).infer(modelId,
                    pipeline.getWorkingImage(), pipeline.getWorkingColorSpace(), null);
            Mat model = pipeline.ownResultModel(probabilities.toMat());
            // The pipeline owns this per-frame matrix. A null result image keeps camera pixels intact.
            return new Result(null, model);
        }
        catch (Exception e) {
            throw new TerminalException(new Exception("CNN model '" + modelId + "': " + e.getMessage(), e));
        }
    }

    @Override
    public void customizePropertySheet(PipelinePropertySheetTable table, CvPipeline pipeline) {
        super.customizePropertySheet(table, pipeline);
        List<String> identifiers = new ArrayList<>(CnnModelManager.get(Configuration.get()).listModelIds());
        if (modelId != null && !identifiers.contains(modelId)) {
            identifiers.add(modelId);
        }
        ComboBoxPropertyEditor editor = new ComboBoxPropertyEditor();
        editor.setAvailableValues(identifiers.toArray(new String[0]));
        for (com.l2fprod.common.propertysheet.Property property : table.getSheetModel().getProperties()) {
            if ("modelId".equals(property.getName())) {
                table.getEditorRegistry().registerEditor(property, editor);
            }
        }
    }
}
