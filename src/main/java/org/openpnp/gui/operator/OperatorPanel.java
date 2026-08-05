package org.openpnp.gui.operator;

import java.awt.BorderLayout;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.GridLayout;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.FilenameFilter;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

import org.openpnp.ConfigurationListener;
import org.openpnp.events.JobLoadedEvent;
import org.openpnp.events.PlacementChangedEvent;
import org.openpnp.events.PlacementsHolderLocationChangedEvent;
import org.openpnp.gui.JobPanel;
import org.openpnp.gui.MainFrame;
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
    private final MainFrame mainFrame;
    private final JobPanel jobPanel;
    private final OperatorRuntimeCanvas canvas = new OperatorRuntimeCanvas();
    private final JButton startNewJobButton = new JButton("Start New Job");
    private final JButton startButton = new JButton("Start");
    private final JButton pauseResumeButton = new JButton("Pause/Resume");
    private final JButton stopButton = new JButton("Stop");
    private final JButton resetBoardButton = new JButton("Reset Board");
    private final JButton modifyLastRunButton = new JButton("Modify Last Run");
    private final JButton openNewJobButton = new JButton("Open New Job");
    private final List<AbstractModelObject> feederListeners = new ArrayList<>();
    private final PropertyChangeListener feederPropertyListener = e -> repaintRuntime();
    private String previousState = "Stopped";

    public OperatorPanel(MainFrame mainFrame, JobPanel jobPanel) {
        super(new BorderLayout(8, 8));
        this.mainFrame = mainFrame;
        this.jobPanel = jobPanel;
        setBorder(new TitledBorder("Runtime"));

        JPanel controls = new JPanel(new GridLayout(0, 3, 6, 6));
        controls.add(startNewJobButton);
        controls.add(startButton);
        controls.add(pauseResumeButton);
        controls.add(stopButton);
        controls.add(resetBoardButton);
        controls.add(modifyLastRunButton);
        controls.add(openNewJobButton);
        add(controls, BorderLayout.NORTH);
        add(canvas, BorderLayout.CENTER);

        JTextArea legend = new JTextArea("Tray: gray pockets are used, blue pockets are available. Boards are rounded shapes; placements are x marks; fiducials are green dots; dispense points are circles.");
        legend.setEditable(false);
        legend.setLineWrap(true);
        legend.setWrapStyleWord(true);
        legend.setBorder(new EmptyBorder(4, 4, 4, 4));
        add(new JScrollPane(legend), BorderLayout.SOUTH);

        startNewJobButton.addActionListener(e -> openJob(true));
        openNewJobButton.addActionListener(e -> openJob(true));
        resetBoardButton.addActionListener(e -> resetCurrentJob());
        modifyLastRunButton.addActionListener(e -> showJobEditor());
        startButton.addActionListener(e -> jobPanel.startPauseResumeJobAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-start")));
        pauseResumeButton.addActionListener(e -> jobPanel.startPauseResumeJobAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-pause-resume")));
        stopButton.addActionListener(e -> jobPanel.stopJobAction.actionPerformed(new ActionEvent(this, ActionEvent.ACTION_PERFORMED, "operator-stop")));
        jobPanel.addPropertyChangeListener("state", e -> handleJobStateChange((String) e.getOldValue(), (String) e.getNewValue()));
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

    private final MachineListener machineListener = new MachineListener.Adapter() {
        @Override public void machineEnabled(Machine machine) { SwingUtilities.invokeLater(() -> updateButtons()); }
        @Override public void machineDisabled(Machine machine, String reason) { SwingUtilities.invokeLater(() -> updateButtons()); }
        @Override public void machineHomed(Machine machine, boolean isHomed) { SwingUtilities.invokeLater(() -> updateButtons()); }
    };

    private boolean machineReady() {
        Machine machine = Configuration.get().getMachine();
        return machine != null && machine.isEnabled() && machine.isHomed();
    }

    private void updateButtons() {
        String state = jobPanel.getJobState();
        boolean ready = machineReady();
        boolean hasJob = jobPanel.getJob() != null && jobPanel.getJob().getFile() != null;
        boolean stopped = "Stopped".equals(state);
        boolean runningOrPaused = "Running".equals(state) || "Paused".equals(state);
        startNewJobButton.setEnabled(ready);
        startButton.setEnabled(hasJob && ready && stopped);
        pauseResumeButton.setEnabled(hasJob && ready && runningOrPaused);
        stopButton.setEnabled(hasJob && runningOrPaused);
        resetBoardButton.setVisible(stopped && hasJob);
        modifyLastRunButton.setVisible(stopped && hasJob);
        openNewJobButton.setVisible(stopped && hasJob);
        canvas.setSelectedTool(mainFrame.getMachineControls().getSelectedTool());
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
        previousState = oldState;
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
