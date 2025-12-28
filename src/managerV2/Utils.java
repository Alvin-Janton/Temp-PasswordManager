package managerV2;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.*;
import com.formdev.flatlaf.*;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.util.prefs.Preferences;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * Utils
 * -----
 * Small helpers used across the UI:
 *  - Password generation (client-side, random)
 *  - Theme switching with FlatLaf (light/dark) + persistence via Preferences
 *  - Populate the website dropdown by scanning the vault file (skips metadata blocks)
 *  - Keyboard shortcut bindings for Retrieve / Generate / Add panels (Swing InputMap/ActionMap)
 *
 * Notes:
 *  - Preferences are stored under userRoot() at node "PasswordManager".
 */
public class Utils {
    private static final String LOWERCASE = "abcdefghijklmnopqrstuvwxyz";
    private static final String UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final String DIGITS = "0123456789";
    private static final String SPECIAL_CHARACTERS = "!@#$%^&*()-_=+<>?";
    public static List<String> allWebsites = new ArrayList<>();
    /** Preferences node for this app; used to remember theme choice (darkMode true/false). */
    private static final Preferences prefs = Preferences.userRoot().node("PasswordManager"); // durable per-user settings

    /** 
     * Generate a 16-char password (first char forced to a digit, rest random from the pool).
     * Purpose: quick, human-usable random string; crypto-strength comes from SecureRandom.
     * Note to self: Current implementation forces a number in the front every time
     */
    public static String generatePassword() {
        String charPool = LOWERCASE + UPPERCASE + DIGITS + SPECIAL_CHARACTERS;
        SecureRandom random = new SecureRandom();
        StringBuilder password = new StringBuilder();
        password.append(DIGITS.charAt(random.nextInt(DIGITS.length()))); // ensure at least one digit
        for (int i = 1; i < 16; i++) {
            password.append(charPool.charAt(random.nextInt(charPool.length())));
        }
        return password.toString();
    }
    
    /**
     * Rebuild the site dropdown from the vault file, skipping internal metadata blocks.
     * Side effects: clears {@link #allWebsites} and repopulates the JComboBox.
     */
    public static void populateDropdown(JComboBox<String> dropdown) {
        dropdown.removeAllItems(); // Clear previous items
        allWebsites.clear();

        File passwordFile = FileUtils.getPasswordFile();

        try (Scanner scanner = new Scanner(passwordFile)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                // Skip internal blocks
                if(line.equals("__SALT__")) {
                	if (scanner.hasNextLine()) scanner.nextLine(); // Salt value
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator line
                    continue;
                }
                if (line.equals("__VERIFICATION__")) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // encrypted "check123"
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator line
                    continue;
                }
                if (line.equals("__RECOVERY__")) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // recovery key hash
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator line
                    continue;
                }

                // Read encrypted website (1st line of a triple: encrypted website, password, separator)
                String encryptedWebsite = line;

                if (scanner.hasNextLine()) scanner.nextLine(); // skip password
                if (scanner.hasNextLine()) scanner.nextLine(); // skip separator

