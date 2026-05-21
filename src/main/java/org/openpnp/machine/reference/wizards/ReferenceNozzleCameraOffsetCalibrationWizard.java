package org.openpnp.machine.reference.wizards;

import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.JButton;
import javax.swing.border.TitledBorder;

import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.machine.reference.ReferenceNozzle;
import org.openpnp.machine.reference.ReferenceNozzleCameraOffsetCalibration;
import org.openpnp.util.UiUtils;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

public class ReferenceNozzleCameraOffsetCalibrationWizard extends AbstractConfigurationWizard {
    private final ReferenceNozzle nozzle;
    private final ReferenceNozzleCameraOffsetCalibration calibration;
    private JCheckBox enabled;
    private JComboBox<ReferenceNozzleCameraOffsetCalibration.RecalibrationTrigger> recalibrationTrigger;
    private JCheckBox failHoming;
    private JLabel failHomingLabel;
    private JTextField lastResultSummary;

    public ReferenceNozzleCameraOffsetCalibrationWizard(ReferenceNozzle nozzle) {
        this.nozzle = nozzle;
        this.calibration = nozzle.getCameraOffsetCalibration();

        JPanel panel = new JPanel();
        panel.setBorder(new TitledBorder(null,
                "Precision Camera Offset Calibration (Pipeline-Driven)",
                TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(panel);
        panel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC,
                ColumnSpec.decode("default:grow")
        }, new RowSpec[] {
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC,
                FormSpecs.RELATED_GAP_ROWSPEC,
                FormSpecs.DEFAULT_ROWSPEC
        }));

        JLabel description = new JLabel("Use this tab for automated, vision pipeline-driven nozzle camera offset calibration settings.");
        panel.add(description, "2, 2, 3, 1");

        enabled = new JCheckBox("Enable precision automated calibration settings for this nozzle");
        panel.add(enabled, "2, 4, 3, 1");

        JLabel triggerLabel = new JLabel("Calibration trigger");
        panel.add(triggerLabel, "2, 6");

        recalibrationTrigger = new JComboBox<>(ReferenceNozzleCameraOffsetCalibration.RecalibrationTrigger.values());
        recalibrationTrigger.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                adaptDialog();
            }
        });
        panel.add(recalibrationTrigger, "4, 6, fill, default");

        failHomingLabel = new JLabel("Fail homing");
        panel.add(failHomingLabel, "2, 8");

        failHoming = new JCheckBox("");
        panel.add(failHoming, "4, 8");

        JLabel lastResultLabel = new JLabel("Last calibration result");
        panel.add(lastResultLabel, "2, 10");

        lastResultSummary = new JTextField();
        lastResultSummary.setEditable(false);
        panel.add(lastResultSummary, "4, 10, fill, default");


        JButton runCalibrationButton = new JButton("Run precision camera-offset calibration now");
        runCalibrationButton.addActionListener((e) -> UiUtils.submitUiMachineTask(() -> {
            nozzle.calibrateCameraOffset(true);
            return null;
        }));
        panel.add(runCalibrationButton, "2, 12, 3, 1");

        JLabel help = new JLabel("Manual mark-and-measure offset setup remains in the separate \"Nozzle Offset Wizard\" tab.");
        panel.add(help, "2, 14, 3, 1");
    }

    protected void adaptDialog() {
        boolean calibratesOnHoming = recalibrationTrigger.getSelectedItem() == ReferenceNozzleCameraOffsetCalibration.RecalibrationTrigger.MachineHome;
        failHomingLabel.setVisible(calibratesOnHoming);
        failHoming.setVisible(calibratesOnHoming);
    }

    @Override
    public void createBindings() {
        addWrappedBinding(calibration, "enabled", enabled, "selected");
        addWrappedBinding(calibration, "recalibrationTrigger", recalibrationTrigger, "selectedItem");
        addWrappedBinding(calibration, "failHoming", failHoming, "selected");
        addWrappedBinding(calibration, "lastResultSummary", lastResultSummary, "text");
        adaptDialog();
    }
}
