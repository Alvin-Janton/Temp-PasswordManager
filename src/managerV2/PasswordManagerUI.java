package managerV2;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.awt.event.WindowAdapter;

/**
 * PasswordManagerUI 
 * - Builds a 3-column UI (Generate / Add / Retrieve) using basic Swing containers.
 * - Wires UI actions to application logic (FileUtils, CloudBackupUtils, MasterKeyManager, etc.).
 * - Includes a lightweight StatusBar for user feedback (info/warn/error/progress).
 *
 * NOTE: This file intentionally avoids logic changes; comments were added to clarify intent.
 */
public class PasswordManagerUI {
	// ----- Status helpers (static shortcuts to the StatusBar) -----
	private static StatusBar statusRef;
	public static void info(String msg)                             { if (statusRef != null) statusRef.showInfo(msg); }
	public static void info(String msg, String selectedSite)        { if (statusRef != null) statusRef.showInfo(msg); }
	public static void warn(String msg)                             { if (statusRef != null) statusRef.showWarning(msg); }
	public static void err(String msg)                              { if (statusRef != null) statusRef.showError(msg); }
	public static void progress(String msg)                         { if (statusRef != null) statusRef.showProgress(msg); }
	public static void progressDone()                               { if (statusRef != null) statusRef.hideProgress(); }

	// UI sizing tokens (kept small & centralized)
	private static int buttonWidth = 150;
	private static int fieldHeight = 25;

	// Typography token (relative to LAF default)
	private static final int TITLE_DELTA_PT = 2; // +2pt for section titles

