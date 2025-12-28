package managerV2;

import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;

/**
 * PasswordManagerLogic
 * --------------------
 * UI wiring helpers for Add / Retrieve / Edit / Delete / Copy / Search features.
 * Each method attaches listeners to buttons/fields and delegates persistence to FileUtils
 * and crypto to EncryptionUtils.
 */
public class PasswordManagerLogic {

    /**
     * Wire up the “Add / Save” flow on the Add tab.
     * - Toggles password visibility via the checkbox (show/hide bullets).
     * - Validates non-empty website/password.
     * - Rejects duplicates by scanning existing websites (case-insensitive).
     * - Appends encrypted entry, refreshes dropdown, clears fields, and surfaces a status message.
     */
    public static void attachAddButtonLogic(JFrame frame, JButton saveButton, JTextField websiteField, JPasswordField passwordField, JComboBox<String> siteDropdown, String masterPassword, JCheckBox showPasswordCheckbox) {    	
    	showPasswordCheckbox.addActionListener(e -> {
    	    if (showPasswordCheckbox.isSelected()) {
    	        passwordField.setEchoChar((char) 0); // show characters
    	    } else {
    	        passwordField.setEchoChar('•'); // hide characters
    	    }
    	});
    	
    	saveButton.addActionListener(e -> {
            String website = websiteField.getText().trim();
            String password = new String(passwordField.getPassword()).trim();

            if (website.isEmpty() || password.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please fill in both fields.");
                return;
            }

            try {
                byte[] bytes = EncryptionUtils.readVaultSalt();
                SecretKeySpec key = EncryptionUtils.getSecretKey(masterPassword, bytes);

                // Duplicate check: use loadAllEntries to get plaintext websites
                List<Entry> entries = FileUtils.loadAllEntries();
                boolean duplicateFound = false;
                for (Entry entry : entries) {
                    if (entry.website.equalsIgnoreCase(website)) {
                        duplicateFound = true;
                        break;
                    }
                }

                if (duplicateFound) {
                    JOptionPane.showMessageDialog(frame, "This website already exists.\nUse the Edit button to update its password.");
                    return;
                }

                FileUtils.appendEntry(website, EncryptionUtils.encrypt(password, key));
                Utils.populateDropdown(siteDropdown); // refresh UI list after write
                PasswordManagerUI.info("The password was added to the vault for: " + website);
                websiteField.setText("");
                passwordField.setText("");

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error saving file.");
                ex.printStackTrace();
            }
        });
    }

    /**
     * Wire up Delete:
     * - Confirms deletion, removes matching entry from the in-memory list, rewrites file
     *   (including verification header), refreshes dropdown, and posts a status message.
     */
    public static void attachDeleteButtonLogic(JFrame frame, JButton deleteButton, JComboBox<String> siteDropdown, String masterPassword) {
        deleteButton.addActionListener(e -> {
            String selectedSite = (String) siteDropdown.getSelectedItem();
            if (selectedSite == null) {
                JOptionPane.showMessageDialog(frame, "No site selected to delete.");
                return;
            }

            int confirm = JOptionPane.showConfirmDialog(frame,
                    "Are you sure you want to delete the entry for: " + selectedSite + "?",
                    "Confirm Delete",
                    JOptionPane.YES_NO_OPTION);

            if (confirm != JOptionPane.YES_OPTION) return;

            try {
                SecretKeySpec key = EncryptionUtils.getSecretKey(masterPassword);
                List<Entry> entries = FileUtils.loadAllEntries();
                entries.removeIf(entry -> entry.website.equalsIgnoreCase(selectedSite));
                FileUtils.writeEntriesWithVerification(entries, key);
                Utils.populateDropdown(siteDropdown); // refresh UI list after rewrite
                PasswordManagerUI.info("Password deleted for: " + selectedSite);

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(frame, "Error deleting entry.");
                ex.printStackTrace();
            }
        });
    }
    
