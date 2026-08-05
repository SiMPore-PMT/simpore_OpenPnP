package org.openpnp.gui.operator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JToggleButton;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;

import org.openpnp.ConfigurationListener;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.events.PlacementChangedEvent;
import org.openpnp.events.PlacementsHolderLocationChangedEvent;
import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.operator.OperatorRuntimeCanvas.EditMode;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.machine.reference.feeder.JEDEC_TrayFeeder;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
import org.openpnp.model.Part;
import org.openpnp.model.Placement;
import org.openpnp.model.PlacementsHolderLocation;
import org.openpnp.spi.Feeder;
import org.openpnp.spi.Machine;
import org.openpnp.spi.MachineListener;

import com.google.common.eventbus.Subscribe;

@SuppressWarnings("serial")
public class OperatorPanel extends JPanel {
    private static final Color STATUS_READY = new Color(39, 128, 68);
    private static final Color STATUS_WAITING = new Color(160, 104, 0);
    private static final Color STATUS_STOPPED = new Color(90, 90, 90);
    private static final Color STATUS_RUNNING = new Color(35, 92, 170);

    private final MainFrame mainFrame;
    private final JobPanel jobPanel;
    private final OperatorJobEditingService editingService = new OperatorJobEditingService();
    private final OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
    private final JButton startNewJobButton = createPrimaryButton("Start New Job", Icons.openSCadIcon);
    private final JButton startButton = createActionButton("Start", Icons.start,
            "Start the loaded job using the OpenPnP job processor");
    private final JButton pauseResumeButton = createActionButton("Pause / Resume", Icons.pause,
            "Pause or resume the current job");
    private final JButton stopButton = createActionButton("Stop", Icons.stop, "Stop the current job");
    private final JButton resetBoardButton = createActionButton("Reset Board", Icons.refresh,
            "Reset placements and tray feeder counts for the loaded job");
    private final JButton modifyLastRunButton = createActionButton("Modify Last Run", Icons.board,
            "Open the existing Job editor for the loaded job");
    private final JButton openNewJobButton = createActionButton("Open New Job", Icons.add,
            "Select and reset a different operator job");
    private final JButton resetBoardsOnlyButton = createActionButton("Reset Boards", Icons.refresh,
            "Clear board and placement progress without resetting tray counts");
    private final JToggleButton globalDispenseToggle = new JToggleButton("Dispense", Icons.centerPin);
    private final JToggleButton editToggle = new JToggleButton("Edit", Icons.captureTool);
    private final JToggleButton boardModeToggle = new JToggleButton("Boards", Icons.board);
    private final JToggleButton placementModeToggle = new JToggleButton("Placements", Icons.place);
    private final JCheckBox placementFilter = new JCheckBox("Pick-and-place", true);
    private final JCheckBox dispenseFilter = new JCheckBox("Dispense", true);
    private final JCheckBox fiducialFilter = new JCheckBox("Fiducials", true);
    private final JLabel titleLabel = new JLabel("Operator Runtime");
    private final JLabel jobLabel = new JLabel("No job loaded");
    private final JLabel statusLabel = new JLabel("Machine status unavailable");
    private final JLabel guidanceLabel = new JLabel("Power and home the machine to start an operator job.", SwingConstants.CENTER);
    private final JLabel selectionLabel = new JLabel("Select a board to inspect placements.");
    private final JPanel jobControlsPanel = new JPanel(new BorderLayout(6, 0));
    private final JPanel postRunPanel = new JPanel(new GridBagLayout());
    private final JPanel editOptionsPanel = new JPanel(new GridBagLayout());
    private final DefaultTableModel placementDetailsModel = new DefaultTableModel(
            new Object[] { "Id", "Type", "Part", "Side", "Enabled", "Placed", "Status" }, 0) {
        @Override
        public Class<?> getColumnClass(int columnIndex) {
            return columnIndex == 4 || columnIndex == 5 ? Boolean.class : String.class;
        }
        @Override
        public boolean isCellEditable(int row, int column) {
            return isEditingAllowed() && (column == 4 || column == 5);
        }
    };
    private final JTable placementDetailsTable = new JTable(placementDetailsModel);
    private final List<RowPlacement> rowPlacements = new ArrayList<>();
    private final Set<PlacementsHolderLocation<?>> selectedBoards = new LinkedHashSet<>();
    private final List<AbstractModelObject> feederListeners = new ArrayList<>();
    private boolean updatingDetails;
    private final PropertyChangeListener feederPropertyListener = e -> repaintRuntime();