	public static void setupUIComponents(JFrame frame) {
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

		// Root: BorderLayout with a content area (CENTER) and a status bar (SOUTH)
		JPanel root = new JPanel(new BorderLayout());
		frame.setContentPane(root);

		// 1 row, 3 columns (Generate / Add / Retrieve). The 12px gap separates columns.
		JPanel content = new JPanel(new GridLayout(1, 3, 12, 0));
		root.add(content, BorderLayout.CENTER);

		// StatusBar: a slim feedback area below the content; shows transient messages.
		StatusBar statusBar = new StatusBar();
		statusRef = statusBar;

		// ----- Menus (Export/Import, Theme, Cloud Backup, Test) -----
		JMenuBar menuBar = new JMenuBar();
		JMenu fileMenu = new JMenu("Export/Import File");
		JMenu themeMenu = new JMenu("Toggle Theme");
		JMenu backupMenu = new JMenu("Cloud Backup");
		JMenu testMenu = new JMenu("Test Features");

		JMenuItem exportItem = new JMenuItem("Export Encrypted Backup");
		JMenuItem importItem = new JMenuItem("Import Encrypted Backup");
		JMenuItem importAndDecryptItem = new JMenuItem("Import + Decrypt Backup");
		JMenuItem exportDecryptedItem = new JMenuItem("Export Decrypted Backup");
		JMenuItem importDecryptedItem = new JMenuItem("Import Decrypted Backup");
		JMenuItem lightMode = new JMenuItem("Switch to Light Mode");
		JMenuItem darkMode = new JMenuItem("Switch to Dark Mode");
		JMenuItem uploadBackup = new JMenuItem("Upload backup to S3");
		JMenuItem retrieveBackup = new JMenuItem("Retrieve backup from S3");
		JMenuItem uploadActiveVault = new JMenuItem("Upload current vault");
		JMenuItem retrieveChoose = new JMenuItem("Retrieve specific backup from S3…");
		JMenuItem exportCSV = new JMenuItem("Export CSV");
		JMenuItem importCSV = new JMenuItem("Import CSV");
		JMenuItem pruneTest = new JMenuItem("Prune Backup");
		JMenuItem backupTest = new JMenuItem("backup");
		JMenuItem enableAutoBackup = new JMenuItem("Enable-Auto-Backup");

		fileMenu.add(importDecryptedItem);
		fileMenu.add(exportDecryptedItem);
		fileMenu.add(exportItem);
		fileMenu.add(importItem);
		fileMenu.add(importAndDecryptItem);
		fileMenu.add(exportCSV);
		fileMenu.add(importCSV);
		themeMenu.add(lightMode);
		themeMenu.add(darkMode);
		backupMenu.add(uploadBackup);
		backupMenu.add(retrieveBackup);
		backupMenu.add(uploadActiveVault);
		backupMenu.add(retrieveChoose);
		testMenu.add(pruneTest);
		testMenu.add(backupTest);
		backupMenu.add(enableAutoBackup);
		menuBar.add(fileMenu);
		menuBar.add(themeMenu);
		menuBar.add(backupMenu);
		menuBar.add(testMenu);
		frame.setJMenuBar(menuBar);

		// ----- Panels (each column uses a simple vertical BoxLayout) -----
		JPanel generatePanel = new JPanel();
		generatePanel.setLayout(new BoxLayout(generatePanel, BoxLayout.Y_AXIS));
		generatePanel.setBorder(BorderFactory.createTitledBorder("Generate Password"));
		generatePanel.setBorder(BorderFactory.createLineBorder(Color.RED)); // developer-visual border

		JPanel addPanel = new JPanel();
		addPanel.setLayout(new BoxLayout(addPanel, BoxLayout.Y_AXIS));
		addPanel.setBorder(BorderFactory.createTitledBorder("Add Password"));
		addPanel.setBorder(BorderFactory.createLineBorder(Color.GREEN)); // developer-visual border

		JPanel retrievePanel = new JPanel();
		retrievePanel.setLayout(new BoxLayout(retrievePanel, BoxLayout.Y_AXIS));
		retrievePanel.setBorder(BorderFactory.createTitledBorder("Retrieve Section"));
		retrievePanel.setBorder(BorderFactory.createLineBorder(Color.BLUE)); // developer-visual border

		// ----- Components -----
		JPasswordField generatedPassword = createPasswordField();
		generatedPassword.setEditable(false);

		JTextField websiteName = createTextField(180, 120, 120, 25);
		JPasswordField passwordName = createPasswordField(180, 200, 120, 25);
		JTextField searchField = createTextField(180, 290, 120, 25);

		JButton Generate = createButton("generate", 0, 180, 120, 25);
		JButton Add = createButton("add", 180, 270, 120, 25);
		JButton copyGenerated = createButton("copy", 1, 150, 120, 25);
		JButton editButton = createButton("Edit", 310, 320, 80, 25);
		JButton deleteButton = createButton("Delete", 400, 320, 80, 25);
		JButton copyFromDropdownButton = createButton("Copy Password", 350, 240, 120, 25);
		JCheckBox showPasswordCheckbox = new JCheckBox("Show");
		JCheckBox showGeneratedPassword = new JCheckBox("Show");

		JLabel generateLabel = createLabel("Generate a Password");
		JLabel addLabel = createLabel("Add a Password");
		JLabel retrieveLabel = createLabel("Retrieve a Password");

		JComboBox<String> siteDropdown = new JComboBox<>();
		JRootPane key = frame.getRootPane();

		// ----- Alignment & sizing (centered columns, unified widths) -----
		generateLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		applyTitleStyle(generateLabel);

		generatedPassword.setAlignmentX(Component.CENTER_ALIGNMENT);
		unifySize(buttonWidth, generatedPassword);
		generatedPassword.setFont(new Font("SansSerif", Font.PLAIN, 12));
		generatedPassword.setBorder(BorderFactory.createLineBorder(Color.BLACK));

		Generate.setAlignmentX(Component.CENTER_ALIGNMENT);
		copyGenerated.setAlignmentX(Component.CENTER_ALIGNMENT);

		addLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		applyTitleStyle(addLabel);

		websiteName.setAlignmentX(Component.CENTER_ALIGNMENT);
		unifySize(buttonWidth, websiteName);

		passwordName.setAlignmentX(Component.CENTER_ALIGNMENT);
		unifySize(buttonWidth, passwordName);

		Add.setAlignmentX(Component.CENTER_ALIGNMENT);

		retrieveLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
		applyTitleStyle(retrieveLabel);

		searchField.setAlignmentX(Component.CENTER_ALIGNMENT);
		unifySize(buttonWidth, searchField);

		siteDropdown.setAlignmentX(Component.CENTER_ALIGNMENT);
		unifySize(buttonWidth, siteDropdown);

		copyFromDropdownButton.setAlignmentX(Component.CENTER_ALIGNMENT);

		// ----- Generate column -----
		generatePanel.add(generateLabel);
		generatePanel.add(Box.createVerticalStrut(10));
		generatePanel.add(generatedPassword);
		generatePanel.add(Box.createVerticalStrut(10));

		// Small row for the "Show" checkbox under the generated password
		JPanel checkboxRow = rowCenter();
		checkboxRow.add(showGeneratedPassword);
		checkboxRow.add(Box.createVerticalStrut(1)); // spacing shim (no vertical effect inside FlowLayout)
		generatePanel.add(checkboxRow);

		// Row with Generate + Copy buttons
		JPanel generateButtonRow = rowCenter();
		generateButtonRow.add(Generate);
		generateButtonRow.add(copyGenerated);
		generatePanel.add(generateButtonRow);

		// ----- Add column -----
		JPanel upperRow = rowCenter(); // holds only the "Show" checkbox to keep it visually tighter
		addPanel.add(addLabel);
		addPanel.add(Box.createVerticalStrut(10));
		addPanel.add(websiteName);
		addPanel.add(Box.createVerticalStrut(10));
		addPanel.add(passwordName);
		addPanel.add(Box.createVerticalStrut(10));
		upperRow.add(showPasswordCheckbox);
		addPanel.add(upperRow);
		addPanel.add(Add);

		// ----- Retrieve column -----
		retrievePanel.add(retrieveLabel);
		retrievePanel.add(Box.createVerticalStrut(10));
		retrievePanel.add(searchField);
		retrievePanel.add(Box.createVerticalStrut(10));
		retrievePanel.add(siteDropdown);
		retrievePanel.add(Box.createVerticalStrut(10));
		retrievePanel.add(copyFromDropdownButton);
		retrievePanel.add(Box.createVerticalStrut(10));
		JPanel editAndDeleteRow = rowCenter();
		editAndDeleteRow.add(editButton);
		editAndDeleteRow.add(deleteButton);
		retrievePanel.add(editAndDeleteRow);
		retrievePanel.add(Box.createVerticalStrut(5));

		// ----- Add columns + status bar -----
		content.add(generatePanel);
		content.add(addPanel);
		content.add(retrievePanel);
		root.add(statusBar, BorderLayout.SOUTH);
		statusRef.showInfo("Ready");

		// ----- ACTION WIRING (UI -> logic). This section hooks all buttons/menu items/shortcuts. -----
		// If you're just reading structure, you can skim this—each line delegates to the appropriate helper class.
		Generate.addActionListener(e -> generatedPassword.setText(Utils.generatePassword()));
		PasswordManagerLogic.attachAddButtonLogic(frame, Add, websiteName, passwordName, siteDropdown, MasterKeyManager.masterPassword, showPasswordCheckbox);
		PasswordManagerLogic.attachCopyButtonLogic(frame, copyGenerated, generatedPassword, "Generated password");
		Utils.populateDropdown(siteDropdown);
		PasswordManagerLogic.attachDeleteButtonLogic(frame, deleteButton, siteDropdown, MasterKeyManager.masterPassword);

		exportItem.addActionListener(e -> FileUtils.exportEncryptedBackup(frame, MasterKeyManager.masterPassword));
		importItem.addActionListener(e -> FileUtils.importEncryptedBackupSafely(frame, MasterKeyManager.masterPassword, siteDropdown));
		importAndDecryptItem.addActionListener(e -> FileUtils.importAndDecryptOldBackup(frame));
		exportDecryptedItem.addActionListener(e -> FileUtils.exportDecryptedBackup(frame));
		importDecryptedItem.addActionListener(e -> FileUtils.importDecryptedBackup(frame, siteDropdown));

		PasswordManagerLogic.attachEditButtonLogic(frame, editButton, siteDropdown, MasterKeyManager.key);
		PasswordManagerLogic.attachSearchFieldFilterLogic(searchField, siteDropdown, Utils.allWebsites);
		copyFromDropdownButton.addActionListener(e -> PasswordManagerLogic.attachCopyFromDropdownLogic(frame, siteDropdown, MasterKeyManager.key));

		lightMode.addActionListener(e -> Utils.applyLightMode(frame));
		darkMode.addActionListener(e -> Utils.applyDarkMode(frame));

		uploadBackup.addActionListener(e -> managerV2.CloudBackupUtils.uploadBackupWithChooser(frame));
		retrieveBackup.addActionListener(e -> managerV2.CloudBackupUtils.retrieveLatestBackupWithChooser(frame));
		uploadActiveVault.addActionListener(e -> managerV2.CloudBackupUtils.uploadActiveVaultToS3(frame));
		retrieveChoose.addActionListener(e -> CloudBackupUtils.retrieveBackupFromListWithChooser(frame));
		exportCSV.addActionListener(e -> FileUtils.exportToCsvMinimal(frame));
		importCSV.addActionListener(e -> FileUtils.importFromCsvMinimal(frame, siteDropdown));
		pruneTest.addActionListener(e -> CloudBackupUtils.pruneOldBackupsForActiveVault(frame));
		backupTest.addActionListener(e -> CloudBackupUtils.runBackupTestAsync(frame));
		enableAutoBackup.addActionListener(e -> MasterKeyManager.openBackupScheduleAndApply(frame));

		// Keyboard accelerators/bindings for the three columns
		Utils.bindRetrieveSmartKeys(key, retrievePanel, searchField, siteDropdown, copyFromDropdownButton, deleteButton, editButton);
		Utils.bindGenerateKeys(key, Generate, copyGenerated);
		Utils.bindAddKeys(key, Add, addPanel);

		// Toggle "Show" for generated password by swapping echo char
		final char defaultEcho = generatedPassword.getEchoChar();
		showGeneratedPassword.addActionListener(e ->
			generatedPassword.setEchoChar(showGeneratedPassword.isSelected() ? (char) 0 : defaultEcho)
		);

		// Initial focus: when the frame opens, put caret in Retrieve → search field
		frame.pack();
		frame.setLocationRelativeTo(null);
		frame.addWindowListener(new WindowAdapter() {
			@Override
			public void windowOpened(WindowEvent e) {
				SwingUtilities.invokeLater(() -> searchField.requestFocusInWindow());
			}
		});
		frame.setVisible(true);
	}