    /**
     * Wire up Edit:
     * - Prompts for a new password for the selected site
     * - Rewrites the entire file with updated ciphertext for that entry (preserving verification header)
     * - Refreshes dropdown and surfaces a status message
     */
    public static void attachEditButtonLogic(JFrame frame, JButton editButton, JComboBox<String> siteDropdown, SecretKeySpec key) {
        editButton.addActionListener(e -> {
            String selectedSite = (String) siteDropdown.getSelectedItem();
            if (selectedSite == null) return;

            String newPassword = JOptionPane.showInputDialog(frame, "Enter new password for " + selectedSite);
            if (newPassword == null || newPassword.trim().isEmpty()) return;

            try {
                List<Entry> entries = FileUtils.loadAllEntries();
                

                for (Entry entry : entries) {
                    if (entry.website.equalsIgnoreCase(selectedSite)) {
                        entry.encryptedPassword = EncryptionUtils.encrypt(newPassword, key);
                        break;
                    }
                }
                
                FileUtils.writeEntriesWithVerification(entries, key);

                Utils.populateDropdown(siteDropdown); // refresh dropdown to reflect any renames/order changes
                PasswordManagerUI.info("Password updated for: " + selectedSite);
            } catch (Exception ex) {
                ex.printStackTrace();
                JOptionPane.showMessageDialog(frame, "An error occurred while updating the password.");
            }
        });
    }
    
    /**
     * Wire up “Copy” buttons:
     * - Copies the field’s contents to the system clipboard, posts a status message, and clears the field.
     * - If empty, shows a warning dialog.
     */
    public static void attachCopyButtonLogic(JFrame frame, JButton button, JPasswordField field, String label) {
        button.addActionListener(e -> {
            String text = new String(field.getPassword()).trim();
            if (!text.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null);
                PasswordManagerUI.info(label + " copied to clipboard");
                field.setText("");
            } else {
                JOptionPane.showMessageDialog(frame, "No " + label.toLowerCase() + " to copy.");
            }
        });
        
    }
    
    /**
     * Copy password from the selected site in the dropdown:
     * - Looks up the entry in the vault file and copies the decrypted password to clipboard.
     * - Shows a status message if found; errors surface a dialog.
     * Note: trailing “not found” branch is currently dead code and kept intentionally.
     */
    public static void attachCopyFromDropdownLogic(JFrame frame, JComboBox<String> siteDropdown, SecretKeySpec key) {
        String selectedSite = (String) siteDropdown.getSelectedItem();
        if (selectedSite == null || selectedSite.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No website selected.");
            return;
        }
        try {
            List<Entry> entries = FileUtils.loadAllEntries();
            boolean found = false;
            for (Entry entry : entries) {
                if (entry.website.equalsIgnoreCase(selectedSite)) {
                    String decrypted = EncryptionUtils.decrypt(entry.encryptedPassword, key);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(decrypted), null);
                    PasswordManagerUI.info("Password copied to clipboard for: " + selectedSite);
                    found = true;
                    break;
                }
            }
            // This is dead code in normal flows (dropdown is sourced from the same file).
            if (!found) {
                JOptionPane.showMessageDialog(frame, "Website not found in the password file.");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(frame, "Error retrieving password.");
            ex.printStackTrace();
        }
    }
    
    /**
     * Live-filter the site dropdown as the user types in the search box.
     * Uses a DocumentListener to rebuild dropdown items on each text change.
     * DocumentListener callbacks:
     *  - insertUpdate: text inserted → filter
     *  - removeUpdate: text removed → filter
     *  - changedUpdate: attribute/style change (rare for plain text) → filter
     */
    public static void attachSearchFieldFilterLogic(JTextField searchField, JComboBox<String> siteDropdown, List<String> allWebsites) {
        searchField.getDocument().addDocumentListener(new DocumentListener() { // listens to text model changes
            private void filterDropdown() {
                String query = searchField.getText().trim().toLowerCase();
                siteDropdown.removeAllItems();
                for (String site : allWebsites) {
                    if (site.toLowerCase().contains(query)) {
                        siteDropdown.addItem(site);
                    }
                }
            }

            @Override
            public void insertUpdate(DocumentEvent e) { filterDropdown(); } // when characters are inserted

            @Override
            public void removeUpdate(DocumentEvent e) { filterDropdown(); } // when characters are deleted

            @Override
            public void changedUpdate(DocumentEvent e) { filterDropdown(); } // attribute changes (typically no-op for JTextField)
        });
    }
}