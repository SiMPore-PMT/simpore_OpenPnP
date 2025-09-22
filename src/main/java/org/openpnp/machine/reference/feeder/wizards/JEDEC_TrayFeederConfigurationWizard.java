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

import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import org.openpnp.gui.support.PartsComboBoxModel;
import org.openpnp.model.Configuration;
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
    private JPanel panelIllustration;
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
    private JTextField textFieldComponentRotation;
    private JTextField textFieldComponentZHeight;

    private JPanel panelPart;
    private JComboBox<?> comboBoxPart;
    private LocationButtonsPanel locationButtonsPanel;
    private LocationButtonsPanel lastLocationButtonsPanel;
    private JTextField retryCountTf;
    private JTextField pickRetryCount;

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

        textFieldComponentRotation = new JTextField();
        textFieldComponentRotation.setColumns(10);
        textFieldComponentRotation.setToolTipText(
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.ComponentRotation.ToolTip"));
        panelParameters.add(textFieldComponentRotation, "4, 6");

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
                    offsetsAndRotation.setLocation(calculateOffsetsAndRotation());
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

        // ---------------- Illustration (optional) ----------------
        JPanel panelIllustration = new JPanel();
        panelIllustration.setBorder(new TitledBorder(null,
                Translations.getString("ReferenceRotatedTrayFeederConfigurationWizard.TrayIllustration"),
                TitledBorder.LEADING, TitledBorder.TOP, null));
        contentPanel.add(panelIllustration);
        try (InputStream stream = getClass().getResourceAsStream("/illustrations/rotatedtrayfeeder.png")) {
            if (stream != null) {
                ImageIcon icon = new ImageIcon(ImageIO.read(stream));
                JLabel illustrationLabel = new JLabel();
                illustrationLabel.setIcon(icon);
                panelIllustration.add(illustrationLabel);
            }
        } catch (IOException ioe) {
            // ignore
        }

        // ---------------- Vision panel (from AdvancedLoosePart wizard) ----------------
        // Warning banner
        JPanel warningPanel = new JPanel();
        FlowLayout flowLayout = (FlowLayout) warningPanel.getLayout();
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
                        FormSpecs.DEFAULT_ROWSPEC, }));

        JLabel lblFeedPipeline = new JLabel("Feed Pipeline");
        visionPanel.add(lblFeedPipeline, "2, 2");

        JButton btnEditPipeline = new JButton("Edit");
        btnEditPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            editPipeline();
        }));
        visionPanel.add(btnEditPipeline, "4, 2");

        JButton btnResetPipeline = new JButton("Reset");
        btnResetPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            resetPipeline();
        }));
        visionPanel.add(btnResetPipeline, "6, 2");

        JLabel lblTrainingPipeline = new JLabel("Training Pipeline");
        visionPanel.add(lblTrainingPipeline, "2, 4");

        JButton btnEditTrainingPipeline = new JButton("Edit");
        btnEditTrainingPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            editTrainingPipeline();
        }));
        visionPanel.add(btnEditTrainingPipeline, "4, 4");

        JButton btnResetTrainingPipeline = new JButton("Reset");
        btnResetTrainingPipeline.addActionListener(e -> UiUtils.messageBoxOnException(() -> {
            resetTrainingPipeline();
        }));
        visionPanel.add(btnResetTrainingPipeline, "6, 4");
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

        // pick location, rotations, Z
        MutableLocationProxy location = new MutableLocationProxy();
        bind(UpdateStrategy.READ_WRITE, feeder, "location", location, "location");
        addWrappedBinding(location, "lengthX", textFieldLocationX, "text", lengthConverter);
        addWrappedBinding(location, "lengthY", textFieldLocationY, "text", lengthConverter);
        addWrappedBinding(location, "rotation", textFieldTrayRotation, "text", doubleConverter);
        addWrappedBinding(location, "lengthZ", textFieldComponentZHeight, "text", lengthConverter);

        addWrappedBinding(feeder, "componentRotationInTray", textFieldComponentRotation, "text", doubleConverter);

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
        ComponentDecorators.decorateWithAutoSelectAndLengthConversion(textFieldComponentRotation);
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
        ComponentDecorators.decorateWithAutoSelect(textFieldTrayCountRows);
        ComponentDecorators.decorateWithAutoSelect(textFieldTrayCountCols);
        ComponentDecorators.decorateWithAutoSelect(textFieldFeedCount);
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
        if (wizardFeedCount < 0) setwizardFeedCount(0);
        if (wizardFeedCount > nCols * nRows) setwizardFeedCount(nCols * nRows);

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
        CvPipeline pipeline = feeder.getPipeline();
        pipeline.setProperty("camera", Configuration.get().getMachine().getDefaultHead().getDefaultCamera());
        pipeline.setProperty("feeder", feeder);
        CvPipelineEditor editor = new CvPipelineEditor(pipeline);
        JDialog dialog = new CvPipelineEditorDialog(MainFrame.get(), feeder.getPart().getId() + " Pipeline", editor);
        dialog.setVisible(true);
    }

    private void resetPipeline() {
        feeder.resetPipeline();
    }

    private void editTrainingPipeline() throws Exception {
        if (feeder.getPart() == null) {
            throw new Exception("Feeder " + feeder.getName() + " has no part.");
        }
        CvPipeline pipeline = feeder.getTrainingPipeline();
        pipeline.setProperty("camera", Configuration.get().getMachine().getDefaultHead().getDefaultCamera());
        pipeline.setProperty("feeder", feeder);
        CvPipelineEditor editor = new CvPipelineEditor(pipeline);
        JDialog dialog = new CvPipelineEditorDialog(MainFrame.get(), feeder.getPart().getId() + " Training Pipeline", editor);
        dialog.setVisible(true);
    }

    private void resetTrainingPipeline() {
        feeder.resetTrainingPipeline();
    }
}
