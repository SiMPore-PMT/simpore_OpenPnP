package org.openpnp.gui.operator;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FileDialog;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JToolBar;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import org.openpnp.ConfigurationListener;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.events.PlacementChangedEvent;
import org.openpnp.events.PlacementsHolderLocationChangedEvent;
import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
import org.openpnp.gui.support.Icons;
import org.openpnp.gui.support.MessageBoxes;
import org.openpnp.model.AbstractModelObject;
import org.openpnp.model.Configuration;
import org.openpnp.model.Job;
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
    private final OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
    private final JButton startNewJobButton = createPrimaryButton("Start New Job", Icons.openSCadIcon);
    private final JButton startButton = createActionButton("Start", Icons.start,
            "Start the loaded job using the OpenPnP job processor");
    private final JButton pauseResumeButton = createActionButton("Pause / Resume", Icons.pause,
            "Pause or resume the current job");
    private final JButton stopButton = createActionButton("Stop", Icons.stop,
            "Stop the current job");
    private final JButton resetBoardButton = createActionButton("Reset Board", Icons.refresh,
            "Reset placements and tray feeder counts for the loaded job");
    private final JButton modifyLastRunButton = createActionButton("Modify Last Run", Icons.board,
            "Open the existing Job editor for the loaded job");
    private final JButton openNewJobButton = createActionButton("Open New Job", Icons.add,
            "Select and reset a different operator job");
    private final JLabel titleLabel = new JLabel("Operator Runtime");
    private final JLabel jobLabel = new JLabel("No job loaded");
    private final JLabel statusLabel = new JLabel("Machine status unavailable");
    private final JLabel guidanceLabel = new JLabel("Power and home the machine to start an operator job.", SwingConstants.CENTER);
    private final JPanel jobControlsPanel = new JPanel(new BorderLayout(6, 0));
    private final JPanel postRunPanel = new JPanel(new GridBagLayout());
    private final List<AbstractModelObject> feederListeners = new ArrayList<>();
    private final PropertyChangeListener feederPropertyListener = e -> repaintRuntime();

    public OperatorPanel(MainFrame mainFrame, JobPanel jobPanel) {
        super(new BorderLayout(8, 8));
        this.mainFrame = mainFrame;
        this.jobPanel = jobPanel;

        setBorder(new CompoundBorder(new TitledBorder("Runtime"), new EmptyBorder(8, 8, 8, 8)));
        add(createHeaderPanel(), BorderLayout.NORTH);
        add(createRuntimePanel(), BorderLayout.CENTER);
        add(createPostRunPanel(), BorderLayout.SOUTH);

        startNewJobButton.addActionListener(e -> openJob(true));
        openNewJobButton.addActionListener(e -> openJob(true));
        resetBoardButton.addActionListener(e -> resetCurrentJob());
        modifyLastRunButton.addActionListener(e -> showJobEditor());
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
        runtime.setBorder(new EmptyBorder(6, 0, 0, 0));
        guidanceLabel.setBorder(new EmptyBorder(4, 4, 4, 4));
        runtime.add(guidanceLabel, BorderLayout.NORTH);
        runtime.add(canvas, BorderLayout.CENTER);

        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.add(startNewJobButton);
        toolBar.addSeparator();
        toolBar.add(startButton);
        toolBar.add(pauseResumeButton);
        toolBar.add(stopButton);
        jobControlsPanel.add(toolBar, BorderLayout.CENTER);
        runtime.add(jobControlsPanel, BorderLayout.SOUTH);
        return runtime;
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

    private static JButton createPrimaryButton(String text, javax.swing.Icon icon) {
        JButton button = createActionButton(text, icon, null);
        button.setAlignmentX(Component.CENTER_ALIGNMENT);
        return button;
    }

    private static JButton createActionButton(String text, javax.swing.Icon icon, String tooltip) {
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
        jobControlsPanel.setVisible(true);
        canvas.setSelectedTool(mainFrame.getMachineControls().getSelectedTool());
    }

    private void updateStatusLabel(String state, boolean ready, boolean hasJob, String notReadyReason) {
        if ("Running".equals(state) || "Pausing".equals(state)) {
            statusLabel.setText("Running");
            statusLabel.setForeground(STATUS_RUNNING);
        }
        else if ("Paused".equals(state)) {
            statusLabel.setText("Paused");
            statusLabel.setForeground(STATUS_WAITING);
        }
        else if (!ready) {
            statusLabel.setText(notReadyReason);
            statusLabel.setForeground(STATUS_WAITING);
        }
        else if (hasJob) {
            statusLabel.setText("Ready to start");
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
            return "Job is running. Use Pause or Stop if operator intervention is needed.";
        }
        if ("Paused".equals(state)) {
            return "Job is paused. Resume to continue or Stop to abort.";
        }
        return "Job loaded and reset. Press Start to begin the operator run.";
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
            jobPanel.getJobPlacementsPanel().refresh();
            if (mainFrame.getFeedersTab() != null) {
                mainFrame.getFeedersTab().updateView();
            }
            refreshJobAndFeeders();
        }
        catch (Exception e) {
            e.printStackTrace();
            MessageBoxes.errorBox(mainFrame, "Operator Job Reset Error", e.getMessage());
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
        updateButtons();
        repaintRuntime();
    }

    private void repaintRuntime() {
        canvas.repaint();
    }

    @Subscribe public void jobLoaded(JobLoadedEvent event) { SwingUtilities.invokeLater(() -> refreshJobAndFeeders()); }
    @Subscribe public void placementChanged(PlacementChangedEvent event) { SwingUtilities.invokeLater(() -> repaintRuntime()); }
    @Subscribe public void placementsHolderLocationChanged(PlacementsHolderLocationChangedEvent event) { SwingUtilities.invokeLater(() -> repaintRuntime()); }
}
