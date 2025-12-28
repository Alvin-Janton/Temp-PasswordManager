package managerV2;

import javax.crypto.spec.SecretKeySpec; 
import javax.swing.*;
import java.awt.Dimension;
import java.io.*;
import java.util.*;
import com.opencsv.CSVReader;
import com.opencsv.CSVWriter;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * FileUtils: all file I/O around the vault file (passwordFile) and import/export helpers.
 * - Vault format (line-based):
 *     encrypted_Website
 *     encrypted_Password
 *     ------------------------
 * - Metadata blocks:
 * 	         __SALT__
 * 	   < Base64 encoded salt>	
 * 	   ------------------------	
 * 
 *        __VERIFICATION__
 *     <encrypted "check123">
 *     ------------------------
 *
 *       __RECOVERY__
 *     <recovery-hash>
 *     ------------------------
 *
 * Notes:
 * - Methods are small and purposeful; dialogs belong to callers/UI, but here we keep
 *   the common flows that require user selection (Chooser) for convenience.
 * - No encryption logic here—use EncryptionUtils and MasterKeyManager.
 */
public class FileUtils {
    /** The primary on-disk vault (line-based). */
    private static final File passwordFile = new File("test.txt");

    /** @return the File object pointing at the active vault ("passwords2.txt"). */
    public static File getPasswordFile() {
        return passwordFile;
    }

    /**
     * Append a single (website, encryptedPassword) pair to the vault, followed by the separator line.
     * Format-preserving append (does not touch verification/recovery blocks).
     */
    public static void appendEntry(String website, String encryptedPassword) throws IOException {
        try (PrintWriter writer = new PrintWriter(new FileWriter(passwordFile, true))) {
            writer.println(EncryptionUtils.encrypt(website, MasterKeyManager.key));
            writer.println(encryptedPassword);
            writer.println("------------------------");
        } catch (Exception e) {
            throw new IOException("Failed to encrypt website", e);
        }
    }