    public OperatorPanel(MainFrame mainFrame, JobPanel jobPanel) {
        super(new BorderLayout(8, 8));
        this.mainFrame = mainFrame;
        this.jobPanel = jobPanel;
        canvas.setListener(createCanvasListener());

        setBorder(new CompoundBorder(new TitledBorder("Runtime"), new EmptyBorder(8, 8, 8, 8)));
        setBackground(UIManager.getColor("Panel.background"));
        add(createTopCommandPanel(), BorderLayout.NORTH);
        add(createRuntimePanel(), BorderLayout.CENTER);
        add(createBottomPanel(), BorderLayout.SOUTH);

        startNewJobButton.addActionListener(e -> openJob(true));
        openNewJobButton.addActionListener(e -> openJob(true));
        resetBoardButton.addActionListener(e -> resetCurrentJob());
        modifyLastRunButton.addActionListener(e -> showJobEditor());
        resetBoardsOnlyButton.addActionListener(e -> resetBoardsOnly());
        globalDispenseToggle.addActionListener(e -> setGlobalDispenseEnabled(globalDispenseToggle.isSelected()));
        editToggle.addActionListener(e -> updateEditMode());
        boardModeToggle.addActionListener(e -> updateEditMode());
        placementModeToggle.addActionListener(e -> updateEditMode());
        placementFilter.addActionListener(e -> updateButtons());
        dispenseFilter.addActionListener(e -> updateButtons());
        fiducialFilter.addActionListener(e -> updateButtons());
        placementDetailsTable.getModel().addTableModelListener(e -> applyPlacementTableEdit(e.getFirstRow(), e.getColumn()));
        startButton.addActionListener(e -> jobPanel.startPauseResumeJobAction.actionPerformed(
                new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-start")));
        pauseResumeButton.addActionListener(e -> jobPanel.startPauseResumeJobAction.actionPerformed(
                new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-pause-resume")));
        stopButton.addActionListener(e -> jobPanel.stopJobAction.actionPerformed(
                new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-stop")));
        jobPanel.addPropertyChangeListener("state",
                e -> handleJobStateChange((String) e.getOldValue(), (String) e.getNewValue()));
        Configuration.get().addListener(new ConfigurationListener.Adapter() {
            @Override
            public void configurationComplete(Configuration configuration) throws Exception {
                configuration.getMachine().addListener(machineListener);
                SwingUtilities.invokeLater(() -> refreshJobAndFeeders());
            }
        });
        Configuration.get().getBus().register(this);
        updateButtons();
        refreshJobAndFeeders();
    }

    private JPanel createHeaderPanel() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        header.setBorder(new CompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0,
                UIManager.getColor("Separator.foreground")), new EmptyBorder(0, 0, 8, 0)));
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, titleLabel.getFont().getSize2D() + 2));
        header.add(titleLabel, BorderLayout.NORTH);
        JPanel statusPanel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 2, 8);
        statusPanel.add(new JLabel("Job:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        statusPanel.add(jobLabel, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.weightx = 0.0;
        gbc.fill = GridBagConstraints.NONE;
        statusPanel.add(new JLabel("Status:"), gbc);
        gbc.gridx = 1;
        gbc.weightx = 1.0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        statusPanel.add(statusLabel, gbc);
        header.add(statusPanel, BorderLayout.CENTER);
        return header;
    }

    private JPanel createRuntimePanel() {
        JPanel runtime = new JPanel(new BorderLayout(8, 8));
        runtime.setBorder(new EmptyBorder(6, 0, 6, 0));
        guidanceLabel.setBorder(new EmptyBorder(4, 4, 4, 4));
        runtime.add(guidanceLabel, BorderLayout.NORTH);
        runtime.add(canvas, BorderLayout.CENTER);
        return runtime;
    }

    private JPanel createTopCommandPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        JToolBar jobToolBar = new JToolBar();
        jobToolBar.setFloatable(false);
        jobToolBar.add(startNewJobButton);
        jobToolBar.addSeparator();
        jobToolBar.add(startButton);
        jobToolBar.add(pauseResumeButton);
        jobToolBar.add(stopButton);
        jobControlsPanel.add(jobToolBar, BorderLayout.CENTER);
        JPanel left = new JPanel(new BorderLayout(4, 4));
        left.add(jobControlsPanel, BorderLayout.NORTH);
        left.add(createEditingToolbar(), BorderLayout.CENTER);
        panel.add(left, BorderLayout.CENTER);
        panel.add(createPostRunPanel(), BorderLayout.EAST);
        return panel;
    }

    private JPanel createBottomPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.add(createDetailsPanel(), BorderLayout.CENTER);
        panel.add(createHeaderPanel(), BorderLayout.SOUTH);
        return panel;
    }

