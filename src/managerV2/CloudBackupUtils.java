package managerV2;

import javax.swing.*;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ActionListener;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * CloudBackupUtils
 * ----------------
 * UI-friendly utilities for S3 backups/restores:
 *  - Opens file choosers, runs S3 work off the EDT via SwingWorker
 *  - Delegates actual AWS I/O to CloudBackupLite (thin S3 client wrapper)
 *  - Maintains per-vault sidecar metadata (.lastbackup, .backup.conf)
 *
 * Threading: UI code runs on EDT; network/file I/O runs in background workers.
 * Security: Assumes AWS credentials are configured (env/CLI/profile). No secrets stored here.
 */

public final class CloudBackupUtils {

    /** ---- Configure once here ---- */
    private static final String S3_REGION = "us-east-1";
    private static final String S3_BUCKET = "Bucket Name";
    private static final String S3_PREFIX = "backups"; /**or "" for bucket root*/
    private static final boolean TIMESTAMP_FIRST = false; /** true: ts__name.ext, false: name__ts.ext*/
    private static final int RETAIN_LATEST_N = 10; /** keep the most recent 10 backups (tweak as needed)*/
    /** ------------------------------ */

    private CloudBackupUtils() {} /**Utility class: prevent instantiation. All members are static.*/
    

    public static void log(String msg) {
        String ts = ZonedDateTime.now().toString(); /** ISO-8601 timestamp for easy parsing/sorting in logs */
        String th = Thread.currentThread().getName(); /** Thread name helps distinguish EDT vs background workers */
        System.out.println("[BackupDebug " + ts + " " + th + "] " + msg);
    }

    /** CloudBackupLite createService
     *  -----------------------------
     * Build a short-lived CloudBackupLite client bound to the configured region/bucket/prefix.
     * Credentials come from your local AWS setup (env vars, profile, or default provider chain).
     * The returned client is AutoCloseable, so we use try-with-resources to free HTTP resources.
     */
    private static CloudBackupLite createService() {
        return CloudBackupLite.builder().region(S3_REGION).bucket(S3_BUCKET).prefix(S3_PREFIX).build();
    }
    