	// ----- Private helpers (simple wrappers; kept for readability & consistency) -----

	/** Creates a plain text field; bounds are ignored by BoxLayout but kept for API parity. */
	private static JTextField createTextField(int x, int y, int width, int height) {
		JTextField field = new JTextField();
		field.setBounds(x, y, width, height);
		return field;
	}

	/** Creates a password field; bounds are ignored by BoxLayout but kept for API parity. */
	private static JPasswordField createPasswordField(int x, int y, int width, int height) {
		JPasswordField field = new JPasswordField();
		field.setBounds(x, y, width, height);
		return field;
	}

	/** Creates a password field with default sizing. */
	private static JPasswordField createPasswordField() {
		return new JPasswordField();
	}

	/** Creates a JButton; bounds are ignored by BoxLayout but kept for API parity. */
	private static JButton createButton(String text, int x, int y, int width, int height) {
		JButton button = new JButton(text);
		button.setBounds(x, y, width, height);
		return button;
	}

	/** Creates a JLabel with explicit bounds (ignored by BoxLayout); kept for parity. */
	private static JLabel createLabel(String text, int x, int y, int width, int height) {
		JLabel label = new JLabel(text);
		label.setBounds(x, y, width, height);
		return label;
	}

	/** Creates a simple JLabel with default sizing. */
	private static JLabel createLabel(String text) {
		return new JLabel(text);
	}

