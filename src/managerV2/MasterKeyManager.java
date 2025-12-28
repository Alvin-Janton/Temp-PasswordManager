package managerV2;

import javax.crypto.spec.SecretKeySpec; 
import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.*;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Scanner;

/**
 * MasterKeyManager
 * ----------------
 * Handles master key lifecycle and authentication:
 *  - First-run setup (generate or manually enter a master key), then derives an AES key via EncryptionUtils.getSecretKey(...)
 *  - Persists a verification block (__VERIFICATION__) and a recovery block (__RECOVERY__) to the vault file
 *  - Authenticates returning users by decrypting the verification check ("check123")
 *  - Provides recovery flow to reset the master key using a pre-hashed recovery key
 *  - Starts/stops the per-vault Cloud backup scheduler after successful authentication
 *
 * File format (top of vault):
 *        __SALT__
 *   <Base64 encoded salt>
 * 
 *   __VERIFICATION__
 *   <encrypted "check123">
 *   ------------------------
 *   __RECOVERY__
 *   <Base64(SHA-256(recoveryKey))>
 *   ------------------------
 *
 * Notes:
 *  - This class shows modal dialogs (Swing) and writes to the active vault file obtained from FileUtils.getPasswordFile().
 *  - Security: masterPassword is kept in memory for the process lifetime; consider future zeroization/char[] usage.
 *  - Recovery hashing is a simple SHA-256 (no salt) used only for equality check of the recovery token; preserved as-is.
 */
public class MasterKeyManager {
    /** Session-derived AES key for encrypt/decrypt, obtained from EncryptionUtils.getSecretKey(masterPassword). */
    public static SecretKeySpec key; /** Sensitive: held in memory for the session; zeroization not implemented (by design for now). */

    /** The plaintext master password/token used to derive {@link #key}. */
    public static String masterPassword; /** Sensitive: retained in RAM to avoid repeated prompts; future: prefer char[] + wipe after use. */

    /** Reference to the active backup scheduler (started post-auth if enabled in per-vault settings). */
    private static BackupScheduler schedulerRef;/** Lifecycle: set after auth; shutdown+null before replacing to avoid duplicate timers. */

    /**
     * Show a password prompt dialog.
     * @param isSetup true to show "Create a master password", false to show "Enter your master password"
     * @param parentFrame owner for modality/centering
     * @return trimmed password/token, or null if canceled
     */
    public static String promptForMasterPassword(boolean isSetup, JFrame parentFrame) {
        JPanel panel = new JPanel();
        JLabel label = new JLabel(isSetup ? "Create a master password:" : "Enter your master password:");
        JPasswordField passwordField = new JPasswordField(20); /** 20 = columns hint used by Swing for preferred width; not a length cap. */
        JCheckBox showPasswordCheck = new JCheckBox("Show");

        passwordField.setEchoChar('*');
        showPasswordCheck.addActionListener(e ->
                passwordField.setEchoChar(showPasswordCheck.isSelected() ? (char) 0 : '*')
        );

        panel.add(label);
        panel.add(passwordField);
        panel.add(showPasswordCheck);

        if (!isSetup) { /** Returning users get a recovery option if they forgot the master key. */
            JButton forgotButton = new JButton("Forgot Master Key?");
            forgotButton.addActionListener(e -> handleLostMasterPassword(parentFrame));
            panel.add(forgotButton);
        }

        int result = JOptionPane.showConfirmDialog(null, panel, "Master Password",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);

        if (result == JOptionPane.OK_OPTION) {
            return new String(passwordField.getPassword()).trim();
        } else {
            return null;
        }
    }