                if (!encryptedWebsite.isBlank() && !encryptedWebsite.equals("------------------------")) {
                    try {
                        String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, MasterKeyManager.key);
                        allWebsites.add(websitePlain);
                        dropdown.addItem(websitePlain);
                    } catch (Exception e) {
                        // Skip entries that fail to decrypt
                        e.printStackTrace();
                    }
                }
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        }
    }
    
    /**
     * Switch UI to FlatLightLaf and persist darkMode=false.
     * Uses UIManager to set L&F, then updates the passed frame tree.
     */
    public static void applyLightMode(JFrame frame) {
        try {
            UIManager.setLookAndFeel(new FlatLightLaf());
            SwingUtilities.updateComponentTreeUI(frame); // refresh all child components
            prefs.putBoolean("darkMode", false);
        } catch (UnsupportedLookAndFeelException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to switch to light mode.");
        }
    }
    
    /**
     * Switch UI to FlatDarkLaf and persist darkMode=true.
     * Uses UIManager to set L&F, then updates the passed frame tree.
     */
    public static void applyDarkMode(JFrame frame) {
        try {
            UIManager.setLookAndFeel(new FlatDarkLaf());
            SwingUtilities.updateComponentTreeUI(frame);
            prefs.putBoolean("darkMode", true);
        } catch (UnsupportedLookAndFeelException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to switch to dark mode.");
        }
    }
    
    /**
     * Read user's saved theme from Preferences and apply it (default: light).
     * This only toggles look & feel; it does not change any app colors directly.
     */
    public static void applySavedTheme(JFrame frame) {
        boolean darkMode = prefs.getBoolean("darkMode", false); // Default to light
        try {
            if (darkMode) {
                UIManager.setLookAndFeel(new FlatDarkLaf());
            } else {
                UIManager.setLookAndFeel(new FlatLightLaf());
            }
            SwingUtilities.updateComponentTreeUI(frame);
        } catch (UnsupportedLookAndFeelException ex) {
            JOptionPane.showMessageDialog(frame, "Failed to apply saved theme.");
        }
    }
    
    /**
     * Bind keyboard shortcuts for the Retrieve panel:
     *  - Ctrl/Cmd+C in search field: copy selection if there is one, otherwise click "Copy" button
     *  - Enter in search field: click "Copy"
     *  - Enter on dropdown: click "Copy"
     *  - Ctrl/Cmd+D (global while Retrieve is visible): click "Delete"
     *  - Ctrl/Cmd+E (global while Retrieve is visible): click "Edit"
     *
     * Notes on Swing key systems:
     *  - InputMap maps KeyStroke → action name for a given focus condition (e.g., WHEN_FOCUSED).
     *  - ActionMap maps action name → Action object (the code to run).
     */
    public static void bindRetrieveSmartKeys(JRootPane rootPane, JPanel retrievePanel, JTextField searchField, JComponent siteDropdown, JButton copyButton, JButton deleteButton, JButton editButton) {

    	KeyStroke ksCopy = KeyStroke.getKeyStroke( // Keyboard shortcut object: Ctrl/Cmd + C on current OS
                KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );
        InputMap sim = searchField.getInputMap(JComponent.WHEN_FOCUSED); // InputMap for the search field while it has focus
        ActionMap sam = searchField.getActionMap(); // Maps action IDs to executable Action objects

        Action defaultCopy = sam.get("copy"); // may be null on some LAFs

        sam.put("smartCopy", new AbstractAction() { // New action ID: "smartCopy" → conditional copy behavior
            @Override public void actionPerformed(ActionEvent e) {
                String sel = searchField.getSelectedText();
                if (sel != null && !sel.isEmpty()) {
                    if (defaultCopy != null) defaultCopy.actionPerformed(e);
                    else searchField.copy();
                } else {
                    copyButton.doClick(); // no selection → trigger the UI "Copy" flow
                }
            }
        });
        sim.put(ksCopy, "smartCopy"); // Bind Ctrl/Cmd+C to smartCopy
        
        KeyStroke ksEnter = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0);
        sam.put("searchEnterCopy", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                copyButton.doClick(); // Enter in search → Copy
            }
        });
        sim.put(ksEnter, "searchEnterCopy");

        // ---- (B) Dropdown bindings (only when dropdown has focus) ----
        InputMap dim = siteDropdown.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap dam = siteDropdown.getActionMap();

        // Enter -> Copy
        dam.put("dropEnterCopy", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                copyButton.doClick();
            }
        });
        dim.put(ksEnter, "dropEnterCopy");
        
        // ---- (C) Global Ctrl/Cmd+D (works anywhere while Retrieve is visible) ----
        KeyStroke ksCtrlD = KeyStroke.getKeyStroke(
                KeyEvent.VK_D,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );
        InputMap rim = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW); // active for the whole window
        ActionMap ram = rootPane.getActionMap();

        ram.put("retrieveCtrlDDelete", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!retrievePanel.isShowing()) return; // only on Retrieve view
                deleteButton.doClick();
            }
        });
        rim.put(ksCtrlD, "retrieveCtrlDDelete");
        
        // ---- (D) Global Ctrl/Cmd+E (works anywhere while Retrieve is visible) ----
        KeyStroke ksCtrlE = KeyStroke.getKeyStroke(
                KeyEvent.VK_E,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );

        InputMap eim = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        ActionMap eam = rootPane.getActionMap();

        eam.put("retrieveCtrlEEdit", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!retrievePanel.isShowing()) return;
                editButton.doClick();
            }
        });
        eim.put(ksCtrlE, "retrieveCtrlEEdit");
    }
    
    /**
     * Bind keyboard shortcuts for the Generate panel:
     *  - Ctrl/Cmd+G = click "Generate"
     *  - Ctrl/Cmd+Shift+C = click "Copy Generated Password"
     */
    public static void bindGenerateKeys(JRootPane rootPane, JButton generateButton, JButton copyGeneratedButton) {
    	// Ctrl/Cmd+G = Generate
        KeyStroke ksGen = KeyStroke.getKeyStroke( // Keyboard shortcut object: Ctrl/Cmd + G
                KeyEvent.VK_G,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ksGen, "doGenerate"); // map keystroke → action id
        rootPane.getActionMap().put("doGenerate", new AbstractAction() { // action id → code (click the button)
            @Override public void actionPerformed(ActionEvent e) {
                generateButton.doClick();
            }
        });
        
     // Ctrl/Cmd+Shift+C = Copy Generated Password
        KeyStroke ksCopyGen = KeyStroke.getKeyStroke(
                KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx() | KeyEvent.SHIFT_DOWN_MASK
        );
        rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(ksCopyGen, "copyGenerated");
        rootPane.getActionMap().put("copyGenerated", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                copyGeneratedButton.doClick();
            }
        });
    }
    
    /**
     * Bind keyboard shortcuts for the Add panel:
     *  - Ctrl/Cmd+S (global while Add is visible) = click "Save/Add"
     */
    public static void bindAddKeys(JRootPane rootPane, JButton addButton, JPanel addPanel) {
    	KeyStroke ksSave = KeyStroke.getKeyStroke( // Keyboard shortcut object: Ctrl/Cmd + S
                KeyEvent.VK_S,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()
        );

        InputMap im = rootPane.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW); // active for the whole window
        ActionMap am = rootPane.getActionMap(); // maps id → action

        im.put(ksSave, "add.savePassword");
        am.put("add.savePassword", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                if (!addPanel.isShowing()) return; // only when Add tab is visible
                addButton.doClick();
            }
        });
    }

}