	/** Makes a left-aligned FlowLayout row that itself is centered in the column. */
	private static JPanel rowCenter() {
		JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
		p.setAlignmentX(Component.CENTER_ALIGNMENT);
		return p;
	}

	/** Unifies width/height for compact, consistent rows. */
	private static void unifySize(int width, JComponent c) {
		Dimension d = new Dimension(width, fieldHeight);
		c.setPreferredSize(d);
		c.setMaximumSize(new Dimension(width, fieldHeight));
	}

	/** Applies a bold, slightly larger font to a section title label. */
	private static void applyTitleStyle(JLabel l) {
		Font base = UIManager.getFont("Label.font");
		if (base == null) base = l.getFont();
		Font sized = base.deriveFont(base.getSize2D() + TITLE_DELTA_PT);
		l.setFont(sized.deriveFont(Font.BOLD));
	}

	// ----- Lightweight status bar (theme-aware, with optional pulse for "info") -----
	private static final class StatusBar extends JPanel {
		private final JLabel msg = new JLabel(" ");   // keep height even when empty
		private final JLabel right = new JLabel(" "); // optional right-aligned progress text
		private String currentKind = "info";          // last style used ("info" | "warn" | "error")
		private Timer pulseTimer;                     // small timer to "pulse" the bar for info messages
		private int pulseCount = 0;                   // counts pulse steps to stop after a few iterations
		private static final int STATUS_MIN_HEIGHT = 44; // min visual height
		private static final int STATUS_FONT_SIZE = 16;  // readable font size for status text