    /**
     * Load all (website, encryptedPassword) pairs from the vault.
     * Skips __VERIFICATION__ and __RECOVERY__ metadata blocks if present.
     */
    public static List<Entry> loadAllEntries() throws Exception {
        List<Entry> entries = new ArrayList<>();
        try (Scanner scanner = new Scanner(passwordFile)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
             // ---- Skip internal header sections ----
                if ("__SALT__".equals(line)) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // salt (Base64)
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator
                    continue;
                }
                if ("__VERIFICATION__".equals(line)) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // encrypted check
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator
                    continue;
                }
                if ("__RECOVERY__".equals(line)) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // recovery hash
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator
                    continue;
                }
                // Read (encrypted website, encrypted password, separator)
                String encryptedWebsite = line;
                String encryptedPassword = scanner.hasNextLine() ? scanner.nextLine() : "";
                if (scanner.hasNextLine()) scanner.nextLine(); // separator (or EOF)
                String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, MasterKeyManager.key);
                entries.add(new Entry(websitePlain, encryptedPassword));
            }
        }
        return entries;
    }

    /**
     * Rewrite the vault from a list of entries and preserve any existing __RECOVERY__ section.
     * Also writes a fresh __VERIFICATION__ block at the top using the provided key.
     */
    public static void writeEntriesWithVerification(List<Entry> entries, SecretKeySpec key) throws Exception {
    	byte[] salt = EncryptionUtils.readVaultSalt(); // must exist for new-format vaults
        if (salt == null || salt.length == 0) {
            throw new IllegalStateException("Vault is missing __SALT__.");
        }
        String recoveryHash = null;

        // Step 1: If current file has a recovery block, capture its hash so we can preserve it.
        try (Scanner scanner = new Scanner(passwordFile)) {
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();
                if (line.equals("__RECOVERY__") && scanner.hasNextLine()) {
                    recoveryHash = scanner.nextLine();
                    break;
                }
            }
        }

        // Step 2: Rewrite entire vault (fresh verification block + entries + optional recovery block).
        try (PrintWriter writer = new PrintWriter(new FileWriter(passwordFile))) {
        	// __SALT__
            writer.println("__SALT__");
            writer.println(Base64.getEncoder().encodeToString(salt));
            writer.println("------------------------");

            // __VERIFICATION__ (fresh, using provided key)
            writer.println("__VERIFICATION__");
            writer.println(EncryptionUtils.encrypt("check123", key));
            writer.println("------------------------");

            // __RECOVERY__ (preserve prior hash if present)
            if (recoveryHash != null) {
                writer.println("__RECOVERY__");
                writer.println(recoveryHash);
                writer.println("------------------------");
            }

            for (Entry entry : entries) {
                writer.println(EncryptionUtils.encrypt(entry.website, key));
                writer.println(entry.encryptedPassword);
                writer.println("------------------------");
            }

        }
    }

    /**
     * Export the current encrypted vault as-is to a user-chosen destination.
     * No decryption—this is a straight byte copy (encrypted stays encrypted).
     */
    public static void exportEncryptedBackup(JFrame frame, String masterPassword) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Export Encrypted Backup");

        if (fileChooser.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
            File dest = fileChooser.getSelectedFile();

            try (InputStream in = new FileInputStream(passwordFile);
                 OutputStream out = new FileOutputStream(dest)) {
                in.transferTo(out); // copy all bytes
                PasswordManagerUI.info("Backup exported successfully.");
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(frame, "Failed to export backup.");
            }
        }
    }

    /**
     * Import an encrypted vault file and merge new websites into the current vault.
     * - Skips duplicates by website name.
     * - Preserves existing entries and metadata blocks.
     * - Only appends entries that don’t already exist.
     * - Assumes the imported vault and the current vault use the same ecnryption key
     */
    public static void importEncryptedBackupSafely(JFrame frame, String masterPassword, JComboBox<String> dropDown) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Choose a backup file to import");
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home") + "/Documents"));
        if (fileChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File importFile = fileChooser.getSelectedFile();

        try {
            // ---- A) Build duplicate guard from CURRENT (destination) vault ----
            Set<String> existingWebsites = new HashSet<>();
            SecretKeySpec destKey = MasterKeyManager.key;
            try (Scanner mainScanner = new Scanner(passwordFile, StandardCharsets.UTF_8)) {
                while (mainScanner.hasNextLine()) {
                    String line = mainScanner.nextLine();

                    if ("__SALT__".equals(line) || "__VERIFICATION__".equals(line) || "__RECOVERY__".equals(line)) {
                        if (mainScanner.hasNextLine()) mainScanner.nextLine(); // content
                        if (mainScanner.hasNextLine()) mainScanner.nextLine(); // separator
                        continue;
                    }

                    String websitePlain = EncryptionUtils.decrypt(line, destKey); // decrypt website
                    existingWebsites.add(websitePlain);
                    if (mainScanner.hasNextLine()) mainScanner.nextLine(); // skip password
                    if (mainScanner.hasNextLine()) mainScanner.nextLine(); // skip separator
                }
            }

            // ---- B) Read import-vault salt and verify the master password against its VERIFICATION block ----
            byte[] importSalt = null;
            String encryptedCheck = null;

            try (Scanner sc = new Scanner(importFile, StandardCharsets.UTF_8)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();

                    if (line.isEmpty() || "------------------------".equals(line)) continue;

                    if ("__SALT__".equals(line)) {
                        if (sc.hasNextLine()) {
                            String saltB64 = sc.nextLine().trim();
                            importSalt = Base64.getDecoder().decode(saltB64);
                        }
                        if (sc.hasNextLine()) sc.nextLine(); // separator
                        continue;
                    }
                    if ("__VERIFICATION__".equals(line)) {
                        if (sc.hasNextLine()) encryptedCheck = sc.nextLine().trim();
                        // optional separator skip:
                        if (sc.hasNextLine()) sc.nextLine();
                        break; // we have enough to verify
                    }
                }
            }

            if (importSalt == null || importSalt.length == 0 || encryptedCheck == null) {
                JOptionPane.showMessageDialog(frame,
                        "Import failed: backup is missing SALT/VERIFICATION.",
                        "Import", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Derive the IMPORT key using the import vault's salt
            SecretKeySpec importKey = EncryptionUtils.getSecretKey(masterPassword, importSalt);
            String decryptedCheck;
            try {
                decryptedCheck = EncryptionUtils.decrypt(encryptedCheck, importKey);
            } catch (Exception badKey) {
                JOptionPane.showMessageDialog(frame,
                        "Import failed: master key does not unlock the selected backup.",
                        "Import", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!"check123".equals(decryptedCheck)) {
                JOptionPane.showMessageDialog(frame,
                        "Import failed: verification token mismatch.",
                        "Import", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // destKey already obtained above in section A
            if (destKey == null) {
                JOptionPane.showMessageDialog(frame, "Destination vault not ready.", "Import", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ---- C) Stream the import file again: decrypt with importKey, re-encrypt with destKey, append if non-duplicate ----
            int added = 0, skipped = 0, failed = 0;
            List<String> skippedSites = new ArrayList<>();
            List<String> failedSites = new ArrayList<>();

            try (Scanner sc = new Scanner(importFile, StandardCharsets.UTF_8);
                 PrintWriter writer = new PrintWriter(new FileWriter(passwordFile, true))) { // append mode
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();

                    // Skip headers in import file
                    if ("__SALT__".equals(line) || "__VERIFICATION__".equals(line) || "__RECOVERY__".equals(line)) {
                        if (sc.hasNextLine()) sc.nextLine(); // content
                        if (sc.hasNextLine()) sc.nextLine(); // separator
                        continue;
                    }
                    if ("------------------------".equals(line) || line.isBlank()) continue;

                    String encryptedWebsite = line;
                    if (!sc.hasNextLine()) break;
                    String importedCipher = sc.nextLine().trim();
                    if (sc.hasNextLine()) sc.nextLine(); // separator (best-effort)

                    try {
                        // decrypt website using IMPORT key
                        String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, importKey);

                        if (existingWebsites.contains(websitePlain)) {
                            skipped++;
                            skippedSites.add(websitePlain);
                            continue;
                        }

                        // decrypt password using IMPORT key + salt of the import vault
                        String plainPassword = EncryptionUtils.decrypt(importedCipher, importKey);
                        // re-encrypt password with DESTINATION vault key (its own salt already factored into destKey)
                        String recipher = EncryptionUtils.encrypt(plainPassword, destKey);
                        // re-encrypt website with DESTINATION vault key
                        String reencryptedWebsite = EncryptionUtils.encrypt(websitePlain, destKey);

                        writer.println(reencryptedWebsite);
                        writer.println(recipher);
                        writer.println("------------------------");

                        added++;
                    } catch (Exception perEntry) {
                        failed++;
                        failedSites.add(encryptedWebsite); // use encrypted form for error reporting
                    }
                }
            }

            // ---- D) Report results ----
            StringBuilder msg = new StringBuilder("Import complete.\n");
            msg.append("New entries added: ").append(added).append("\n");
            msg.append("Duplicates skipped: ").append(skipped).append("\n");
            if (failed > 0) msg.append("Failed to convert: ").append(failed).append("\n");

            if (!skippedSites.isEmpty()) {
                msg.append("\nSkipped (duplicates):\n");
                for (String s : skippedSites) msg.append("• ").append(s).append("\n");
            }
            if (!failedSites.isEmpty()) {
                msg.append("\nFailed to import (decrypt/re-encrypt errors):\n");
                for (String s : failedSites) msg.append("• ").append(s).append("\n");
            }
            if(added > 0) {
            	Utils.populateDropdown(dropDown);
            }

            JOptionPane.showMessageDialog(frame, msg.toString(), "Import", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error importing encrypted backup.", "Import", JOptionPane.ERROR_MESSAGE);
        }
    }


    /**
     * Import an old encrypted backup by prompting for the original master password,
     * verifying it using the __VERIFICATION__ block, then showing the decrypted pairs
     * (website → plaintext password) in a scrollable dialog. No writes to the vault.
     */
    public static void importAndDecryptOldBackup(JFrame frame) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Select encrypted backup file");
        fileChooser.setCurrentDirectory(new File(System.getProperty("user.home") + "/Documents"));
        if (fileChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File importFile = fileChooser.getSelectedFile();

        String providedPassword = JOptionPane.showInputDialog(frame, "Enter the master password for the selected backup:");
        if (providedPassword == null || providedPassword.trim().isEmpty()) {
            JOptionPane.showMessageDialog(frame, "No password entered. Aborting.");
            return;
        }

        try {
            // ---- Pass 1: read __SALT__ and __VERIFICATION__ from the import file ----
            byte[] importSalt = null;
            String encryptedCheck = null;

            try (Scanner sc = new Scanner(importFile, StandardCharsets.UTF_8)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine().trim();
                    if (line.isEmpty() || "------------------------".equals(line)) continue;

                    if ("__SALT__".equals(line)) {
                        if (sc.hasNextLine()) {
                            String saltB64 = sc.nextLine().trim();
                            importSalt = Base64.getDecoder().decode(saltB64);
                        }
                        if (sc.hasNextLine()) sc.nextLine(); // separator
                        continue;
                    }
                    if ("__VERIFICATION__".equals(line)) {
                        if (sc.hasNextLine()) encryptedCheck = sc.nextLine().trim();
                        if (sc.hasNextLine()) sc.nextLine(); // separator
                        break; // we have enough to verify
                    }
                }
            }

            // Derive the import key: prefer per-vault salt; if absent, fall back to legacy static-salt method
            SecretKeySpec importKey = (importSalt != null && importSalt.length > 0)
                    ? EncryptionUtils.getSecretKey(providedPassword, importSalt)
                    : EncryptionUtils.getSecretKey(providedPassword); // legacy backups (no __SALT__)

            // Verify the check token
            if (encryptedCheck == null) {
                JOptionPane.showMessageDialog(frame, "Backup is missing VERIFICATION block.", "Decrypt", JOptionPane.ERROR_MESSAGE);
                return;
            }
            String decryptedCheck;
            try {
                decryptedCheck = EncryptionUtils.decrypt(encryptedCheck, importKey);
            } catch (Exception badKey) {
                JOptionPane.showMessageDialog(frame, "Incorrect password for this backup (verification failed).", "Decrypt", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (!"check123".equals(decryptedCheck)) {
                JOptionPane.showMessageDialog(frame, "Verification token mismatch. Wrong password or corrupt file.", "Decrypt", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // ---- Pass 2: stream entries, decrypt with importKey, collect for display ----
            List<Entry> decryptedEntries = new ArrayList<>();

            try (Scanner sc = new Scanner(importFile, StandardCharsets.UTF_8)) {
                while (sc.hasNextLine()) {
                    String line = sc.nextLine();

                    // Skip header blocks and their content lines
                    if ("__SALT__".equals(line) || "__VERIFICATION__".equals(line) || "__RECOVERY__".equals(line)) {
                        if (sc.hasNextLine()) sc.nextLine(); // content line
                        if (sc.hasNextLine()) sc.nextLine(); // separator
                        continue;
                    }
                    if ("------------------------".equals(line) || line.isBlank()) continue;

                    String encryptedWebsite = line;
                    if (!sc.hasNextLine()) break;
                    String importedCipher = sc.nextLine().trim();
                    if (sc.hasNextLine()) sc.nextLine(); // separator (best-effort)

                    String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, importKey);
                    String plainPassword = EncryptionUtils.decrypt(importedCipher, importKey);
                    // Reuse Entry for convenience; here encryptedPassword field holds plaintext for display
                    decryptedEntries.add(new Entry(websitePlain, plainPassword));
                }
            }

            // ---- Show results ----
            StringBuilder displayText = new StringBuilder();
            for (Entry entry : decryptedEntries) {
                displayText.append("Website: ").append(entry.website).append("\n");
                displayText.append("Password: ").append(entry.encryptedPassword).append("\n");
                displayText.append("------------------------\n");
            }

            JTextArea textArea = new JTextArea(displayText.toString());
            textArea.setEditable(false);
            JScrollPane scrollPane = new JScrollPane(textArea);
            scrollPane.setPreferredSize(new Dimension(560, 360));

            JOptionPane.showMessageDialog(frame, scrollPane, "Decrypted Passwords", JOptionPane.INFORMATION_MESSAGE);

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Failed to decrypt the file. Possibly wrong password or corrupt file.", "Decrypt", JOptionPane.ERROR_MESSAGE);
        }
    }


    /**
     * Export a fully decrypted, plaintext copy of the vault to a user-chosen file.
     * WARNING: this writes plaintext passwords to disk—advise users accordingly.
     */
    public static void exportDecryptedBackup(JFrame frame) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Choose location to save decrypted backup");
        fileChooser.setSelectedFile(new File("decrypted_backup.txt"));

        if (fileChooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File exportFile = fileChooser.getSelectedFile();

        try (Scanner scanner = new Scanner(passwordFile);
             PrintWriter writer = new PrintWriter(new FileWriter(exportFile))
        ) {
            SecretKeySpec key = MasterKeyManager.key;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                if (line.equals("__VERIFICATION__") || line.equals("__RECOVERY__") || line.equals("__SALT__")) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // skip encrypted hash / recovery hash
                    if (scanner.hasNextLine()) scanner.nextLine(); // skip separator
                    continue;
                }

                String encryptedWebsite = line;
                if (!scanner.hasNextLine()) break;
                String encryptedPassword = scanner.nextLine();
                if (!scanner.hasNextLine()) break;
                scanner.nextLine(); // skip separator

                String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, key);
                String decryptedPassword = EncryptionUtils.decrypt(encryptedPassword, key);
                writer.println(websitePlain);
                writer.println(decryptedPassword);
                writer.println("------------------------");
            }

            PasswordManagerUI.info("Decrypted backup exported successfully to: \n" + exportFile.getAbsolutePath());

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error exporting decrypted backup.");
        }
    }

    /**
     * Import a decrypted (PLAINTEXT) backup, line-based in the same 3-line format.
     * - Encrypts incoming plaintext with current MasterKeyManager.key.
     * - Skips duplicates by website name.
     */
    public static void importDecryptedBackup(JFrame frame, JComboBox<String> dropDown) {
        JFileChooser fileChooser = new JFileChooser();
        fileChooser.setDialogTitle("Choose a decrypted backup file to import");

        if (fileChooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File importFile = fileChooser.getSelectedFile();
        Set<String> existingWebsites = new HashSet<>();

        try {
            // Step 1: Load existing website names from current vault (duplicate guard).
            SecretKeySpec key = MasterKeyManager.key;
            try (Scanner scanner = new Scanner(passwordFile)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();

                    if (line.equals("__VERIFICATION__") || line.equals("__RECOVERY__") || line.equals("__SALT__")) {
                        if (scanner.hasNextLine()) scanner.nextLine(); // skip content line
                        if (scanner.hasNextLine()) scanner.nextLine(); // skip separator
                        continue;
                    }

                    String websitePlain = EncryptionUtils.decrypt(line, key);
                    existingWebsites.add(websitePlain);

                    if (scanner.hasNextLine()) scanner.nextLine(); // skip password
                    if (scanner.hasNextLine()) scanner.nextLine(); // skip separator
                }
            }

            int added = 0, skipped = 0;
            List<String> skippedSites = new ArrayList<>();

            // Step 2: Append non-duplicates from the plaintext import file.
            try (Scanner scanner = new Scanner(importFile);
                 PrintWriter writer = new PrintWriter(new FileWriter(passwordFile, true)) // append mode
            ) {
                while (scanner.hasNextLine()) {
                    String website = scanner.nextLine().trim();
                    if (!scanner.hasNextLine()) break;
                    String plainPassword = scanner.nextLine().trim();
                    if (scanner.hasNextLine()) scanner.nextLine(); // skip separator

                    if (existingWebsites.contains(website)) {
                        skipped++;
                        skippedSites.add(website);
                        continue;
                    }

                    String encryptedWebsite = EncryptionUtils.encrypt(website, key);
                    String encryptedPassword = EncryptionUtils.encrypt(plainPassword, key);
                    writer.println(encryptedWebsite);
                    writer.println(encryptedPassword);
                    writer.println("------------------------");
                    added++;
                }
            }

            // Step 3: Show result message.
            StringBuilder msg = new StringBuilder(String.format(
                    "Import complete.\nNew entries added: %d\nDuplicates skipped: %d", added, skipped));
            if (!skippedSites.isEmpty()) {
                msg.append("\n\nSkipped:\n");
                for (String site : skippedSites) {
                    msg.append("- ").append(site).append("\n");
                }
            }
            if(added > 0) {
            	Utils.populateDropdown(dropDown);
            }

            JOptionPane.showMessageDialog(frame, msg.toString());

        } catch (Exception e) {
            e.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Error importing decrypted backup.");
        }
    }

    /**
     * Export to Bitwarden-like CSV (PLAINTEXT). Header: type,name,login_password.
     * - Uses UTF-8 consistently for both reading the vault and writing the CSV.
     * - Warns the user that CSV is plaintext before proceeding.
     */
    public static void exportToCsvMinimal(JFrame frame) {
        // Warn: plaintext export
        int confirm = JOptionPane.showConfirmDialog(
                frame,
                "CSV exports are PLAINTEXT.\nAnyone who opens the file can read your passwords.\n\nContinue?",
                "Warning: Plaintext Export",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE
        );
        if (confirm != JOptionPane.OK_OPTION) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export CSV (plaintext)");
        chooser.setSelectedFile(new File("vault_export.csv"));
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File dest = chooser.getSelectedFile();

        try (
            // Read the vault using UTF-8 to avoid platform default charset issues.
            Scanner scanner = new Scanner(passwordFile, StandardCharsets.UTF_8);
            // Write CSV in UTF-8 explicitly (CSVWriter wraps the OutputStreamWriter).
            CSVWriter writer = new CSVWriter(
                    new OutputStreamWriter(new FileOutputStream(dest), StandardCharsets.UTF_8))
        ) {
            // Header
            writer.writeNext(new String[] { "type", "name", "login_password" }, false);

            SecretKeySpec key = MasterKeyManager.key;

            while (scanner.hasNextLine()) {
                String line = scanner.nextLine();

                // Skip metadata blocks (__VERIFICATION__/__RECOVERY__/__SALT__) including their content + separator.
                if (line.equals("__VERIFICATION__") || line.equals("__RECOVERY__") || line.equals("__SALT__")) {
                    if (scanner.hasNextLine()) scanner.nextLine(); // encrypted check / recovery hash
                    if (scanner.hasNextLine()) scanner.nextLine(); // separator
                    continue;
                }

                String encryptedWebsite = line;
                if (!scanner.hasNextLine()) break;
                String encryptedPassword = scanner.nextLine();
                if (!scanner.hasNextLine()) break;
                scanner.nextLine(); // separator

                String websitePlain = EncryptionUtils.decrypt(encryptedWebsite, key);
                String passwordPlain = EncryptionUtils.decrypt(encryptedPassword, key);

                // Row: login,<website>,<password>
                writer.writeNext(new String[] { "login", websitePlain, passwordPlain }, false);
            }

            writer.flush();
            PasswordManagerUI.info("CSV backup exported successfully (PLAINTEXT) to \n: " + dest.getAbsolutePath());

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Failed to export CSV.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Import from CSV with header "type,name,login_password" (Bitwarden-like).
     * - Accepts only rows where type=="login".
     * - Encrypts the password with current key and appends non-duplicate websites.
     * - Handles optional UTF-8 BOM on the first header cell safely.
     */
    public static void importFromCsvMinimal(JFrame frame, JComboBox<String> dropDown) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Import CSV (type,name,login_password)");
        if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;

        File src = chooser.getSelectedFile();

        // Build set of existing website names (duplicate guard)
        Set<String> existing = new HashSet<>();
        SecretKeySpec key = MasterKeyManager.key;
        try (Scanner s = new Scanner(passwordFile, StandardCharsets.UTF_8)) {
            while (s.hasNextLine()) {
                String line = s.nextLine();

                if (line.equals("__VERIFICATION__") || line.equals("__RECOVERY__") || line.equals("__SALT__")) {
                    if (s.hasNextLine()) s.nextLine(); // skip content
                    if (s.hasNextLine()) s.nextLine(); // skip separator
                    continue;
                }

                String websitePlain = EncryptionUtils.decrypt(line, key);
                existing.add(websitePlain);

                if (s.hasNextLine()) s.nextLine(); // password
                if (s.hasNextLine()) s.nextLine(); // separator
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(frame, "Could not read current vault file.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        int added = 0, skippedDup = 0, skippedInvalid = 0;

        try (
            CSVReader reader = new CSVReader(new InputStreamReader(new FileInputStream(src), StandardCharsets.UTF_8));
            PrintWriter out = new PrintWriter(new FileWriter(passwordFile, true)) // append to vault
        ) {
            String[] row = reader.readNext(); // header
            if (row == null) {
                JOptionPane.showMessageDialog(frame, "CSV is empty.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            // Handle potential UTF-8 BOM on first header cell:
            // Some tools save CSV with BOM; removing it ensures header comparison works.
            if (row.length > 0 && row[0] != null && !row[0].isEmpty() && row[0].charAt(0) == '\uFEFF') {
                row[0] = row[0].substring(1);
            }

            String expectedHeader = "type,name,login_password";
            String actualHeader = String.join(",", row);
            if (!expectedHeader.equals(actualHeader)) {
                int cont = JOptionPane.showConfirmDialog(
                    frame,
                    "Unexpected header:\n" + actualHeader + "\n\nExpected: " + expectedHeader + "\nProceed anyway?",
                    "Header Mismatch",
                    JOptionPane.OK_CANCEL_OPTION,
                    JOptionPane.WARNING_MESSAGE
                );
                if (cont != JOptionPane.OK_OPTION) return;
            }

            // Process rows
            while ((row = reader.readNext()) != null) {
                if (row.length < 3) { skippedInvalid++; continue; }

                String type = row[0] == null ? "" : row[0].trim();
                String name = row[1] == null ? "" : row[1];
                String plainPassword = row[2] == null ? "" : row[2];

                if (!"login".equalsIgnoreCase(type)) { skippedInvalid++; continue; }
                if (name.trim().isEmpty())           { skippedInvalid++; continue; }

                // Duplicate policy: website must be unique
                if (existing.contains(name)) { skippedDup++; continue; }

                String encryptedWebsite = EncryptionUtils.encrypt(name, key);
                String encryptedPassword = EncryptionUtils.encrypt(plainPassword, key);

                out.println(encryptedWebsite);
                out.println(encryptedPassword);
                out.println("------------------------");

                existing.add(name);
                added++;
            }

            out.flush();
            String msg = "CSV import complete.\n"
                       + "New entries added: " + added + "\n"
                       + "Duplicates skipped: " + skippedDup + "\n"
                       + "Invalid rows skipped: " + skippedInvalid;
            JOptionPane.showMessageDialog(frame, msg, "Import Complete", JOptionPane.INFORMATION_MESSAGE);
            if(added > 0) {
            	Utils.populateDropdown(dropDown);
            }

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(frame, "Failed to import CSV.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }
}