    /**
     * Create a new, random recovery key (32 chars, A-Z/a-z/0-9).
     * Caller shows this to the user and stores only the hash in the vault.
     */
    public static String generateRecoveryKey() {
        SecureRandom random = new SecureRandom();
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            key.append(chars.charAt(random.nextInt(chars.length())));
        }
        return key.toString();
    }
    
    /**
     * Hash a recovery key using SHA-256 and return Base64 encoding.
     * Purpose: equality check only (not key derivation). Kept as-is for backward compatibility.
     */
    public static String hashRecoveryKey(String key) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(key.getBytes("UTF-8"));
        return Base64.getEncoder().encodeToString(hash);
    }
 
    /**
     * Compare a plaintext recovery key against the stored Base64(SHA-256(...)) hash.
     */
    public static boolean verifyRecoveryKey(String inputKey, String storedHash) throws Exception {
        return hashRecoveryKey(inputKey).equals(storedHash);
    }

    /**
     * First-run or post-recovery setup:
     *  - Ask user to generate a strong master key OR type their own
     *  - Derive AES key via EncryptionUtils.getSecretKey(...)
     *  - Generate recovery key, hash it, and write both verification/recovery sections to the vault
     *  - Show dialogs to copy the master key and recovery key
     *  - Set in-memory {@link #masterPassword} and {@link #key}
     *
     * Side effects: overwrites the current vault file header with new verification/recovery blocks.
     */
    public static void setupNewMasterKeyAndRecovery(JFrame frame) throws Exception {
        // 1) Ask the user: Generate vs Manual
        String[] options = {"Generate a master key for me", "Create my own master key", "Cancel"};
        int choice = JOptionPane.showOptionDialog(
                frame,
                "How would you like to set your master key?",
                "Vault Setup",
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
            JOptionPane.showMessageDialog(frame, "Setup canceled.");
            System.exit(0);
            return;
        }

        // 2) Determine the master key source
        String masterKeyToken;
        if (choice == 0) {
            // Generate path (existing flow)
            masterKeyToken = generateMasterKeyToken();
            // Optional: show immediately for user to copy; final dialog still shown below.
            // showMasterKeyDialogWithCopy(masterKeyToken);
        } else {
            // Manual path (reuse your existing prompt for setup)
            masterKeyToken = promptForMasterPassword(true, frame);
            if (masterKeyToken == null || masterKeyToken.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "No master key entered. Setup canceled.");
                System.exit(0);
                return;
            }
        }
     // 1) Generate per-vault random salt (16 bytes)
        byte[] salt = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);

        // 3) Derive key exactly like you do today
        SecretKeySpec newKey = EncryptionUtils.getSecretKey(masterKeyToken, salt);

        // 4) Always create a fresh recovery key + hash (your existing approach)
        String recoveryKey = generateRecoveryKey();
        String recoveryKeyHash = hashRecoveryKey(recoveryKey);

        // 5) Write verification + recovery sections (unchanged)
        try (PrintWriter writer = new PrintWriter(new FileWriter(FileUtils.getPasswordFile()))) {
        	writer.println("__SALT__");
        	writer.println(Base64.getEncoder().encodeToString(salt));
        	writer.println("------------------------");
            writer.println("__VERIFICATION__");
            writer.println(EncryptionUtils.encrypt("check123", newKey));
            writer.println("------------------------");
            writer.println("__RECOVERY__");
            writer.println(recoveryKeyHash);
            writer.println("------------------------");
        }

        // 6) Show master key (generated or manual) + show recovery key for copying/secure storage
        showMasterKeyDialogWithCopy(masterKeyToken);
        showRecoveryKeyDialog(recoveryKey);

        // 7) Persist in memory (unchanged)
        masterPassword = masterKeyToken;
        key = newKey;
    }

    /**
     * Generate a random master key token (length ~40) with mixed charset.
     * Note: First character is chosen from the first 10 chars of 'chars' to ensure a digit; preserved as-is.
     */
    private static String generateMasterKeyToken() {
        SecureRandom random = new SecureRandom();
        String chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()-_=+<>?";
        StringBuilder token = new StringBuilder();
        for (int i = 1; i < 40; i++) {
            token.append(chars.charAt(random.nextInt(chars.length())));
        }
        return token.toString();
    }

    /**
     * Modal dialog that displays the master key and offers a Copy-to-Clipboard button.
     * Layout uses absolute bounds (null layout) for simplicity; kept to avoid refactoring.
     */
    private static void showMasterKeyDialogWithCopy(String masterKey) {
        JDialog dialog = new JDialog((JFrame) null, "Master Key", true);
        dialog.setSize(400, 200);
        dialog.setLayout(null);
        dialog.setLocationRelativeTo(null);

        JLabel label = new JLabel("Your master key (copy and store securely):");
        label.setBounds(20, 20, 360, 20);
        dialog.add(label);

        JTextField keyField = new JTextField(masterKey);
        keyField.setBounds(20, 50, 340, 25);
        keyField.setEditable(false);
        dialog.add(keyField);

        JButton copyButton = new JButton("Copy to Clipboard");
        copyButton.setBounds(20, 90, 160, 25);
        dialog.add(copyButton);

        JButton okButton = new JButton("OK");
        okButton.setBounds(200, 90, 80, 25);
        dialog.add(okButton);

        copyButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(masterKey), null);
            JOptionPane.showMessageDialog(dialog, "Master key copied to clipboard.");
        });

        okButton.addActionListener(e -> dialog.dispose());

        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setVisible(true);
    }

    /**
     * Modal dialog that displays the recovery key and offers a Copy-to-Clipboard button.
     * Layout uses absolute bounds (null layout); kept for behavioral parity with the master key dialog.
     */
    private static void showRecoveryKeyDialog(String recoveryKey) {
        JDialog dialog = new JDialog((JFrame) null, "Recovery Key", true);
        dialog.setSize(400, 200);
        dialog.setLayout(null);
        dialog.setLocationRelativeTo(null);

        JLabel label = new JLabel("Your recovery key (copy and store securely):");
        label.setBounds(20, 20, 360, 20);
        dialog.add(label);

        JTextField keyField = new JTextField(recoveryKey);
        keyField.setBounds(20, 50, 340, 25);
        keyField.setEditable(false);
        dialog.add(keyField);

        JButton copyButton = new JButton("Copy to Clipboard");
        copyButton.setBounds(20, 90, 160, 25);
        dialog.add(copyButton);

        JButton okButton = new JButton("OK");
        okButton.setBounds(200, 90, 80, 25);
        dialog.add(okButton);

        copyButton.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                    new StringSelection(recoveryKey), null);
            JOptionPane.showMessageDialog(dialog, "Recovery key copied to clipboard.");
        });

        okButton.addActionListener(e -> dialog.dispose());

        dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);
        dialog.setVisible(true);
    }

    /**
     * Recovery flow for forgotten master password:
     *  - Prompt for recovery key and validate against stored hash in the vault
     *  - Force the user to export the old vault to a chosen file (plaintext copy) before reset
     *  - Call {@link #setupNewMasterKeyAndRecovery(JFrame)} to overwrite header with new master/recovery
     *  - Close all windows and exit to ensure a clean restart with the new key
     */
    public static void handleLostMasterPassword(JFrame frame) {
        try {
            String inputKey = JOptionPane.showInputDialog(frame, "Enter your recovery key:");
            if (inputKey == null || inputKey.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Recovery canceled.");
                return;
            }

            String storedHash = null;
            try (Scanner scanner = new Scanner(FileUtils.getPasswordFile())) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.equals("__RECOVERY__") && scanner.hasNextLine()) {
                        storedHash = scanner.nextLine();
                        break;
                    }
                }
            }

            if (storedHash == null || !verifyRecoveryKey(inputKey, storedHash)) {
                JOptionPane.showMessageDialog(frame, "Invalid recovery key.");
                return;
            }

            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setDialogTitle("Export old password file before reset");
            fileChooser.setSelectedFile(new File("old_passwords_backup.txt"));

            int userSelection = fileChooser.showSaveDialog(frame);
            if (userSelection == JFileChooser.APPROVE_OPTION) {
                File exportFile = fileChooser.getSelectedFile();
                try (
                    Scanner scanner = new Scanner(FileUtils.getPasswordFile());
                    PrintWriter writer = new PrintWriter(new FileWriter(exportFile))
                ) {
                    while (scanner.hasNextLine()) {
                        writer.println(scanner.nextLine());
                    }
                    JOptionPane.showMessageDialog(frame, "Old data exported to: " + exportFile.getAbsolutePath());
                } catch (IOException e) {
                    JOptionPane.showMessageDialog(frame, "Failed to export old data.");
                    e.printStackTrace();
                    return;
                }
            } else {
                JOptionPane.showMessageDialog(frame, "Reset canceled. No backup file selected.");
                return;
            }

            setupNewMasterKeyAndRecovery(frame); /** Overwrites header with new verification/recovery; prior contents remain in file body as-is. */

            JOptionPane.showMessageDialog(frame, "Password reset complete. Please restart the app.");
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "An error occurred during recovery.");
            e.printStackTrace();
        }

        for (Frame f : Frame.getFrames()) f.dispose(); /** Close all open Swing/AWT windows to avoid zombie UI before exiting. */
        System.exit(0);
    }

    /**
     * Entry point at app start:
     *  - If no vault exists, run setup flow
     *  - Otherwise, prompt up to 3 times for master password and verify via __VERIFICATION__ block
     *  - On success, start the backup scheduler from per-vault settings (if enabled)
     */
    public static void authenticateUserOrSetupMasterKey(JFrame frame) {
        try {
            File passwordFile = FileUtils.getPasswordFile();
            if (!passwordFile.exists()) {
                // First time use — create password
                setupNewMasterKeyAndRecovery(frame);
            } else {
                final int MAX_ATTEMPTS = 3;
                int attempts = 0;
                boolean verified = false;

                while (attempts < MAX_ATTEMPTS) {
                    masterPassword = promptForMasterPassword(false, frame);
           
                    if (masterPassword == null || masterPassword.trim().isEmpty()) {
                        JOptionPane.showMessageDialog(null, "No master password entered. Exiting.");
                        System.exit(0);
                    }
                    byte[] salt = EncryptionUtils.readVaultSalt(); // returns null if __SALT__ missing
                    if (salt == null || salt.length == 0) {
                        JOptionPane.showMessageDialog(null, "Vault is missing __SALT__ section. Exiting.");
                        System.exit(1);
                    }
                    key = EncryptionUtils.getSecretKey(masterPassword, salt);
                    try (Scanner scanner = new Scanner(passwordFile)) {
                    	if (scanner.nextLine().equals("__SALT__")) {
                            if (scanner.hasNextLine()) scanner.nextLine(); // salt base64
                            if (scanner.hasNextLine()) scanner.nextLine(); // separator
                            //continue;
                        }
                    	
                        if (!scanner.nextLine().equals("__VERIFICATION__")) {
                            JOptionPane.showMessageDialog(null, "Corrupted password file. Exiting.");
                            System.exit(1);
                        }

                        String encryptedCheck = scanner.nextLine(); 
                        String decryptedCheck = EncryptionUtils.decrypt(encryptedCheck, key);

                        if (decryptedCheck.equals("check123")) {
                            scanner.nextLine(); // skip separator
                            verified = true;
                            break;
                        } else {
                            attempts++;
                            JOptionPane.showMessageDialog(null, "Incorrect master password. Attempts left: " + (MAX_ATTEMPTS - attempts));
                        }
                    } catch (Exception e) {
                        attempts++;
                        JOptionPane.showMessageDialog(null, "Incorrect master password. Attempts left: " + (MAX_ATTEMPTS - attempts));
                    }
                }

                if (!verified) {
                    JOptionPane.showMessageDialog(null, "Too many failed attempts. Exiting.");
                    System.exit(0);
                }
            }
            
            schedulerRef = CloudBackupUtils.startSchedulerFromSettingsAfterAuth(frame);// Start per-vault auto-backup if enabled; may return null if disabled.
            
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(null, "Error verifying master password.");
            ex.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * Open the Backup Schedule dialog and apply changes immediately.
     * If enabled/updated, restarts the scheduler; optionally triggers a one-off run in ~30s for feedback.
     */
    public static void openBackupScheduleAndApply(JFrame frame) {
        boolean saved = CloudBackupUtils.showBackupScheduleDialog(frame);
        if (!saved) return;

        // Restart scheduler according to the new settings
        if (schedulerRef != null) {
            schedulerRef.shutdown();
            schedulerRef = null;
        }
        schedulerRef = CloudBackupUtils.startSchedulerFromSettingsAfterAuth(frame);

        // Optional: one-off run for quick feedback during testing
        if (schedulerRef != null) {
            schedulerRef.runOnceInSeconds(30);
        }
    }
}