		StatusBar() {
			setLayout(new BorderLayout(8, 0));
			msg.setOpaque(false);
			right.setOpaque(false);
			add(msg, BorderLayout.WEST);
			add(right, BorderLayout.EAST);
			applyStyle("info"); // default style on construction
		}

		/**
		 * Called when Look & Feel changes. We re-apply style using ThemePalette to
		 * refresh colors/fonts that may have changed with the new theme.
		 * Swing calls updateUI() during LAF switches; we defer to EDT to ensure UIManager is ready.
		 */
		@Override public void updateUI() {
			super.updateUI();
			if (msg == null) return; // guard during construction
			SwingUtilities.invokeLater(() -> applyStyle(currentKind != null ? currentKind : "info"));
		}

		/**
		 * Applies a background blend and font based on message "kind".
		 * - We blend between the panel base color and the accent to make the bar noticeable.
		 * - Ratio is stronger for warnings/errors, subtler for info.
		 */
		private void applyStyle(String kind) {
			if (kind == null) kind = "info";
			this.currentKind = kind;

			ThemePalette p = ThemePalette.current(); // Centralized theme: base bg, accent, divider, fonts, etc.

			// Choose a blend ratio depending on severity.
			float ratio = switch (kind) {
				case "warn"  -> 0.18f;
				case "error" -> 0.26f;
				default      -> 0.50f;
			};

			// Blend base background toward accent by 'ratio' (per-channel).
			setBackground(new Color(
				Math.round(p.baseBg.getRed()   * (1f - ratio) + p.accent.getRed()   * ratio),
				Math.round(p.baseBg.getGreen() * (1f - ratio) + p.accent.getGreen() * ratio),
				Math.round(p.baseBg.getBlue()  * (1f - ratio) + p.accent.getBlue()  * ratio)
			));

			Font baseFont = p.baseLabelFont;
			Font sized = baseFont.deriveFont((float) STATUS_FONT_SIZE);

			setOpaque(true);
			msg.setFont("info".equals(kind) ? sized.deriveFont(Font.BOLD) : sized);

			// Top divider helps separate the bar from content regardless of theme brightness.
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(1, 0, 0, 0, p.divider),
				BorderFactory.createEmptyBorder(6, 10, 6, 10)
			));
		}

		/** Show an INFO/WARN/ERROR message; auto-hide if autoHideMs > 0 (runs on the EDT). */
		private void showMessage(String text, String kind, int autoHideMs) {
			SwingUtilities.invokeLater(() -> {
				applyStyle(kind);
				msg.setText(text);
				// For "info", give a brief visual pulse to draw the eye without being noisy.
				if ("info".equals(kind)) pulseOnce();
				if (autoHideMs > 0) {
					Timer t = new Timer(autoHideMs, e -> {
						msg.setText(" ");
						applyStyle("info");
						repaint();
					});
					t.setRepeats(false);
					t.start();
				}
			});
		}

		void showInfo(String text)    { showMessage(text, "info", 5000); }
		void showWarning(String text) { showMessage(text, "warn", 0); }
		void showError(String text)   { showMessage(text, "error", 0); }

		void showProgress(String text) {
			applyStyle("info");
			right.setText(text == null ? "Working…" : text);
			repaint();
		}
		void hideProgress() { right.setText(" "); repaint(); }

		/**
		 * Perform two quick "pulses" of the background (slightly brighter/dimmer)
		 * to help users notice a new info message without a modal dialog.
		 */
		private void pulseOnce() {
			if (pulseTimer != null && pulseTimer.isRunning()) pulseTimer.stop();
			Color orig = getBackground();
			Color focus = UIManager.getColor("Component.focusColor");
			if (focus == null) focus = new Color(0x3D7FFF);

			pulseCount = 0;
			pulseTimer = new Timer(120, e -> {
				setBackground(pulseCount % 2 == 0
					? new Color(
						Math.min(255, orig.getRed() + 12),
						Math.min(255, orig.getGreen() + 12),
						Math.min(255, orig.getBlue() + 12))
					: orig);
				repaint();
				if (++pulseCount >= 4) { // 2 quick pulses
					((Timer) e.getSource()).stop();
					setBackground(orig);
				}
			});
			pulseTimer.start();
		}
	}
}
