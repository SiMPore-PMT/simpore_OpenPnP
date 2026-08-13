/*
 * Copyright (C) 2025 Your Name
 *
 * This file is part of OpenPnP.
 *
 * OpenPnP is free software: you can redistribute it and/or modify it under the terms of the GNU
 * General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * OpenPnP is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even
 * the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General
 * Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with OpenPnP. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.machine.reference.feeder.wizards;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSeparator;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.border.TitledBorder;

import org.jdesktop.beansbinding.Converter;
import org.jdesktop.beansbinding.AutoBinding.UpdateStrategy;
import org.openpnp.Translations;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.components.ComponentDecorators;
import org.openpnp.gui.components.LocationButtonsPanel;
import org.openpnp.gui.support.AbstractConfigurationWizard;
import org.openpnp.gui.support.DoubleConverter;
import org.openpnp.gui.support.IdentifiableListCellRenderer;
import org.openpnp.gui.support.IntegerConverter;
import org.openpnp.gui.support.LengthConverter;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.gui.support.MutableLocationProxy;
import org.openpnp.gui.support.NamedListCellRenderer;
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.gui.support.VisionSettingsComboBoxModel;
import org.openpnp.model.Configuration;
import org.openpnp.model.AbstractVisionSettings;
import org.openpnp.model.FiducialVisionSettings;
import org.openpnp.model.Length;
import org.openpnp.model.LengthUnit;
import org.openpnp.model.Location;
import org.openpnp.model.Part;
import org.openpnp.util.UiUtils;
import org.openpnp.util.Utils2D;
import org.openpnp.vision.pipeline.CvPipeline;
import org.openpnp.vision.pipeline.ui.CvPipelineEditor;
import org.openpnp.vision.pipeline.ui.CvPipelineEditorDialog;

// *** Change this import/type to your actual backend class ***
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.FirstRasterDirection;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.RasterPattern;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder.StartCorner;

import com.jgoodies.forms.layout.ColumnSpec;
import com.jgoodies.forms.layout.FormLayout;
import com.jgoodies.forms.layout.FormSpecs;
import com.jgoodies.forms.layout.RowSpec;

@SuppressWarnings("serial")
public class JEDEC_TrayFeederConfigurationWizard extends AbstractConfigurationWizard {
    private static final double RIGHT_ANGLE_TOLERANCE = 2.5; // degrees
    private static final LengthUnit VALIDATION_UNITS = LengthUnit.Millimeters;
    private static final double VALIDATION_TOLERANCE = 0.03;

    private final JEDEC_TrayFeeder feeder;

    // ---------- UI fields copied from ReferenceRotatedTrayFeederConfigurationWizard ----------
    private JTextField textFieldOffsetsX;
    private JTextField textFieldOffsetsY;

    private JTextField textFieldFeedCount;

    private JPanel panelLocation;
    private JPanel panelParameters;
    private JLabel lblX_1;
    private JLabel lblY_1;
    private JLabel lblComponentCount;
    private JTextField textFieldLocationX;
    private JTextField textFieldLocationY;
    private JTextField textFieldFirstRowLastLocationX;
    private JTextField textFieldFirstRowLastLocationY;
    private JTextField textFieldLastLocationX;
    private JTextField textFieldLastLocationY;

    private JTextField textFieldTrayCountCols;
    private JTextField textFieldTrayCountRows;
    private JTextField textFieldTrayRotation;
    private JComboBox<Double> comboBoxComponentRotation;
    private JTextField textFieldComponentZHeight;

    private JPanel panelPart;
    private JComboBox<?> comboBoxPart;
    private LocationButtonsPanel locationButtonsPanel;
    private LocationButtonsPanel lastLocationButtonsPanel;
    private JTextField retryCountTf;
    private JTextField pickRetryCount;
    private JComboBox<AbstractVisionSettings> fiducialVisionSettingsCombo;
    private JCheckBox useAdvancedCameraCalibration;
    private JCheckBox useAsyncGcodeMotion;
    private JTextField recenterToleranceMm;
    private JTextField recenterMaxPasses;
    private JCheckBox rotateNozzleAtPick;
    private JCheckBox useDetectedAngleForPickRotation;
    private TrayPreviewPanel trayPreviewPanel;
    private JLabel secondRasterDirectionLabel;
    private JRadioButton firstDirectionRow;
    private JRadioButton firstDirectionColumn;
    private JRadioButton patternZigZag;
    private JRadioButton patternSnake;

    private MutableLocationProxy firstRowFirstColumn = new MutableLocationProxy();
    private MutableLocationProxy firstRowLastColumn = new MutableLocationProxy();
    private MutableLocationProxy lastRowLastColumn = new MutableLocationProxy();
    private MutableLocationProxy offsetsAndRotation = new MutableLocationProxy();
    private int nRows;
    private int nCols;
    private int wizardFeedCount;

    public JEDEC_TrayFeederConfigurationWizard(JEDEC_TrayFeeder feeder) {
        this.feeder = feeder;

        // ---------------- General / Part panel ----------------
        panelPart = new JPanel();
        panelPart.setBorder(new TitledBorder(null,
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.GeneralSettings"),
                TitledBorder.LEADING, TitledBorder.TOP, null));
        contentPanel.add(panelPart);
        panelPart.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"), },
                new RowSpec[] { FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, }));

        comboBoxPart = new JComboBox();
        try {
            comboBoxPart.setModel(new PartsComboBoxModel());
        } catch (Throwable t) {
            // ignore in WindowBuilder parsing
        }
        JLabel lblPart = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.Part"));
        panelPart.add(lblPart, "2, 2, right, default");
        comboBoxPart.setRenderer(new IdentifiableListCellRenderer<Part>());
        panelPart.add(comboBoxPart, "4, 2, left, default");

        JLabel lblRetryCount = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.FeedRetryCount"));
        panelPart.add(lblRetryCount, "2, 4, right, default");

        retryCountTf = new JTextField();
        retryCountTf.setText("3");
        retryCountTf.setColumns(3);
        panelPart.add(retryCountTf, "4, 4");

        JLabel lblPickRetryCount = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.PickRetryCount"));
        panelPart.add(lblPickRetryCount, "2, 6, right, default");

        pickRetryCount = new JTextField();
        pickRetryCount.setText("3");
        pickRetryCount.setColumns(3);
        panelPart.add(pickRetryCount, "4, 6, fill, default");

        // ---------------- Locations panel ----------------
        panelLocation = new JPanel();
        panelLocation.setBorder(new TitledBorder(null,
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.TrayComponentLocations"),
                TitledBorder.LEADING, TitledBorder.TOP, null));
        contentPanel.add(panelLocation);
        panelLocation.setLayout(new FormLayout(
                new ColumnSpec[] { FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"), },
                new RowSpec[] { FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC }));

        JLabel firstComponent = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.FirstRowFirstColumn"));
        panelLocation.add(firstComponent, "2, 4");

        lblX_1 = new JLabel(Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.X"));
        panelLocation.add(lblX_1, "4, 2");

        lblY_1 = new JLabel(Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.Y"));
        panelLocation.add(lblY_1, "6, 2");

        textFieldLocationX = new JTextField();
        textFieldLocationX.setColumns(6);
        panelLocation.add(textFieldLocationX, "4, 4");

        textFieldLocationY = new JTextField();
        textFieldLocationY.setColumns(6);
        panelLocation.add(textFieldLocationY, "6, 4");

        JLabel firstRowLastComponent = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.FirstRowLastColumn"));
        panelLocation.add(firstRowLastComponent, "2, 6");

        textFieldFirstRowLastLocationX = new JTextField();
        textFieldFirstRowLastLocationX.setColumns(6);
        panelLocation.add(textFieldFirstRowLastLocationX, "4, 6");

        textFieldFirstRowLastLocationY = new JTextField();
        textFieldFirstRowLastLocationY.setColumns(6);
        panelLocation.add(textFieldFirstRowLastLocationY, "6, 6");

        lastLocationButtonsPanel = new LocationButtonsPanel(textFieldFirstRowLastLocationX, textFieldFirstRowLastLocationY, null, null);
        panelLocation.add(lastLocationButtonsPanel, "8, 6");

        JLabel lastComponent = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.LastRowLastColumn"));
        panelLocation.add(lastComponent, "2, 8");

        textFieldLastLocationX = new JTextField();
        textFieldLastLocationX.setColumns(6);
        panelLocation.add(textFieldLastLocationX, "4, 8");

        textFieldLastLocationY = new JTextField();
        textFieldLastLocationY.setColumns(6);
        panelLocation.add(textFieldLastLocationY, "6, 8");

        lastLocationButtonsPanel = new LocationButtonsPanel(textFieldLastLocationX, textFieldLastLocationY, null, null);
        panelLocation.add(lastLocationButtonsPanel, "8, 8");

        // ---------------- Parameters panel ----------------
        panelParameters = new JPanel();
        panelParameters.setBorder(new TitledBorder(null,
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.TrayParameters"),
                TitledBorder.LEADING, TitledBorder.TOP, null));
        contentPanel.add(panelParameters);
        panelParameters.setLayout(new FormLayout(
                new ColumnSpec[] { FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"),
                        FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("left:default:grow"), },
                new RowSpec[] { FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC }));

        JLabel lblTrayRows = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.NumberOfRows"));
        panelParameters.add(lblTrayRows, "2, 2");

        textFieldTrayCountRows = new JTextField();
        textFieldTrayCountRows.setColumns(10);
        panelParameters.add(textFieldTrayCountRows, "4, 2");

        JLabel lblTrayCols = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.NumberOfColumns"));
        panelParameters.add(lblTrayCols, "6, 2");

        textFieldTrayCountCols = new JTextField();
        textFieldTrayCountCols.setColumns(10);
        panelParameters.add(textFieldTrayCountCols, "8, 2");

        JLabel lblFeedCount = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.FeedCount"));
        panelParameters.add(lblFeedCount, "2, 4");

        textFieldFeedCount = new JTextField();
        textFieldFeedCount.setColumns(10);
        panelParameters.add(textFieldFeedCount, "4, 4");

        lblComponentCount = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ComponentsRemaining"));
        panelParameters.add(lblComponentCount, "6, 4");

        JButton btnResetFeedCount = new JButton(new AbstractAction(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.Reset")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                feeder.setFeedCount(0);
            }
        });
        btnResetFeedCount.setHorizontalAlignment(SwingConstants.LEFT);
        panelParameters.add(btnResetFeedCount, "8, 4, left, default");

        JLabel lblComponentRotation = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ComponentRotation"));
        panelParameters.add(lblComponentRotation, "2, 6");

        comboBoxComponentRotation = new JComboBox<>(new Double[] { 0.0, 90.0, 180.0, 270.0 });
        comboBoxComponentRotation.setToolTipText(
                "Component orientation in the tray. This is not used for pick rotation unless advanced pick rotation is enabled.");
        panelParameters.add(comboBoxComponentRotation, "4, 6");

        JLabel lblComponentZHeight = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ZHeight"));
        panelParameters.add(lblComponentZHeight, "6, 6");

        textFieldComponentZHeight = new JTextField();
        textFieldComponentZHeight.setColumns(10);
        panelParameters.add(textFieldComponentZHeight, "8, 6");

        JSeparator separator = new JSeparator();
        panelParameters.add(separator, "1, 9, 8, 1");

        JButton btnCalcOffsetsRotation = new JButton(new AbstractAction(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.CalculateOffsetsAndTrayRotation")) {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    applyTrayGridDefinition();
                } catch (Exception e1) {
                    MessageBoxes.errorBox(getTopLevelAncestor(),
                            Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.Error"),
                            e1.getMessage());
                    return;
                }
            }
        });
        btnCalcOffsetsRotation.setHorizontalAlignment(SwingConstants.LEFT);
        panelParameters.add(btnCalcOffsetsRotation, "2, 12");

        JLabel lblRowOffset = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ColumnOffset"));
        panelParameters.add(lblRowOffset, "2, 14");

        textFieldOffsetsX = new JTextField();
        textFieldOffsetsX.setColumns(10);
        panelParameters.add(textFieldOffsetsX, "4, 14");

        JLabel lblColOffset = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.RowOffset"));
        panelParameters.add(lblColOffset, "6, 14");

        textFieldOffsetsY = new JTextField();
        textFieldOffsetsY.setColumns(10);
        panelParameters.add(textFieldOffsetsY, "8, 14, ");

        JLabel lblTrayRotation = new JLabel(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.TrayRotation"));
        panelParameters.add(lblTrayRotation, "2, 16");

        textFieldTrayRotation = new JTextField();
        textFieldTrayRotation.setColumns(10);
        textFieldTrayRotation
                .setToolTipText(Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.TrayRotation.ToolTip"));
        panelParameters.add(textFieldTrayRotation, "4, 16");

        // Location buttons (needs location & rotation fields initialized)
        locationButtonsPanel = new LocationButtonsPanel(textFieldLocationX, textFieldLocationY, null, textFieldTrayRotation);
        panelLocation.add(locationButtonsPanel, "8, 4");

        // ---------------- Raster selection and preview ----------------
        JPanel rasterPanel = new JPanel();
        rasterPanel.setBorder(new TitledBorder(null, "JEDEC Tray Raster Preview",
                TitledBorder.LEADING, TitledBorder.TOP, null));
        rasterPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC,
                FormSpecs.RELATED_GAP_COLSPEC, ColumnSpec.decode("default:grow"), },
                new RowSpec[] { FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, RowSpec.decode("160dlu"), }));
        contentPanel.add(rasterPanel);

        firstDirectionRow = new JRadioButton("ROW");
        firstDirectionColumn = new JRadioButton("COLUMN");
        ButtonGroup firstDirectionGroup = new ButtonGroup();
        firstDirectionGroup.add(firstDirectionRow);
        firstDirectionGroup.add(firstDirectionColumn);
        rasterPanel.add(new JLabel("First raster direction"), "2, 2");
        rasterPanel.add(firstDirectionRow, "4, 2");
        rasterPanel.add(firstDirectionColumn, "6, 2");

        patternZigZag = new JRadioButton("ZIG_ZAG");
        patternSnake = new JRadioButton("SNAKE");
        ButtonGroup patternGroup = new ButtonGroup();
        patternGroup.add(patternZigZag);
        patternGroup.add(patternSnake);
        rasterPanel.add(new JLabel("Raster pattern"), "2, 4");
        rasterPanel.add(patternZigZag, "4, 4");
        rasterPanel.add(patternSnake, "6, 4");

        secondRasterDirectionLabel = new JLabel();
        rasterPanel.add(secondRasterDirectionLabel, "8, 2");

        trayPreviewPanel = new TrayPreviewPanel();
        rasterPanel.add(trayPreviewPanel, "2, 6, 7, 1, fill, fill");

        firstDirectionRow.addActionListener(e -> updateRasterFromControls());
        firstDirectionColumn.addActionListener(e -> updateRasterFromControls());
        patternZigZag.addActionListener(e -> updateRasterFromControls());
        patternSnake.addActionListener(e -> updateRasterFromControls());

        // ---------------- Vision panel (from AdvancedLoosePart wizard) ----------------
        // Warning banner
        JPanel warningPanel = new JPanel();
        contentPanel.add(warningPanel, 0);
        JLabel lblWarning = new JLabel(
                "Warning: This feeder is incomplete and experimental. Use at your own risk.");
        lblWarning.setFont(new Font("Lucida Grande", Font.PLAIN, 16));
        lblWarning.setForeground(Color.RED);
        lblWarning.setHorizontalAlignment(SwingConstants.LEFT);
        warningPanel.add(lblWarning);

        // Vision controls
        JPanel visionPanel = new JPanel();
        visionPanel.setBorder(new TitledBorder(null, "Vision", TitledBorder.LEADING, TitledBorder.TOP, null, null));
        contentPanel.add(visionPanel);
        visionPanel.setLayout(new FormLayout(new ColumnSpec[] {
                FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC,
                FormSpecs.DEFAULT_COLSPEC, FormSpecs.RELATED_GAP_COLSPEC, FormSpecs.DEFAULT_COLSPEC, },
                new RowSpec[] { FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC,
                        FormSpecs.DEFAULT_ROWSPEC, FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC,
                        FormSpecs.RELATED_GAP_ROWSPEC, FormSpecs.DEFAULT_ROWSPEC, }));

        JLabel lblFiducialVisionSettings = new JLabel("Top Vision Alignment Model");
        visionPanel.add(lblFiducialVisionSettings, "2, 2");

        fiducialVisionSettingsCombo = new JComboBox<>(new VisionSettingsComboBoxModel<>(FiducialVisionSettings.class));
        fiducialVisionSettingsCombo.setMaximumRowCount(20);
        fiducialVisionSettingsCombo.setRenderer(new NamedListCellRenderer<>());
        visionPanel.add(fiducialVisionSettingsCombo, "4, 2, 3, 1");

        JLabel lblFeedPipeline = new JLabel("Active Pick Pipeline");
        visionPanel.add(lblFeedPipeline, "2, 4");

        JButton btnEditPipeline = new JButton("Edit");
        btnEditPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            editPipeline();
        }));
        visionPanel.add(btnEditPipeline, "4, 4");

        JButton btnResetPipeline = new JButton("Reset");
        btnResetPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            resetPipeline();
        }));
        visionPanel.add(btnResetPipeline, "6, 4");

        useAdvancedCameraCalibration = new JCheckBox("Use Advanced Camera Calibration");
        visionPanel.add(useAdvancedCameraCalibration, "2, 6, 3, 1");

        useAsyncGcodeMotion = new JCheckBox("Async Gcode Recenter Waits");
        visionPanel.add(useAsyncGcodeMotion, "6, 6");

        JLabel lblRecenterToleranceMm = new JLabel("Recenter Tolerance (mm)");
        visionPanel.add(lblRecenterToleranceMm, "2, 8");

        recenterToleranceMm = new JTextField();
        recenterToleranceMm.setColumns(10);
        visionPanel.add(recenterToleranceMm, "4, 8");

        JLabel lblRecenterMaxPasses = new JLabel("Recenter Max Passes");
        visionPanel.add(lblRecenterMaxPasses, "2, 10");

        recenterMaxPasses = new JTextField();
        recenterMaxPasses.setColumns(10);
        visionPanel.add(recenterMaxPasses, "4, 10");

        rotateNozzleAtPick = new JCheckBox("Rotate nozzle at pick");
        visionPanel.add(rotateNozzleAtPick, "2, 12, 5, 1");

        useDetectedAngleForPickRotation = new JCheckBox("Use detected angle for pick rotation");
        visionPanel.add(useDetectedAngleForPickRotation, "2, 14, 5, 1");

    }

    // ---------- Bindings ----------
    @Override
    public void createBindings() {
        LengthConverter lengthConverter = new LengthConverter();
        IntegerConverter intConverter = new IntegerConverter();
        DoubleConverter doubleConverter = new DoubleConverter(Configuration.get().getLengthDisplayFormat());

        // access GUI fields via proxies
        bind(UpdateStrategy.READ_WRITE, firstRowFirstColumn, "lengthX", textFieldLocationX, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, firstRowFirstColumn, "lengthY", textFieldLocationY, "text", lengthConverter);

        bind(UpdateStrategy.READ_WRITE, firstRowLastColumn, "lengthX", textFieldFirstRowLastLocationX, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, firstRowLastColumn, "lengthY", textFieldFirstRowLastLocationY, "text", lengthConverter);

        bind(UpdateStrategy.READ_WRITE, lastRowLastColumn, "lengthX", textFieldLastLocationX, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, lastRowLastColumn, "lengthY", textFieldLastLocationY, "text", lengthConverter);

        bind(UpdateStrategy.READ_WRITE, this, "nRows", textFieldTrayCountRows, "text", intConverter);
        bind(UpdateStrategy.READ_WRITE, this, "nCols", textFieldTrayCountCols, "text", intConverter);

        bind(UpdateStrategy.READ_WRITE, this, "wizardFeedCount", textFieldFeedCount, "text", intConverter);

        bind(UpdateStrategy.READ_WRITE, offsetsAndRotation, "lengthX", textFieldOffsetsX, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, offsetsAndRotation, "lengthY", textFieldOffsetsY, "text", lengthConverter);
        bind(UpdateStrategy.READ_WRITE, offsetsAndRotation, "rotation", textFieldTrayRotation, "text", doubleConverter);

        // feeder bindings (expect your JEDEC_TrayFeeder to expose same properties as rotated tray feeder)
        addWrappedBinding(feeder, "part", comboBoxPart, "selectedItem");
        addWrappedBinding(feeder, "feedRetryCount", retryCountTf, "text", intConverter);
        addWrappedBinding(feeder, "pickRetryCount", pickRetryCount, "text", intConverter);
        addWrappedBinding(feeder, "useAdvancedCameraCalibration", useAdvancedCameraCalibration, "selected");
        addWrappedBinding(feeder, "useAsyncGcodeMotion", useAsyncGcodeMotion, "selected");
        addWrappedBinding(feeder, "recenterToleranceMm", recenterToleranceMm, "text", doubleConverter);
        addWrappedBinding(feeder, "recenterMaxPasses", recenterMaxPasses, "text", intConverter);
        addWrappedBinding(feeder, "rotateNozzleAtPick", rotateNozzleAtPick, "selected");
        addWrappedBinding(feeder, "useDetectedAngleForPickRotation",
                useDetectedAngleForPickRotation, "selected");
        addWrappedBinding(feeder, "fiducialVisionSettingsId", fiducialVisionSettingsCombo, "selectedItem",
                new Converter<String, AbstractVisionSettings>() {
                    @Override
                    public AbstractVisionSettings convertForward(String id) {
                        if (id == null || id.isEmpty()) {
                            return null;
                        }
                        if (Configuration.get().getVisionSettings(id) instanceof AbstractVisionSettings) {
                            return (AbstractVisionSettings) Configuration.get().getVisionSettings(id);
                        }
                        return null;
                    }

                    @Override
                    public String convertReverse(AbstractVisionSettings visionSettings) {
                        return visionSettings == null ? null : visionSettings.getId();
                    }
                });

        // pick location, rotations, Z
        MutableLocationProxy location = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "location", location, "location");
        addWrappedBinding(location, "lengthX", textFieldLocationX, "text", lengthConverter);
        addWrappedBinding(location, "lengthY", textFieldLocationY, "text", lengthConverter);
        addWrappedBinding(location, "rotation", textFieldTrayRotation, "text", doubleConverter);
        addWrappedBinding(location, "lengthZ", textFieldComponentZHeight, "text", lengthConverter);

        addWrappedBinding(feeder, "componentRotationInTray", comboBoxComponentRotation, "selectedItem");

        MutableLocationProxy firstRowLastComponentlocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "firstRowLastComponentLocation", firstRowLastComponentlocation, "location");
        addWrappedBinding(firstRowLastComponentlocation, "lengthX", textFieldFirstRowLastLocationX, "text", lengthConverter);
        addWrappedBinding(firstRowLastComponentlocation, "lengthY", textFieldFirstRowLastLocationY, "text", lengthConverter);

        MutableLocationProxy lastComponentlocation = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "lastComponentLocation", lastComponentlocation, "location");
        addWrappedBinding(lastComponentlocation, "lengthX", textFieldLastLocationX, "text", lengthConverter);
        addWrappedBinding(lastComponentlocation, "lengthY", textFieldLastLocationY, "text", lengthConverter);

        // offsets
        MutableLocationProxy offsets = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "offsets", offsets, "location");
        addWrappedBinding(offsets, "lengthX", textFieldOffsetsX, "text", lengthConverter);
        addWrappedBinding(offsets, "lengthY", textFieldOffsetsY, "text", lengthConverter);

        // rows/cols + feed count
        addWrappedBinding(feeder, "trayCountCols", textFieldTrayCountCols, "text", intConverter);
        addWrappedBinding(feeder, "trayCountRows", textFieldTrayCountRows, "text", intConverter);
        addWrappedBinding(feeder, "feedCount", textFieldFeedCount, "text", intConverter);

        // remaining count label
        bind(UpdateStrategy.READ, feeder, "remainingCount", lblComponentCount, "text",
                new Converter<Integer, String>() {
                    @Override
                    public String convertForward(Integer count) {
                        return Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ComponentsRemaining")
                                + String.valueOf(count);
                    }
                    @Override
                    public Integer convertReverse(String s) {
                        return Integer.parseInt(s.substring(17));
                    }
                });

        // decorators
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldLocationX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldLocationY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldComponentZHeight);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldFirstRowLastLocationX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldFirstRowLastLocationY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldLastLocationX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldLastLocationY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldOffsetsX);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldOffsetsY);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldTrayRotation);
        ComponentDecorators.decorateWithAutoSelect(retryCountTf);
        ComponentDecorators.decorateWithAutoSelect(pickRetryCount);
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(recenterToleranceMm);
        ComponentDecorators.decorateWithAutoSelect(recenterMaxPasses);
        ComponentDecorators.decorateWithAutoSelect(textFieldTrayCountRows);
        ComponentDecorators.decorateWithAutoSelect(textFieldTrayCountCols);
        ComponentDecorators.decorateWithAutoSelect(textFieldFeedCount);
        initializeRasterControls();
    }

    // ---------- Properties for bindings ----------
    public int getnRows() { return nRows; }
    public void setnRows(int nRows) { this.nRows = nRows; }
    public int getnCols() { return nCols; }
    public void setnCols(int nCols) { this.nCols = nCols; }
    public int getwizardFeedCount() { return wizardFeedCount; }
    public void setwizardFeedCount(int wizardFeedCount) {
        int old = this.wizardFeedCount;
        this.wizardFeedCount = wizardFeedCount;
        firePropertyChange("wizardFeedCount", old, wizardFeedCount);
    }

    private void initializeRasterControls() {
        firstDirectionRow.setSelected(feeder.getFirstRasterDirection() == FirstRasterDirection.ROW);
        firstDirectionColumn.setSelected(feeder.getFirstRasterDirection() == FirstRasterDirection.COLUMN);
        patternZigZag.setSelected(feeder.getRasterPattern() == RasterPattern.ZIG_ZAG);
        patternSnake.setSelected(feeder.getRasterPattern() == RasterPattern.SNAKE);
        updatePreviewFromFeeder();
    }

    private void updateRasterFromControls() {
        feeder.setFirstRasterDirection(firstDirectionColumn.isSelected() ? FirstRasterDirection.COLUMN : FirstRasterDirection.ROW);
        feeder.setRasterPattern(patternSnake.isSelected() ? RasterPattern.SNAKE : RasterPattern.ZIG_ZAG);
        updatePreviewFromFeeder();
    }

    private void updatePreviewFromFeeder() {
        if (trayPreviewPanel == null) {
            return;
        }
        trayPreviewPanel.setRows(feeder.getEffectiveTrayCountRows());
        trayPreviewPanel.setCols(feeder.getEffectiveTrayCountCols());
        trayPreviewPanel.setStartCorner(feeder.getStartCorner());
        trayPreviewPanel.setFirstRasterDirection(feeder.getFirstRasterDirection());
        trayPreviewPanel.setRasterPattern(feeder.getRasterPattern());
        trayPreviewPanel.setApplied(!isZero(feeder.getColumnVector()) || !isZero(feeder.getRowVector()));
        secondRasterDirectionLabel.setText("Second raster direction: " + feeder.getSecondRasterDirectionDescription());
    }

    private boolean isZero(Location location) {
        Location mm = location.convertToUnits(LengthUnit.Millimeters);
        return Math.abs(mm.getX()) < 0.000001 && Math.abs(mm.getY()) < 0.000001;
    }

    private void applyTrayGridDefinition() throws Exception {
        TrayGridDefinition definition = calculateTrayGridDefinition();
        offsetsAndRotation.setLocation(definition.offsetsAndRotation);
        feeder.setGridOrigin(definition.gridOrigin);
        feeder.setColumnVector(definition.columnVector);
        feeder.setRowVector(definition.rowVector);
        feeder.setOffsets(definition.legacyOffsets);
        feeder.setLocation(feeder.getLocation().derive(
                definition.gridOrigin.getX(), definition.gridOrigin.getY(), null, definition.offsetsAndRotation.getRotation()));
        feeder.setFirstRowLastComponentLocation(firstRowLastColumn.getLocation());
        feeder.setLastComponentLocation(lastRowLastColumn.getLocation());
        updatePreviewFromFeeder();
    }

    private TrayGridDefinition calculateTrayGridDefinition() throws Exception {
        Location offsetsAndRotationLocation = calculateOffsetsAndRotation();
        LengthUnit units = Configuration.get().getSystemUnits();
        Location a = firstRowFirstColumn.getLocation().convertToUnits(units);
        Location b = firstRowLastColumn.getLocation().convertToUnits(units);
        Location c = lastRowLastColumn.getLocation().convertToUnits(units);
        Location columnVector = nCols > 1
                ? new Location(units, (b.getX() - a.getX()) / (nCols - 1), (b.getY() - a.getY()) / (nCols - 1), 0, 0)
                : new Location(units);
        Location rowVector = nRows > 1
                ? new Location(units, (c.getX() - b.getX()) / (nRows - 1), (c.getY() - b.getY()) / (nRows - 1), 0, 0)
                : new Location(units);
        Location legacyOffsets = new Location(units, columnVector.getLinearDistanceTo(new Location(units)),
                rowVector.getLinearDistanceTo(new Location(units)), 0, 0);
        return new TrayGridDefinition(a.derive(null, null, 0.0, 0.0), columnVector, rowVector, legacyOffsets,
                offsetsAndRotationLocation);
    }

    private static class TrayGridDefinition {
        final Location gridOrigin;
        final Location columnVector;
        final Location rowVector;
        final Location legacyOffsets;
        final Location offsetsAndRotation;

        TrayGridDefinition(Location gridOrigin, Location columnVector, Location rowVector, Location legacyOffsets,
                Location offsetsAndRotation) {
            this.gridOrigin = gridOrigin;
            this.columnVector = columnVector;
            this.rowVector = rowVector;
            this.legacyOffsets = legacyOffsets;
            this.offsetsAndRotation = offsetsAndRotation;
        }
    }

    // ---------- Geometry calc (copied from rotated tray wizard) ----------
    public Location calculateOffsetsAndRotation() throws Exception {
        if (nCols < 1 || nRows < 1) {
            throw new Exception(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.AtLeastOneRowAndOneColumn"));
        }

        Length abLength = firstRowFirstColumn.getLocation().getLinearLengthTo(firstRowLastColumn.getLocation());
        if ((abLength.getValue() > 0) && (nCols == 1)) {
            throw new Exception(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.SingleColumnInconsistency"));
        }
        if ((abLength.getValue() == 0) && (nCols > 1)) {
            throw new Exception(String.format(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.MultipleColumnInconsistency"), nCols));
        }

        Length bcLength = firstRowLastColumn.getLocation().getLinearLengthTo(lastRowLastColumn.getLocation());
        if ((bcLength.getValue() > 0) && (nRows == 1)) {
            throw new Exception(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.SingleRowInconsistency"));
        }
        if ((bcLength.getValue() == 0) && (nRows > 1)) {
            throw new Exception(String.format(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.MultipleRowInconsistency"), nRows));
        }

        Length colStep = nCols > 1 ? abLength.divide(nCols - 1) : new Length(0, LengthUnit.Millimeters);
        Length rowStep = nRows > 1 ? bcLength.divide(nRows - 1) : new Length(0, LengthUnit.Millimeters);

        double rowAngleDeg = Utils2D.getAngleFromPoint(firstRowFirstColumn.getLocation(), firstRowLastColumn.getLocation());
        double colAngleDeg = Utils2D.getAngleFromPoint(firstRowLastColumn.getLocation(), lastRowLastColumn.getLocation());

        if ((nRows > 1) && (nCols > 1)) {
            double checkAngleDeg = Utils2D.normalizeAngle180(rowAngleDeg - colAngleDeg);
            double checkDeg = Math.abs(checkAngleDeg);
            if ((checkDeg < 90 - RIGHT_ANGLE_TOLERANCE) || (checkDeg > 90 + RIGHT_ANGLE_TOLERANCE)) {
                throw new Exception(String.format(
                        Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.TrayAngleNot90"),
                        checkDeg));
            }
            if (checkAngleDeg < 0) {
                rowStep = rowStep.multiply(-1);
            }
        }

        double rotDeg = offsetsAndRotation.getRotation();
        if (nCols > 1) {
            rotDeg = rowAngleDeg;
        } else if (nRows > 1) {
            rotDeg = colAngleDeg + 90;
        }

        LengthUnit units = Configuration.get().getSystemUnits();
        return new Location(units, colStep.convertToUnits(units).getValue(),
                rowStep.convertToUnits(units).getValue(), 0, rotDeg);
    }

    @Override
    public void validateInput() throws Exception {
        if (wizardFeedCount < 0){ setwizardFeedCount(0);}
        if (wizardFeedCount > nCols * nRows){ setwizardFeedCount(nCols * nRows); }

        Location offRot = calculateOffsetsAndRotation().convertToUnits(VALIDATION_UNITS);

        double offsetX = this.offsetsAndRotation.getLengthX().convertToUnits(VALIDATION_UNITS).getValue();
        double offsetY = this.offsetsAndRotation.getLengthY().convertToUnits(VALIDATION_UNITS).getValue();
        double rot = this.offsetsAndRotation.getRotation();

        if ((Math.abs(offRot.getX() - offsetX) > VALIDATION_TOLERANCE)
                || (Math.abs(offRot.getY() - offsetY) > VALIDATION_TOLERANCE)
                || (Math.abs(offRot.getRotation() - rot) > VALIDATION_TOLERANCE)) {
            throw new Exception(Translations.getString(
                    "ReferenceRotatedTrayFeederConfigurationWizard.ErrorMessage.OffsetsAndRotationInconsistency"));
        }
    }

    // ---------- Vision actions (from AdvancedLoosePart wizard) ----------
    private void editPipeline() throws Exception {
        if (feeder.getPart() == null) {
            throw new Exception("Feeder " + feeder.getName() + " has no part.");
        }
        FiducialVisionSettings fiducialVisionSettings = getSelectedFiducialVisionSettings();
        CvPipeline pipeline = (fiducialVisionSettings != null) ? fiducialVisionSettings.getPipeline() : feeder.getPipeline();
        if (pipeline == null) {
            throw new Exception("No pipeline is configured for this feeder.");
        }
        pipeline.setProperty("camera", Configuration.get().getMachine().getDefaultHead().getDefaultCamera());
        pipeline.setProperty("feeder", feeder);
        CvPipelineEditor editor = new CvPipelineEditor(pipeline);
        String pipelineName = (fiducialVisionSettings != null)
                ? ((fiducialVisionSettings.getName() == null || fiducialVisionSettings.getName().isEmpty())
                        ? fiducialVisionSettings.getId()
                        : fiducialVisionSettings.getName())
                : feeder.getPart().getId();
        String pipelineType = (fiducialVisionSettings != null) ? " Top Vision Pipeline" : " Pipeline";
        JDialog dialog = new CvPipelineEditorDialog(MainFrame.get(), pipelineName + pipelineType, editor);
        dialog.setVisible(true);
    }

    private void resetPipeline() {
        FiducialVisionSettings fiducialVisionSettings = getSelectedFiducialVisionSettings();
        if (fiducialVisionSettings != null) {
            fiducialVisionSettings.resetToDefault();
            return;
        }
        feeder.resetPipeline();
    }

    private FiducialVisionSettings getSelectedFiducialVisionSettings() {
        Object selected = (fiducialVisionSettingsCombo != null) ? fiducialVisionSettingsCombo.getSelectedItem() : null;
        if (selected instanceof FiducialVisionSettings) {
            return (FiducialVisionSettings) selected;
        }
        return feeder.getFiducialVisionSettings();
    }


    private class TrayPreviewPanel extends JPanel {
        private int rows = 1;
        private int cols = 1;
        private StartCorner startCorner = StartCorner.BOTTOM_LEFT;
        private FirstRasterDirection firstRasterDirection = FirstRasterDirection.ROW;
        private RasterPattern rasterPattern = RasterPattern.ZIG_ZAG;
        private boolean applied;

        TrayPreviewPanel() {
            setPreferredSize(new Dimension(420, 260));
            setOpaque(false);
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    StartCorner corner = cornerAt(e.getPoint());
                    if (corner != null) {
                        feeder.setStartCorner(corner);
                        updatePreviewFromFeeder();
                    }
                }
            });
        }

        void setRows(int rows) {
            this.rows = Math.max(rows, 1);
            repaint();
        }

        void setCols(int cols) {
            this.cols = Math.max(cols, 1);
            repaint();
        }

        void setStartCorner(StartCorner startCorner) {
            this.startCorner = startCorner;
            repaint();
        }

        void setFirstRasterDirection(FirstRasterDirection firstRasterDirection) {
            this.firstRasterDirection = firstRasterDirection;
            repaint();
        }

        void setRasterPattern(RasterPattern rasterPattern) {
            this.rasterPattern = rasterPattern;
            repaint();
        }

        void setApplied(boolean applied) {
            this.applied = applied;
            repaint();
        }

        private StartCorner cornerAt(Point point) {
            int[] grid = getGridBounds();
            int x = grid[0], y = grid[1], cell = grid[2];
            if (cell <= 0 || point.x < x || point.y < y || point.x >= x + cols * cell || point.y >= y + rows * cell) {
                return null;
            }
            int col = (point.x - x) / cell;
            int row = (point.y - y) / cell;
            if (row == 0 && col == 0) {
                return StartCorner.TOP_LEFT;
            }
            if (row == 0 && col == cols - 1) {
                return StartCorner.TOP_RIGHT;
            }
            if (row == rows - 1 && col == 0) {
                return StartCorner.BOTTOM_LEFT;
            }
            if (row == rows - 1 && col == cols - 1) {
                return StartCorner.BOTTOM_RIGHT;
            }
            return null;
        }

        private int[] getGridBounds() {
            int margin = 24;
            int cell = Math.max(2, Math.min((getWidth() - margin * 2) / cols, (getHeight() - margin * 2) / rows));
            int width = cell * cols;
            int height = cell * rows;
            return new int[] { (getWidth() - width) / 2, (getHeight() - height) / 2, cell };
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int[] grid = getGridBounds();
            int x0 = grid[0], y0 = grid[1], cell = grid[2];
            int selectedRow = startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.TOP_RIGHT ? 0 : rows - 1;
            int selectedCol = startCorner == StartCorner.TOP_LEFT || startCorner == StartCorner.BOTTOM_LEFT ? 0 : cols - 1;
            for (int row = 0; row < rows; row++) {
                for (int col = 0; col < cols; col++) {
                    if ((firstRasterDirection == FirstRasterDirection.ROW && row == selectedRow)
                            || (firstRasterDirection == FirstRasterDirection.COLUMN && col == selectedCol)) {
                        g2.setColor(new Color(255, 245, 150));
                        g2.fillRect(x0 + col * cell, y0 + row * cell, cell, cell);
                    }
                    if (row == selectedRow && col == selectedCol) {
                        g2.setColor(new Color(80, 200, 120));
                        g2.fillRect(x0 + col * cell, y0 + row * cell, cell, cell);
                    }
                    g2.setColor(Color.GRAY);
                    g2.drawRect(x0 + col * cell, y0 + row * cell, cell, cell);
                }
            }
            drawSecondDirectionArrow(g2, x0, y0, cell);
            drawRasterArrows(g2, x0, y0, cell);
            if (!applied) {
                g2.setColor(Color.DARK_GRAY);
                g2.drawString("Apply A/B/C grid definition to store grid vectors", 8, 16);
            }
            g2.dispose();
        }

        private void drawSecondDirectionArrow(Graphics2D g2, int x0, int y0, int cell) {
            int cx = x0 + cols * cell / 2;
            int cy = y0 + rows * cell / 2;
            int len = Math.max(18, Math.min(cols * cell, rows * cell) / 4);
            String direction = JEDEC_TrayFeeder.getSecondRasterDirectionDescription(startCorner, firstRasterDirection);
            int dx = 0, dy = 0;
            if ("UP".equals(direction)) {
                dy = -len;
            }
            else if ("DOWN".equals(direction)) {
                dy = len;
            }
            else if ("LEFT".equals(direction)) {
                dx = -len;
            }
            else {
                dx = len;
            }
            g2.setColor(new Color(40, 90, 220, 180));
            g2.setStroke(new BasicStroke(5f));
            drawArrow(g2, cx - dx / 2, cy - dy / 2, cx + dx / 2, cy + dy / 2, 10);
        }

        private void drawRasterArrows(Graphics2D g2, int x0, int y0, int cell) {
            g2.setColor(new Color(20, 20, 20, 150));
            g2.setStroke(new BasicStroke(1.2f));
            int capacity = rows * cols;
            int step = Math.max(1, capacity / 60);
            JEDEC_TrayFeeder.GridIndex previous = null;
            for (int i = 0; i < capacity; i += step) {
                JEDEC_TrayFeeder.GridIndex current = JEDEC_TrayFeeder.getGridIndexForFeed(i, rows, cols,
                        startCorner, firstRasterDirection, rasterPattern);
                if (previous != null) {
                    drawArrow(g2, x0 + previous.col * cell + cell / 2, y0 + previous.row * cell + cell / 2,
                            x0 + current.col * cell + cell / 2, y0 + current.row * cell + cell / 2);
                }
                previous = current;
            }
        }

        private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2) {
            drawArrow(g2, x1, y1, x2, y2, 6);
        }

        private void drawArrow(Graphics2D g2, int x1, int y1, int x2, int y2, int size) {
            g2.drawLine(x1, y1, x2, y2);
            double angle = Math.atan2(y2 - y1, x2 - x1);
            Polygon head = new Polygon();
            head.addPoint(x2, y2);
            head.addPoint((int) (x2 - size * Math.cos(angle - Math.PI / 6)), (int) (y2 - size * Math.sin(angle - Math.PI / 6)));
            head.addPoint((int) (x2 - size * Math.cos(angle + Math.PI / 6)), (int) (y2 - size * Math.sin(angle + Math.PI / 6)));
            g2.fillPolygon(head);
        }
    }

}
