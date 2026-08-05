package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.CompoundBorder;
import javax.swing.border.EmptyBorder;

import org.openpnp.gui.support.Icons;

@SuppressWarnings("serial")
public class AccessLevelLoginDialog extends JDialog {
    private AccessLevel selectedAccessLevel = AccessLevel.ADMINISTRATOR;

    public AccessLevelLoginDialog(Window owner) {
        super(owner, "OpenPnP Access Level", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(14, 14));
        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        content.add(createHeader(), BorderLayout.NORTH);
        content.add(createChoices(), BorderLayout.CENTER);
        setContentPane(content);
        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        JLabel title = new JLabel("Choose OpenPnP Workflow", SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2));
        JLabel message = new JLabel("Select the access level for this session.", SwingConstants.CENTER);
        message.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.add(title, BorderLayout.NORTH);
        header.add(message, BorderLayout.CENTER);
        return header;
    }

    private JPanel createChoices() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.insets = new Insets(0, 0, 8, 0);
        panel.add(createChoiceButton("Administrator", "Full OpenPnP workflow with setup and configuration tools.",
                Icons.lockOpenOutline, AccessLevel.ADMINISTRATOR), gbc);
        gbc.gridy++;
        gbc.insets = new Insets(0, 0, 0, 0);
        panel.add(createChoiceButton("Operator", "Simplified runtime workflow for loading and running jobs.",
                Icons.lockOutline, AccessLevel.OPERATOR), gbc);
        return panel;
    }

    private JButton createChoiceButton(String title, String description, javax.swing.Icon icon, AccessLevel accessLevel) {
        JButton button = new JButton("<html><b>" + title + "</b><br/><span style='font-weight:normal'>"
                + description + "</span></html>", icon);
        button.setHorizontalAlignment(SwingConstants.LEFT);
        button.setIconTextGap(12);
        button.setBorder(new CompoundBorder(BorderFactory.createEtchedBorder(), new EmptyBorder(10, 12, 10, 12)));
        button.addActionListener(e -> select(accessLevel));
        return button;
    }

    private void select(AccessLevel accessLevel) {
        selectedAccessLevel = accessLevel;
        dispose();
    }

    public AccessLevel getSelectedAccessLevel() {
        return selectedAccessLevel;
    }

    public static AccessLevel showDialog(Window owner) {
        AccessLevelLoginDialog dialog = new AccessLevelLoginDialog(owner);
        dialog.setVisible(true);
        return dialog.getSelectedAccessLevel();
    }
}
