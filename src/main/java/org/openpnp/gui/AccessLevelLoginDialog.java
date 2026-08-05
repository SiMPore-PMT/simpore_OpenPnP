package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Window;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.border.EmptyBorder;

@SuppressWarnings("serial")
public class AccessLevelLoginDialog extends JDialog {
    private AccessLevel selectedAccessLevel = AccessLevel.ADMINISTRATOR;

    public AccessLevelLoginDialog(Window owner) {
        super(owner, "OpenPnP Access Level", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(16, 16, 16, 16));
        content.add(new JLabel("Choose how to start OpenPnP.", SwingConstants.CENTER), BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.CENTER));
        JButton administrator = new JButton("Administrator");
        administrator.addActionListener(e -> select(AccessLevel.ADMINISTRATOR));
        JButton operator = new JButton("Operator");
        operator.addActionListener(e -> select(AccessLevel.OPERATOR));
        buttons.add(administrator);
        buttons.add(operator);
        content.add(buttons, BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setLocationRelativeTo(owner);
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