    /** Opens a chooser, uploads the selected encrypted file, shows a result dialog. */
    public static void uploadBackupWithChooser(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Select Encrypted Backup to Upload");
        if (chooser.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        Path file = chooser.getSelectedFile().toPath();
        if (!Files.isRegularFile(file)) {
            JOptionPane.showMessageDialog(parent, "Not a file:\n" + file.toAbsolutePath(),
                    "Upload Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                try (var svc = createService()) { /** Upload preserves the original filename; timestamp position controlled by TIMESTAMP_FIRST */
                    /** Uses the filename-preserving upload we added earlier */
                    return svc.uploadEncrypted(file, TIMESTAMP_FIRST);
                }
            }
            @Override protected void done() {
                try {
                    String key = get(); /** SwingWorker#get blocks until doInBackground() completes and returns its result (or throws) */
                    JOptionPane.showMessageDialog(parent, "Uploaded to:\ns3://" + S3_BUCKET + "/" + key, "Upload Successful", JOptionPane.INFORMATION_MESSAGE);
                    
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent, "Upload failed:\n" + ex.getMessage(), "Upload Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }

    /** Downloads the newest backup under the prefix and lets the user choose where to save it. */
    public static void retrieveLatestBackupWithChooser(Component parent) {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Save Retrieved Encrypted Backup As");
        chooser.setSelectedFile(new File("Encrypted_Backup_restored.pm.enc"));
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        File outFile = chooser.getSelectedFile();
 
        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                try (var svc = createService()) { /** 'var' lets Java infer the local variable type automatically. */
                	                              /**  Example: 'var svc = createService();' is equivalent to 'CloudBackupLite svc = createService(); */
                	
                    var dl = svc.downloadLatest(); /** downloadLatest() returns both the S3 key and the file bytes as a byte[] */
                    Files.write(outFile.toPath(), dl.data); /** Write all bytes to the chosen output file atomically (overwrites any existing file) */
                    return dl.key;
                }
            }
            @Override protected void done() {
                try {
                    String key = get();
                    JOptionPane.showMessageDialog(parent,
                            "Downloaded latest backup:\nKey: " + key +
                            "\nSaved to: " + outFile.getAbsolutePath(),
                            "Retrieve Successful", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent,
                            "Retrieve failed:\n" + ex.getMessage(),
                            "Retrieve Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    
 // In managerV2.CloudBackupUtils
    public static void uploadActiveVaultToS3(Component parent) {
        Path file = activeVaultPath();

        if (file == null) {
            JOptionPane.showMessageDialog(parent,
                    "No active vault file is set.",
                    "Upload Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        if (!Files.isRegularFile(file)) {
            JOptionPane.showMessageDialog(parent,
                    "Active vault not found:\n" + file.toAbsolutePath(),
                    "Upload Error", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new SwingWorker<String, Void>() {
            @Override protected String doInBackground() throws Exception {
                try (var svc = createService()) {
                    /** Reuse your filename-preserving upload (timestampFirst true/false = your preference) */
                    return svc.uploadEncrypted(file, TIMESTAMP_FIRST);
                }
            }
            @Override protected void done() {
                try {
                    String key = get();
                    JOptionPane.showMessageDialog(parent,
                            "Uploaded current vault to:\ns3://" + S3_BUCKET + "/" + key,
                            "Upload Successful", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent,
                            "Upload failed:\n" + ex.getMessage(),
                            "Upload Error", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    /** Creates a list of vaults uploaded to S3 and allows the user to select one and load it into the current vault file*/
    public static void retrieveBackupFromListWithChooser(Component parent) {

        /** Step 1: Fetch list (off the EDT) */
        new SwingWorker<List<CloudBackupLite.BackupObject>, Void>() {
            @Override protected List<CloudBackupLite.BackupObject> doInBackground() throws Exception {
                try (var svc = createService()) {
                    return svc.listBackups();
                }
            }
            @Override protected void done() {
                List<CloudBackupLite.BackupObject> items;
                try { items = get(); } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent,
                            "Could not list backups:\n" + ex.getMessage(),
                            "Error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                if (items == null || items.isEmpty()) {
                    JOptionPane.showMessageDialog(parent,
                            "No backups found under prefix \"" + S3_PREFIX + "\".",
                            "Nothing to Retrieve", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                /** Step 2: Let user pick one (JList inside a dialog) */
                DefaultListModel<CloudBackupLite.BackupObject> model = new DefaultListModel<>();
                for (var o : items) model.addElement(o);

                JList<CloudBackupLite.BackupObject> list = new JList<>(model);
                list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                list.setVisibleRowCount(Math.min(10, model.size()));
                list.setCellRenderer((comp, value, index, isSelected, hasFocus) -> { /** Custom cell renderer: convert each BackupObject into a human-readable label.*/
                    String label = formatItem(value);
                    JLabel lbl = new JLabel(label); /** The lambda returns a JLabel component for each row; Swing paints it in the list.*/
                    if (isSelected) {
                        lbl.setOpaque(true);
                        lbl.setBackground(list.getSelectionBackground());
                        lbl.setForeground(list.getSelectionForeground());
                    }
                    return lbl;
                });

                int res = JOptionPane.showConfirmDialog(
                        parent,
                        new JScrollPane(list),
                        "Choose a backup to retrieve",
                        JOptionPane.OK_CANCEL_OPTION,
                        JOptionPane.PLAIN_MESSAGE
                );
                if (res != JOptionPane.OK_OPTION || list.getSelectedValue() == null) return;

                var selected = list.getSelectedValue();

                /** Step 3: Ask where to save it */
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Save Retrieved Encrypted Backup As");
                chooser.setSelectedFile(new File(baseName(selected.key) + ".pm.enc"));
                if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
                File outFile = chooser.getSelectedFile();

                /** Step 4: Download selected (off the EDT) */
                new SwingWorker<String, Void>() {
                    @Override protected String doInBackground() throws Exception {
                        try (var svc = createService()) {
                            var dl = svc.downloadByKey(selected.key);
                            Files.write(outFile.toPath(), dl.data);
                            return dl.key;
                        }
                    }
                    @Override protected void done() {
                        try {
                            String key = get();
                            JOptionPane.showMessageDialog(parent,
                                    "Downloaded:\nKey: " + key +
                                    "\nSaved to: " + outFile.getAbsolutePath(),
                                    "Retrieve Successful",
                                    JOptionPane.INFORMATION_MESSAGE);
                        } catch (Exception ex) {
                            JOptionPane.showMessageDialog(parent,
                                    "Retrieve failed:\n" + ex.getMessage(),
                                    "Error", JOptionPane.ERROR_MESSAGE);
                        }
                    }
                }.execute();
            }
        }.execute();
    }

    /** ---- private helpers (inside CloudBackupUtils) ---- */

    private static Path activeVaultPath() { /** Fetch current vault file selected in the app (FileUtils owns this lookup) */
        File f = FileUtils.getPasswordFile();
        return (f != null && f.isFile()) ? f.toPath() : null;
    }
        
    private static String formatItem(CloudBackupLite.BackupObject o) { /** Build a compact line like: "2025-11-05 14:33:10 • 2.4 MB • myVault__2025-11-05.enc" */
        return formatInstant(o.lastModified) + "   •   " + humanSize(o.size) + "   •   " + baseName(o.key);
    }
    
    private static String baseName(String key) { /** Return the last path segment of an S3 key (strip the prefix folder). */
        int i = key.lastIndexOf('/');
        return (i >= 0) ? key.substring(i + 1) : key;
    }
    
    private static String humanSize(long bytes) { /** Human-friendly byte size using powers of 1024 (KB, MB, GB...). */
        if (bytes < 1024) return bytes + " B";
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String unit = "KMGTPE".charAt(exp - 1) + "B";
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024, exp), unit);
    }
    
    private static String formatInstant(Instant t) { /** Format Instant using the system timezone: yyyy-MM-dd HH:mm:ss. */
        var z = ZonedDateTime.ofInstant(t, ZoneId.systemDefault());
        return z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
    
 /** Where to store the sidecar metadata for a given vault file */
    private static Path sidecarPathFor(Path vaultPath) { /** Returns the path for the lastbackup sidecar metadate file */
        return vaultPath.resolveSibling(vaultPath.getFileName().toString() + ".lastbackup");
    }

    private static Path backupConfigPathFor(Path vaultPath) { /** Returns the path for the backup configuration sidecar metadate file */
        return vaultPath.resolveSibling(vaultPath.getFileName().toString() + ".backup.conf");
    }
    
 /** Compute SHA-256 of the file contents (hex lower-case) */
    private static String sha256Hex(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) {
                md.update(buf, 0, r);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }
    
 /** Container for the last backup metadata */
    private static final class LastBackupMeta {
        String lastUploadedHash;   // hex
        Instant lastBackupAtUtc; // may be null

        static LastBackupMeta read(Path sidecar) {
            LastBackupMeta m = new LastBackupMeta();
            if (!Files.isRegularFile(sidecar)) return m;
            Properties p = new Properties();
            try (var in = Files.newInputStream(sidecar)) {
                p.load(in);
                m.lastUploadedHash = p.getProperty("last_uploaded_hash");
                String ts = p.getProperty("last_backup_at_utc");
                if (ts != null && !ts.isBlank()) {
                    m.lastBackupAtUtc = Instant.parse(ts);
                }
            } catch (Exception ignored) {}
            return m;
        }

        void write(Path sidecar) throws Exception {
            Properties p = new Properties();
            if (lastUploadedHash != null) p.setProperty("last_uploaded_hash", lastUploadedHash);
            if (lastBackupAtUtc != null) p.setProperty("last_backup_at_utc", lastBackupAtUtc.toString());
            try (var out = Files.newOutputStream(sidecar,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                p.store(out, "Cloud backup metadata");
            }
        }
    }
    
    /**
     * Returns a small snapshot describing whether the vault changed relative to the last uploaded hash.
     */
    public static final class ChangeCheck {
        public final Path vaultPath;
        public final String currentHashHex;
        public final String lastUploadedHashHex; // may be null
        public final boolean changedSinceLastUpload;

        ChangeCheck(Path vaultPath, String currentHashHex, String lastUploadedHashHex) {
            this.vaultPath = vaultPath;
            this.currentHashHex = currentHashHex;
            this.lastUploadedHashHex = lastUploadedHashHex;
            this.changedSinceLastUpload = (currentHashHex != null) &&
                    (lastUploadedHashHex == null || !lastUploadedHashHex.equals(currentHashHex));
        }
    }

    /**
     * Compute current SHA-256 of the active vault and compare to sidecar.
     * If no sidecar exists yet, we consider it "changed" (i.e., needs an initial backup).
     */
    public static ChangeCheck checkForLocalChange() {
        Path file = activeVaultPath();
        if (file == null || !Files.isRegularFile(file)) {
            return new ChangeCheck(null, null, null);
        }
        try {
            String current = sha256Hex(file);
            LastBackupMeta meta = LastBackupMeta.read(sidecarPathFor(file));
            return new ChangeCheck(file, current, meta.lastUploadedHash);
        } catch (Exception e) {
            /** If hashing fails, treat as "no change info"; caller can decide what to do. */
            return new ChangeCheck(file, null, null);
        }
    }

    /**
     * After a successful backup upload, call this to record the new hash + timestamp.
     */
    public static void recordSuccessfulBackup(Path vaultPath, String uploadedHashHex) {
        if (vaultPath == null || uploadedHashHex == null) return;
        LastBackupMeta meta = new LastBackupMeta();
        meta.lastUploadedHash = uploadedHashHex;
        meta.lastBackupAtUtc = Instant.now(); // UTC
        try {
            meta.write(sidecarPathFor(vaultPath));
        } catch (Exception ignored) {}
    }
    
    /** Prune old backups for the active vault name, keeping only RETAIN_LATEST_N. */
    public static void pruneOldBackupsForActiveVault(Component parent) {
        Path file = activeVaultPath();
        if (file == null) {
            JOptionPane.showMessageDialog(parent,
                    "No active vault file is set.",
                    "Prune Backups", JOptionPane.ERROR_MESSAGE);
            return;
        }

        new SwingWorker<Integer, Void>() {
            @Override protected Integer doInBackground() throws Exception {
                try (var svc = createService()) {
                    String originalName = file.getFileName().toString();
                    return svc.pruneOldBackups(originalName, TIMESTAMP_FIRST, RETAIN_LATEST_N);
                }
            }
            @Override protected void done() {
                try {
                    int deleted = get();
                    JOptionPane.showMessageDialog(parent,
                            "Pruning complete.\nDeleted " + deleted + " old backup(s).",
                            "Prune Backups", JOptionPane.INFORMATION_MESSAGE);
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(parent,
                            "Prune failed:\n" + ex.getMessage(),
                            "Prune Backups", JOptionPane.ERROR_MESSAGE);
                }
            }
        }.execute();
    }
    
    /** 
     * Step 3A: Perform one backup NOW if the active vault has changed since the last upload.
     * - No dialogs, no scheduling logic here.
     * - Synchronous (caller should run off-EDT if calling from UI).
     * - On success: updates the sidecar and prunes old backups.
     *
     * @return true if an upload was performed; false if no change or on any failure.
     */
    public static boolean performBackupIfChangedNow() {


        try {
            Path file = activeVaultPath();
            if (file == null || !Files.isRegularFile(file)) return false;

            ChangeCheck cc = checkForLocalChange();
            if (cc == null || cc.currentHashHex == null || !cc.changedSinceLastUpload) return false;

            try (var svc = createService()) {
                /** Upload using your existing naming scheme */
                svc.uploadEncrypted(file, TIMESTAMP_FIRST);

                /** Record success locally (hash + timestamp) */
                recordSuccessfulBackup(file, cc.currentHashHex);

                PasswordManagerUI.info("Automatic backup exported to S3"); /** // Non-blocking UX: surfaces success without a dialog panel*/

                /** Best-effort pruning; ignore failures */
                try {
                    String originalName = file.getFileName().toString();
                    svc.pruneOldBackups(originalName, TIMESTAMP_FIRST, RETAIN_LATEST_N);

                } catch (Exception ignored) {}

                return true;
            }
        } catch (Exception ex) {
            /** Silent by design (no UI). Caller can log if desired. */
            return false;
        }
    }
    
    /**read the last backup timestamp (UTC) from the sidecar, or null if none. */
    public static Instant getLastBackupAtUtcOrNull() {
        Path file = activeVaultPath();
        if (file == null || !Files.isRegularFile(file)) return null;
        try {
            /** Reuse the same sidecar and metadata logic */
            var sidecar = sidecarPathFor(file);
            var meta = LastBackupMeta.read(sidecar);
            return meta.lastBackupAtUtc;
        } catch (Exception e) {
            return null;
        }
    }
    
 /** ===== per-vault backup settings (.backupConfigs) ===== */
    static final class BackupSettings {
        boolean enabled = false; /** default: OFF so users aren't forced into AWS */
        BackupScheduler.ScheduleType type = BackupScheduler.ScheduleType.DAILY;
        int hour = 8;                // 0..23
        int minute = 0;              // reserved for future use; scheduler ignores for now
        DayOfWeek dow = DayOfWeek.SUNDAY; // weekly only
        int dom = 1;                 // monthly only (1..28 recommended)

        static BackupSettings load(Path vaultPath) {
            BackupSettings s = new BackupSettings();
            /** variables need to be more descriptive. Don't change code, but in the future this will need work */
            try {
                var cfgPath = backupConfigPathFor(vaultPath);
                if (!Files.isRegularFile(cfgPath)) return s; // defaults

                var p = new Properties();
                try (var in = Files.newInputStream(cfgPath)) {
                    p.load(in);
                }
                String en = p.getProperty("schedule.enabled");
                if (en != null) s.enabled = Boolean.parseBoolean(en);

                String t = p.getProperty("schedule.type");
                if (t != null) {
                    try { s.type = BackupScheduler.ScheduleType.valueOf(t); } catch (Exception ignored) {}
                }

                s.hour = clampInt(parseIntOr(p.getProperty("schedule.hour"), s.hour), 0, 23);
                s.minute = clampInt(parseIntOr(p.getProperty("schedule.minute"), s.minute), 0, 59);

                String dowStr = p.getProperty("schedule.dow");
                if (dowStr != null) {
                    try { s.dow = DayOfWeek.valueOf(dowStr); } catch (Exception ignored) {}
                }

                s.dom = clampInt(parseIntOr(p.getProperty("schedule.dom"), s.dom), 1, 28);
            } catch (Exception ignored) {}
            return s;
        }

        void save(Path vaultPath) throws Exception {
            var cfgPath = backupConfigPathFor(vaultPath);
            var p = new Properties();
            p.setProperty("schedule.enabled", Boolean.toString(enabled));
            p.setProperty("schedule.type", type.name());
            p.setProperty("schedule.hour", Integer.toString(hour));
            p.setProperty("schedule.minute", Integer.toString(minute)); // stored now; UI may use later
            p.setProperty("schedule.dow", dow.name());
            p.setProperty("schedule.dom", Integer.toString(dom));
            try (var out = Files.newOutputStream(
                    cfgPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE)) {
                p.store(out, "Backup schedule settings (per-vault)");
            }
        }
        
        private static int parseIntOr(String s, int def) { /** parseIntOr: parse an int from string or return the provided default if missing/invalid */
            try { return (s == null) ? def : Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
        }
        
        private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); } /** clampInt: constrain v into [lo, hi] to keep schedule values sane */
    }
    
    /**
     * Load per-vault backup settings from <vault>.backupConfigs and start the scheduler if enabled.
     * @return the started BackupScheduler, or null if disabled / no active vault.
     */
    public static BackupScheduler startSchedulerFromSettingsAfterAuth(Component parent) {
        Path vault = activeVaultPath();
        if (vault == null || !Files.isRegularFile(vault)) return null;

        BackupSettings s = BackupSettings.load(vault);
        if (!s.enabled) {
            // Optional: log that auto-backups are disabled for this vault
            return null;
        }

        // Construct from settings (minute currently ignored by BackupScheduler)
        BackupScheduler sched = new BackupScheduler(s.type, s.hour, s.dow, s.dom);
        sched.startAfterAuth();
        return sched;
    }
    
    /** Step 5B: Show the per-vault Backup Schedule dialog (enable + cadence).
     *  Returns true if settings were saved, false if canceled or no active vault.
     */
    public static boolean showBackupScheduleDialog(Component parent) {
        Path vault = activeVaultPath();
        if (vault == null || !Files.isRegularFile(vault)) {
            JOptionPane.showMessageDialog(parent,
                    "No active vault file is set.",
                    "Backup Schedule", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        // Load current settings
        BackupSettings s = BackupSettings.load(vault);

        // --- UI controls ---
        JCheckBox enabledChk = new JCheckBox("Enable automatic backups", s.enabled);

        JRadioButton dailyRb = new JRadioButton("Daily");
        JRadioButton weeklyRb = new JRadioButton("Weekly");
        JRadioButton monthlyRb = new JRadioButton("Monthly");
        var group = new ButtonGroup();
        group.add(dailyRb); group.add(weeklyRb); group.add(monthlyRb);

        switch (s.type) {
            case DAILY -> dailyRb.setSelected(true);
            case WEEKLY -> weeklyRb.setSelected(true);
            case MONTHLY -> monthlyRb.setSelected(true);
        }

        JSpinner hourSp = new JSpinner(new SpinnerNumberModel(s.hour, 0, 23, 1));
        JLabel hourLbl = new JLabel("Hour (0–23):");

        /** Minute is optional in scheduler; we persist it for future use but it's not required. */
        JSpinner minuteSp = new JSpinner(new SpinnerNumberModel(s.minute, 0, 59, 1));
        JLabel minuteLbl = new JLabel("Minute (0–59):");

        JComboBox<DayOfWeek> dowCb = new JComboBox<>(DayOfWeek.values());
        dowCb.setSelectedItem(s.dow);
        JLabel dowLbl = new JLabel("Day of week:");

        JSpinner domSp = new JSpinner(new SpinnerNumberModel(s.dom, 1, 28, 1));
        JLabel domLbl = new JLabel("Day of month (1–28):");

        /** Layout notes:
         -  We use GridBagLayout to create a compact 2-column grid (labels left, controls right).
         - 'gc.insets' adds padding around each cell; 'anchor=WEST' left-aligns within cells.
         - 'gridwidth=2' lets "Enable automatic backups" span both columns.
         - 'cadenceRow' is a nested FlowLayout panel to keep the three radio buttons on one row.
        */
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gc = new GridBagConstraints();
        gc.insets = new Insets(4, 6, 4, 6);
        gc.anchor = GridBagConstraints.WEST;
        gc.gridx = 0; gc.gridy = 0; gc.gridwidth = 2;
        panel.add(enabledChk, gc);

        gc.gridwidth = 1; gc.gridy++;
        panel.add(new JLabel("Cadence:"), gc);
        gc.gridx = 1;
        JPanel cadenceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        cadenceRow.add(dailyRb); cadenceRow.add(weeklyRb); cadenceRow.add(monthlyRb);
        panel.add(cadenceRow, gc);

        gc.gridx = 0; gc.gridy++;
        panel.add(hourLbl, gc);
        gc.gridx = 1; panel.add(hourSp, gc);

        gc.gridx = 0; gc.gridy++;
        panel.add(minuteLbl, gc);
        gc.gridx = 1; panel.add(minuteSp, gc);

        gc.gridx = 0; gc.gridy++;
        panel.add(dowLbl, gc);
        gc.gridx = 1; panel.add(dowCb, gc);

        gc.gridx = 0; gc.gridy++;
        panel.add(domLbl, gc);
        gc.gridx = 1; panel.add(domSp, gc);

        /** Enable/disable cadence fields based on checkbox + selection */
        Runnable updateEnableState = () -> {
            boolean en = enabledChk.isSelected();
            hourLbl.setEnabled(en); hourSp.setEnabled(en);
            minuteLbl.setEnabled(en); minuteSp.setEnabled(en);

            boolean showWeekly = en && weeklyRb.isSelected();
            boolean showMonthly = en && monthlyRb.isSelected();

            dowLbl.setEnabled(showWeekly); dowCb.setEnabled(showWeekly);
            domLbl.setEnabled(showMonthly); domSp.setEnabled(showMonthly);
        };
        ActionListener refresh = e -> updateEnableState.run();
        enabledChk.addActionListener(refresh);
        dailyRb.addActionListener(refresh);
        weeklyRb.addActionListener(refresh);
        monthlyRb.addActionListener(refresh);
        updateEnableState.run();

        /** Show dialog */
        int res = JOptionPane.showConfirmDialog(
                parent, panel, "Backup Schedule",
                JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE);

        if (res != JOptionPane.OK_OPTION) return false;

        /** Collect & validate */
        boolean enabled = enabledChk.isSelected();
        BackupScheduler.ScheduleType type = dailyRb.isSelected() ? BackupScheduler.ScheduleType.DAILY
                : weeklyRb.isSelected() ? BackupScheduler.ScheduleType.WEEKLY
                : BackupScheduler.ScheduleType.MONTHLY;

        int hour = ((Number) hourSp.getValue()).intValue();
        int minute = ((Number) minuteSp.getValue()).intValue();
        DayOfWeek dow = (DayOfWeek) dowCb.getSelectedItem();
        int dom = ((Number) domSp.getValue()).intValue();

        /** Save settings */
        s.enabled = enabled;
        s.type = type;
        s.hour = Math.max(0, Math.min(23, hour));
        s.minute = Math.max(0, Math.min(59, minute));
        s.dow = (dow == null ? DayOfWeek.SUNDAY : dow);
        s.dom = Math.max(1, Math.min(28, dom));

        try {
            s.save(vault);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(parent,
                    "Failed to save schedule settings:\n" + ex.getMessage(),
                    "Backup Schedule", JOptionPane.ERROR_MESSAGE);
            return false;
        }

        return true;
    }
    
    public static void runBackupTestAsync(JFrame frame) {
        new Thread(() -> {
            boolean uploaded = CloudBackupUtils.performBackupIfChangedNow();
            SwingUtilities.invokeLater(() -> {
                JOptionPane.showMessageDialog(
                    frame,  /** use frame instead of null so it centers on your window */
                    uploaded ? "Backup uploaded to S3." : "No changes detected — nothing uploaded.",
                    "Backup Test",
                    JOptionPane.INFORMATION_MESSAGE
                );
            });
        }, "BackupTestThread").start();
    }
    
}

