package org.openpnp.gui;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.ActionEvent;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;

import org.openpnp.gui.support.Icons;

@SuppressWarnings("serial")
public class AccessLevelLoginDialog extends JDialog {
    private AccessLevel selectedAccessLevel;
    private final JPasswordField passwordField = new JPasswordField(18);
    private final JLabel errorLabel = new JLabel(" ");

    public AccessLevelLoginDialog(Window owner) {
        super(owner, "OpenPnP Login", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JPanel content = new JPanel(new BorderLayout(14, 14));
        content.setBorder(new EmptyBorder(18, 18, 18, 18));
        content.add(createHeader(), BorderLayout.NORTH);
        content.add(createPasswordPanel(), BorderLayout.CENTER);
        content.add(createButtons(), BorderLayout.SOUTH);
        setContentPane(content);
        pack();
        setMinimumSize(getPreferredSize());
        setLocationRelativeTo(owner);
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(8, 4));
        JLabel title = new JLabel("OpenPnP Access Login", Icons.lockOutline, SwingConstants.CENTER);
        title.setFont(title.getFont().deriveFont(Font.BOLD, title.getFont().getSize2D() + 2));
        JLabel message = new JLabel("Enter the shop-floor access password for this session.", SwingConstants.CENTER);
        message.setForeground(UIManager.getColor("Label.disabledForeground"));
        header.add(title, BorderLayout.NORTH);
        header.add(message, BorderLayout.CENTER);
        return header;
    }

    private JPanel createPasswordPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createEtchedBorder(), new EmptyBorder(12, 12, 12, 12)));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.anchor = GridBagConstraints.WEST;
        gbc.insets = new Insets(0, 0, 6, 8);
        panel.add(new JLabel("Password:"), gbc);
        gbc.gridx = 1;
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        panel.add(passwordField, gbc);
        gbc.gridx = 0;
        gbc.gridy = 1;
        gbc.gridwidth = 2;
        gbc.insets = new Insets(4, 0, 0, 0);
        errorLabel.setForeground(UIManager.getColor("OptionPane.errorDialog.titlePane.foreground"));
        panel.add(errorLabel, gbc);
        passwordField.addActionListener(this::login);
        return panel;
    }

    private JPanel createButtons() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton cancel = new JButton("Cancel");
        JButton login = new JButton("Login", Icons.accept);
        cancel.addActionListener(e -> dispose());
        login.addActionListener(this::login);
        panel.add(cancel);
        panel.add(login);
        return panel;
    }

    private void login(ActionEvent e) {
        String password = new String(passwordField.getPassword());
        if ("admin".equals(password)) {
            selectedAccessLevel = AccessLevel.ADMINISTRATOR;
            dispose();
        }
        else if ("op".equals(password)) {
            selectedAccessLevel = AccessLevel.OPERATOR;
            dispose();
        }
        else {
            errorLabel.setText("Invalid password. Use the operator or administrator access password.");
            passwordField.selectAll();
            passwordField.requestFocusInWindow();
        }
    }

    public AccessLevel getSelectedAccessLevel() {
        return selectedAccessLevel;
    }

    public static AccessLevel showDialog(Window owner) {
        AccessLevelLoginDialog dialog = new AccessLevelLoginDialog(owner);
        dialog.setVisible(true);
        AccessLevel selected = dialog.getSelectedAccessLevel();
        return selected == null ? AccessLevel.ADMINISTRATOR : selected;
    }

    public static AccessLevel showSwitchDialog(Window owner, AccessLevel currentAccessLevel) {
        AccessLevelLoginDialog dialog = new AccessLevelLoginDialog(owner);
        dialog.setVisible(true);
        AccessLevel selected = dialog.getSelectedAccessLevel();
        return selected == null ? currentAccessLevel : selected;
    }
}