    private JToolBar createEditingToolbar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        globalDispenseToggle.setSelected(true);
        updateDispenseToggleIcon();
        globalDispenseToggle.setToolTipText("Disable or restore operator-session dispense placements");
        editToggle.setToolTipText("Enable board and placement selection tools");
        ButtonGroup group = new ButtonGroup();
        group.add(boardModeToggle);
        group.add(placementModeToggle);
        boardModeToggle.setSelected(true);
        toolBar.add(resetBoardsOnlyButton);
        toolBar.add(globalDispenseToggle);
        toolBar.addSeparator();
        toolBar.add(editToggle);
        toolBar.add(boardModeToggle);
        toolBar.add(placementModeToggle);
        toolBar.addSeparator();
        toolBar.add(placementFilter);
        toolBar.add(dispenseFilter);
        toolBar.add(fiducialFilter);
        return toolBar;
    }

    private JPanel createDetailsPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(new CompoundBorder(new TitledBorder("Board Inspector"), new EmptyBorder(4, 4, 4, 4)));
        placementDetailsTable.setFillsViewportHeight(true);
        placementDetailsTable.setDefaultRenderer(Object.class, new PlacementDetailsRenderer());
        placementDetailsTable.setDefaultRenderer(Boolean.class, new PlacementDetailsRenderer());
        panel.add(selectionLabel, BorderLayout.NORTH);
        panel.add(new JScrollPane(placementDetailsTable), BorderLayout.CENTER);
        panel.setPreferredSize(new java.awt.Dimension(320, 120));
        return panel;
    }

    private JPanel createPostRunPanel() {
        postRunPanel.setBorder(new CompoundBorder(new TitledBorder("After Run"), new EmptyBorder(4, 4, 4, 4)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.insets = new Insets(0, 0, 0, 6);
        postRunPanel.add(resetBoardButton, gbc);
        gbc.gridx++;
        postRunPanel.add(modifyLastRunButton, gbc);
        gbc.gridx++;
        gbc.insets = new Insets(0, 0, 0, 0);
        postRunPanel.add(openNewJobButton, gbc);
        return postRunPanel;
    }

    private static JButton createPrimaryButton(String text, Icon icon) {
        JButton button = createActionButton(text, icon, null);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        return button;
    }

    private static JButton createActionButton(String text, Icon icon, String tooltip) {
        JButton button = new JButton(text, icon);
        button.setFocusable(false);
        if (tooltip != null) {
            button.setToolTipText(tooltip);
        }
        return button;
    }

    private final MachineListener machineListener = new MachineListener.Adapter() {
        @Override public void machineEnabled(Machine machine) { SwingUtilities.invokeLater(() -> updateButtons()); }
        @Override public void machineDisabled(Machine machine, String reason) { SwingUtilities.invokeLater(() -> updateButtons()); }
        @Override public void machineHomed(Machine machine, boolean isHomed) { SwingUtilities.invokeLater(() -> updateButtons()); }
    };

    private boolean machineReady() {
        Machine machine = Configuration.get().getMachine();
        return machine != null && machine.isEnabled() && machine.isHomed();
    }

    private boolean isEditingAllowed() {
        return jobPanel.getJob() != null && jobPanel.getState() == JobPanel.State.Stopped;
    }

    private String machineNotReadyReason() {
        Machine machine = Configuration.get().getMachine();
        if (machine == null) {
            return "Machine configuration is still loading.";
        }
        if (!machine.isEnabled() && !machine.isHomed()) {
            return "Machine is disabled and not homed.";
        }
        if (!machine.isEnabled()) {
            return "Machine is disabled.";
        }
        if (!machine.isHomed()) {
            return "Machine is powered but not homed.";
        }
        return null;
    }

    private void updateButtons() {
        String state = jobPanel.getJobState();
        boolean ready = machineReady();
        boolean hasJob = jobPanel.getJob() != null && jobPanel.getJob().getFile() != null;
        boolean stopped = "Stopped".equals(state);
        boolean running = "Running".equals(state) || "Pausing".equals(state);
        boolean paused = "Paused".equals(state);
        boolean runningOrPaused = running || paused;
        String notReadyReason = machineNotReadyReason();
        boolean editingAllowed = isEditingAllowed();

        jobLabel.setText(hasJob ? jobPanel.getJob().getFile().getName() : "No job loaded");
        updateStatusLabel(state, ready, hasJob, notReadyReason);
        guidanceLabel.setText(createGuidanceText(hasJob, ready, state, notReadyReason));

        startNewJobButton.setEnabled(ready);
        startNewJobButton.setToolTipText(ready ? "Select a .job.xml file to load and reset for an operator run" : notReadyReason);
        startButton.setEnabled(hasJob && ready && stopped);
        pauseResumeButton.setEnabled(hasJob && ready && runningOrPaused);
        pauseResumeButton.setText(paused ? "Resume" : "Pause");
        pauseResumeButton.setIcon(paused ? Icons.start : Icons.pause);
        stopButton.setEnabled(hasJob && runningOrPaused);
        resetBoardButton.setEnabled(hasJob && stopped);
        modifyLastRunButton.setEnabled(hasJob && stopped);
        openNewJobButton.setEnabled(ready && stopped);
        postRunPanel.setVisible(stopped && hasJob);
        resetBoardsOnlyButton.setEnabled(editingAllowed);
        globalDispenseToggle.setEnabled(editingAllowed);
        editToggle.setEnabled(editingAllowed);
        boardModeToggle.setEnabled(editingAllowed && editToggle.isSelected());
        placementModeToggle.setEnabled(editingAllowed && editToggle.isSelected());
        boolean placementMode = editToggle.isSelected() && placementModeToggle.isSelected();
        placementFilter.setEnabled(editingAllowed && placementMode);
        dispenseFilter.setEnabled(editingAllowed && placementMode);
        fiducialFilter.setEnabled(editingAllowed && placementMode);
        String editTooltip = editingAllowed ? "Operator edits are enabled." : "Operator edits require the job to be stopped.";
        resetBoardsOnlyButton.setToolTipText(editTooltip);
        editToggle.setToolTipText(editTooltip);
        updateDispenseToggleIcon();
        canvas.setEditingAllowed(editingAllowed);
        canvas.setSelectedTool(mainFrame.getMachineControls().getSelectedTool());
        updateEditMode();
    }

    private void updateEditMode() {
        editOptionsPanel.setVisible(editToggle.isSelected());
        if (!editToggle.isSelected() || !isEditingAllowed()) {
            canvas.setEditMode(EditMode.NONE);
        }
        else {
            canvas.setEditMode(boardModeToggle.isSelected() ? EditMode.BOARD : EditMode.PLACEMENT);
        }
        repaintRuntime();
    }

    private void updateStatusLabel(String state, boolean ready, boolean hasJob, String notReadyReason) {
        if ("Running".equals(state) || "Pausing".equals(state)) {
            statusLabel.setText("Running");
            statusLabel.setForeground(STATUS_RUNNING);
        }
        else if ("Paused".equals(state)) {
            statusLabel.setText("Paused - editing disabled until stopped");
            statusLabel.setForeground(STATUS_WAITING);
        }
        else if (!ready) {
            statusLabel.setText(notReadyReason);
            statusLabel.setForeground(STATUS_WAITING);
        }
        else if (hasJob) {
            statusLabel.setText("Ready to start and edit");
            statusLabel.setForeground(STATUS_READY);
        }
        else {
            statusLabel.setText("Ready for job selection");
            statusLabel.setForeground(STATUS_STOPPED);
        }
    }

    private String createGuidanceText(boolean hasJob, boolean ready, String state, String notReadyReason) {
        if (!ready) {
            return notReadyReason + " Power and home the machine to start an operator job.";
        }
        if (!hasJob) {
            return "Machine ready. Select Start New Job to load and reset an operator job.";
        }
        if ("Running".equals(state) || "Pausing".equals(state)) {
            return "Job is running. Operator edits are locked until the job stops.";
        }
        if ("Paused".equals(state)) {
            return "Job is paused. Edits are disabled for this MVP; stop the job before modifying state.";
        }
        return "Job loaded. Use Edit for boards/placements or right-click JEDEC pockets to adjust tray progress.";
    }

    private void openJob(boolean reset) {
        FileDialog fileDialog = new FileDialog((Frame) SwingUtilities.getWindowAncestor(this), "Open Job");
        fileDialog.setFilenameFilter((FilenameFilter) (dir, name) -> name.toLowerCase().endsWith(".job.xml"));
        fileDialog.setVisible(true);
        if (fileDialog.getFile() == null) {
            return;
        }
        try {
            File file = new File(new File(fileDialog.getDirectory()), fileDialog.getFile());
            jobPanel.loadJob(file);
            if (reset) {
                resetCurrentJob();
            }
            restoreTrayProgress();
            refreshJobAndFeeders();
        }
        catch (Exception e) {
            e.printStackTrace();
            MessageBoxes.errorBox(mainFrame, "Operator Job Load Error", e.getMessage());
        }
    }

    private void resetCurrentJob() {
        try {
            OperatorJobReset.resetForFreshOperatorRun(jobPanel.getJob());
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            e.printStackTrace();
            MessageBoxes.errorBox(mainFrame, "Operator Job Reset Error", e.getMessage());
        }
    }

    private void resetBoardsOnly() {
        if (!confirmEdit("Reset all boards and placements without resetting tray counts?")) {
            return;
        }
        try {
            editingService.resetBoardsOnly(jobPanel.getJob());
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void setGlobalDispenseEnabled(boolean enabled) {
        try {
            int changed = editingService.setDispenseGloballyEnabled(jobPanel.getJob(), enabled);
            updateDispenseToggleIcon();
            refreshAndPersistView(false);
            guidanceLabel.setText((enabled ? "Restored " : "Disabled ") + changed + " dispense placement(s).");
        }
        catch (Exception e) {
            globalDispenseToggle.setSelected(!enabled);
            updateDispenseToggleIcon();
            showEditError(e);
        }
    }

    private void showJobEditor() {
        JDialog dialog = new JDialog(mainFrame, "Modify Last Run", true);
        dialog.getContentPane().add(jobPanel);
        dialog.setSize(900, 650);
        dialog.setLocationRelativeTo(mainFrame);
        dialog.setVisible(true);
    }

    private void handleJobStateChange(String oldState, String newState) {
        SwingUtilities.invokeLater(() -> {
            updateButtons();
            repaintRuntime();
            Job job = jobPanel.getJob();
            // MVP completion heuristic: the current processor does not expose a normal-completion flag here.
            // Completion preserves final board, placement and tray state; reset is always operator initiated.
            if (("Running".equals(oldState) || "Pausing".equals(oldState) || "Paused".equals(oldState))
                    && "Stopped".equals(newState) && job != null
                    && job.getActivePlacements(job.getRootPanelLocation()) == 0) {
                JOptionPane.showMessageDialog(mainFrame, "Job complete.", "Operator", JOptionPane.INFORMATION_MESSAGE);
                updateButtons();
            }
        });
    }

    private void refreshJobAndFeeders() {
        canvas.setJob(jobPanel.getJob());
        for (AbstractModelObject modelObject : feederListeners) {
            modelObject.removePropertyChangeListener(feederPropertyListener);
        }
        feederListeners.clear();
        Machine machine = Configuration.get().getMachine();
        if (machine == null) {
            updateButtons();
            repaintRuntime();
            return;
        }
        for (Feeder feeder : machine.getFeeders()) {
            if (feeder instanceof AbstractModelObject) {
                AbstractModelObject modelObject = (AbstractModelObject) feeder;
                modelObject.addPropertyChangeListener("feedCount", feederPropertyListener);
                modelObject.addPropertyChangeListener("remainingCount", feederPropertyListener);
                feederListeners.add(modelObject);
            }
        }
        updateDetailsPanel();
        updateButtons();
        repaintRuntime();
    }

    private void restoreTrayProgress() throws Exception {
        Machine machine = Configuration.get().getMachine();
        if (machine == null) {
            return;
        }
        List<JEDEC_TrayFeeder> trays = new ArrayList<>();
        for (Feeder feeder : machine.getFeeders()) {
            if (feeder instanceof JEDEC_TrayFeeder) {
                trays.add((JEDEC_TrayFeeder) feeder);
            }
        }
        editingService.restoreTrayProgress(jobPanel.getJob(), trays);
    }

    private OperatorRuntimeCanvas.Listener createCanvasListener() {
        return new OperatorRuntimeCanvas.Listener() {
            @Override
            public void boardClicked(PlacementsHolderLocation<?> boardLocation) {
                selectedBoards.clear();
                selectedBoards.add(boardLocation);
                updateDetailsPanel();
            }
            @Override
            public void boardSelectionChanged(Set<PlacementsHolderLocation<?>> selection) {
                selectedBoards.clear();
                selectedBoards.addAll(selection);
                updateDetailsPanel();
            }
            @Override
            public void showBoardContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection) {
                OperatorPanel.this.showBoardContextMenu(invoker, x, y, selection);
            }
            @Override
            public void showPlacementContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection) {
                OperatorPanel.this.showPlacementContextMenu(invoker, x, y, selection);
            }
            @Override
            public void showTrayPocketContextMenu(Component invoker, int x, int y, JEDEC_TrayFeeder feeder,
                    int feedIndexBase0, int displayPosition) {
                OperatorPanel.this.showTrayPocketContextMenu(invoker, x, y, feeder, displayPosition);
            }
            @Override
            public void resetTray(JEDEC_TrayFeeder feeder) {
                OperatorPanel.this.resetTray(feeder);
            }
            @Override
            public void enableTray(JEDEC_TrayFeeder feeder) {
                OperatorPanel.this.enableTray(feeder);
            }
        };
    }

    private void showBoardContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection) {
        if (!isEditingAllowed()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem enable = new JMenuItem("Enable Selected Boards", Icons.pinEnabled);
        JMenuItem disable = new JMenuItem("Disable Selected Boards", Icons.pinDisabled);
        enable.addActionListener(e -> setSelectedBoardsEnabled(selection, true));
        disable.addActionListener(e -> setSelectedBoardsEnabled(selection, false));
        menu.add(enable);
        menu.add(disable);
        menu.show(invoker, x, y);
    }

    private void showPlacementContextMenu(Component invoker, int x, int y, Set<PlacementsHolderLocation<?>> selection) {
        if (!isEditingAllowed() || selectedPlacementTypes().isEmpty()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        JMenuItem enable = new JMenuItem("Enable Selected Placements", Icons.pinEnabled);
        JMenuItem disable = new JMenuItem("Disable Selected Placements", Icons.pinDisabled);
        JMenuItem placed = new JMenuItem("Set Selected Placements as Placed", Icons.accept);
        JMenuItem unplaced = new JMenuItem("Set Selected Placements as Not Placed", Icons.dismiss);
        enable.addActionListener(e -> setSelectedPlacementsEnabled(selection, true));
        disable.addActionListener(e -> setSelectedPlacementsEnabled(selection, false));
        placed.addActionListener(e -> setSelectedPlacementsPlaced(selection, true));
        unplaced.addActionListener(e -> setSelectedPlacementsPlaced(selection, false));
        menu.add(enable);
        menu.add(disable);
        menu.addSeparator();
        menu.add(placed);
        menu.add(unplaced);
        menu.show(invoker, x, y);
    }

    private void showTrayPocketContextMenu(Component invoker, int x, int y, JEDEC_TrayFeeder feeder, int displayPosition) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem start = new JMenuItem("Set as Starting Position " + displayPosition, Icons.arrowUp);
        JMenuItem refresh = new JMenuItem("Refresh This JEDEC Tray", Icons.refresh);
        start.addActionListener(e -> setTrayStartingPosition(feeder, displayPosition));
        refresh.addActionListener(e -> resetTray(feeder));
        menu.add(start);
        menu.add(refresh);
        menu.show(invoker, x, y);
    }

    private void setSelectedBoardsEnabled(Set<PlacementsHolderLocation<?>> selection, boolean enabled) {
        if (!confirmBulk("set board enabled=" + enabled, selection.size(), 0)) {
            return;
        }
        try {
            for (PlacementsHolderLocation<?> board : selection) {
                editingService.setBoardEnabled(jobPanel.getJob(), board, enabled);
            }
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void setSelectedPlacementsEnabled(Set<PlacementsHolderLocation<?>> selection, boolean enabled) {
        List<RowPlacement> placements = filteredPlacements(selection);
        if (!confirmBulk("set placement enabled=" + enabled, selection.size(), placements.size())) {
            return;
        }
        try {
            for (RowPlacement row : placements) {
                editingService.setPlacementEnabled(jobPanel.getJob(), row.boardLocation, row.placement, enabled);
            }
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void setSelectedPlacementsPlaced(Set<PlacementsHolderLocation<?>> selection, boolean placed) {
        List<RowPlacement> placements = filteredPlacements(selection);
        if (!confirmBulk("set placement placed=" + placed, selection.size(), placements.size())) {
            return;
        }
        try {
            for (RowPlacement row : placements) {
                editingService.setPlacementPlaced(jobPanel.getJob(), row.boardLocation, row.placement, placed);
            }
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void setTrayStartingPosition(JEDEC_TrayFeeder feeder, int displayPosition) {
        try {
            editingService.setJedecTrayStartingPosition(jobPanel.getJob(), feeder, displayPosition);
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void enableTray(JEDEC_TrayFeeder feeder) {
        try {
            editingService.setFeederEnabled(feeder, true);
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void resetTray(JEDEC_TrayFeeder feeder) {
        if (!confirmEdit("Refresh " + feeder.getName() + " and set its next position to 1?")) {
            return;
        }
        try {
            editingService.resetJedecTray(jobPanel.getJob(), feeder);
            refreshAndPersistView(false);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void applyPlacementTableEdit(int row, int column) {
        if (updatingDetails || row < 0 || row >= rowPlacements.size() || column < 4 || !isEditingAllowed()) {
            return;
        }
        RowPlacement rowPlacement = rowPlacements.get(row);
        boolean value = Boolean.TRUE.equals(placementDetailsModel.getValueAt(row, column));
        try {
            if (column == 4) {
                editingService.setPlacementEnabled(jobPanel.getJob(), rowPlacement.boardLocation, rowPlacement.placement, value);
            }
            else if (column == 5) {
                editingService.setPlacementPlaced(jobPanel.getJob(), rowPlacement.boardLocation, rowPlacement.placement, value);
            }
            refreshAndPersistView(true);
        }
        catch (Exception e) {
            showEditError(e);
        }
    }

    private void updateDetailsPanel() {
        updatingDetails = true;
        placementDetailsModel.setRowCount(0);
        rowPlacements.clear();
        if (selectedBoards.isEmpty()) {
            selectionLabel.setText("Select a board to inspect placements.");
            updatingDetails = false;
            return;
        }
        PlacementsHolderLocation<?> board = selectedBoards.iterator().next();
        selectionLabel.setText("Board: " + board.getId() + (board.isEnabled() ? "" : " (disabled)"));
        for (Placement placement : board.getPlacementsHolder().getPlacements()) {
            rowPlacements.add(new RowPlacement(board, placement));
            Part part = placement.getPart();
            boolean placed = jobPanel.getJob().retrievePlacedStatus(board, placement.getId());
            String status = !placement.isEnabled() ? "Disabled" : placed ? "Placed" : "Pending";
            placementDetailsModel.addRow(new Object[] { placement.getId(), placement.getType().name(),
                    part == null ? "" : part.getId(), placement.getSide().name(), placement.isEnabled(),
                    placed, status });
        }
        updatingDetails = false;
    }

    private List<RowPlacement> filteredPlacements(Set<PlacementsHolderLocation<?>> boards) {
        EnumSet<Placement.Type> types = selectedPlacementTypes();
        List<RowPlacement> placements = new ArrayList<>();
        for (PlacementsHolderLocation<?> board : boards) {
            for (Placement placement : board.getPlacementsHolder().getPlacements()) {
                if (types.contains(placement.getType())) {
                    placements.add(new RowPlacement(board, placement));
                }
            }
        }
        return placements;
    }

    private EnumSet<Placement.Type> selectedPlacementTypes() {
        EnumSet<Placement.Type> types = EnumSet.noneOf(Placement.Type.class);
        if (placementFilter.isSelected()) {
            types.add(Placement.Type.Placement);
        }
        if (dispenseFilter.isSelected()) {
            types.add(Placement.Type.Dispense);
        }
        if (fiducialFilter.isSelected()) {
            types.add(Placement.Type.Fiducial);
        }
        return types;
    }

    private boolean confirmBulk(String action, int boards, int placements) {
        return JOptionPane.showConfirmDialog(mainFrame,
                "Apply '" + action + "' to " + boards + " board(s) and " + placements + " placement(s)?",
                "Confirm Operator Edit", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private boolean confirmEdit(String message) {
        return JOptionPane.showConfirmDialog(mainFrame, message, "Confirm Operator Edit",
                JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION;
    }

    private void refreshAndPersistView(boolean tableEdit) {
        if (!tableEdit) {
            updateDetailsPanel();
        }
        jobPanel.getJobPlacementsPanel().refresh();
        if (mainFrame.getFeedersTab() != null) {
            mainFrame.getFeedersTab().updateView();
        }
        refreshJobAndFeeders();
    }

    private void showEditError(Exception e) {
        e.printStackTrace();
        MessageBoxes.errorBox(mainFrame, "Operator Edit Error", e.getMessage());
        updateDetailsPanel();
        repaintRuntime();
    }

    private void updateDispenseToggleIcon() {
        globalDispenseToggle.setIcon(globalDispenseToggle.isSelected() ? Icons.centerPin : new SlashedIcon(Icons.centerPin));
        globalDispenseToggle.setText(globalDispenseToggle.isSelected() ? "Dispense On" : "Dispense Off");
    }

    private void repaintRuntime() {
        canvas.repaint();
    }

    @Subscribe public void jobLoaded(JobLoadedEvent event) { SwingUtilities.invokeLater(() -> refreshJobAndFeeders()); }
    @Subscribe public void placementChanged(PlacementChangedEvent event) { SwingUtilities.invokeLater(() -> repaintRuntime()); }
    @Subscribe public void placementsHolderLocationChanged(PlacementsHolderLocationChangedEvent event) { SwingUtilities.invokeLater(() -> repaintRuntime()); }

    private class PlacementDetailsRenderer extends DefaultTableCellRenderer {
        private final TableCellRenderer booleanRenderer = placementDetailsTable.getDefaultRenderer(Boolean.class);
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                boolean focus, int row, int column) {
            Component component = column >= 4 && column <= 5
                    ? booleanRenderer.getTableCellRendererComponent(table, value, selected, focus, row, column)
                    : super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            if (!selected) {
                component.setBackground(UIManager.getColor("Table.background"));
                component.setForeground(UIManager.getColor("Table.foreground"));
            }
            if (!selected && row >= 0 && row < rowPlacements.size()) {
                RowPlacement rowPlacement = rowPlacements.get(row);
                boolean placed = jobPanel.getJob() != null
                        && jobPanel.getJob().retrievePlacedStatus(rowPlacement.boardLocation, rowPlacement.placement.getId());
                if (!rowPlacement.placement.isEnabled()) {
                    component.setBackground(new Color(255, 230, 210));
                    component.setForeground(new Color(120, 55, 20));
                }
                else if (placed) {
                    component.setBackground(new Color(255, 243, 204));
                    component.setForeground(new Color(105, 80, 20));
                }
                else if (column == 1) {
                    if (rowPlacement.placement.getType() == Placement.Type.Fiducial) {
                        component.setForeground(new Color(44, 150, 82));
                    }
                    else if (rowPlacement.placement.getType() == Placement.Type.Dispense) {
                        component.setForeground(new Color(210, 132, 34));
                    }
                    else {
                        component.setForeground(new Color(35, 92, 170));
                    }
                }
            }
            return component;
        }
    }

    private static class SlashedIcon implements Icon {
        private final Icon delegate;
        SlashedIcon(Icon delegate) { this.delegate = delegate == null ? new ImageIcon() : delegate; }
        @Override public int getIconWidth() { return delegate.getIconWidth(); }
        @Override public int getIconHeight() { return delegate.getIconHeight(); }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            delegate.paintIcon(c, g, x, y);
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(new Color(190, 30, 30));
                g2.setStroke(new java.awt.BasicStroke(2.4f));
                g2.drawLine(x + 1, y + getIconHeight() - 2, x + getIconWidth() - 2, y + 1);
            }
            finally {
                g2.dispose();
            }
        }
    }

    private static class RowPlacement {
        final PlacementsHolderLocation<?> boardLocation;
        final Placement placement;
        RowPlacement(PlacementsHolderLocation<?> boardLocation, Placement placement) {
            this.boardLocation = boardLocation;
            this.placement = placement;
        }
    }
}
