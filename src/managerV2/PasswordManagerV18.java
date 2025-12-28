package managerV2;

import javax.swing.*;

/**
 * PasswordManagerV18
 * ------------------
 * Application entry point.
 * Initializes the main JFrame, applies the user’s saved theme (dark/light),
 * verifies or sets up the master key, and finally launches the main UI.
 *
 * Execution order:
 *  1. Create JFrame (top-level window for Swing app).
 *  2. Apply saved Look & Feel via {@link Utils#applySavedTheme(JFrame)}.
 *  3. Authenticate or initialize master key with {@link MasterKeyManager}.
 *  4. Build and render UI via {@link PasswordManagerUI#setupUIComponents(JFrame)}.
 *
 * Notes:
 * - All heavy logic (file IO, encryption, cloud) happens after authentication.
 * - Keeping main() minimal ensures startup is predictable and easy to maintain.
 */
public class PasswordManagerV18 {

    public static void main(String[] args) {
        JFrame frame = new JFrame("Password Manager");

        // Step 1: Apply user's saved theme (FlatLaf light/dark).
        Utils.applySavedTheme(frame);

        // Step 2: Prompt for master key or run setup if first launch.
        MasterKeyManager.authenticateUserOrSetupMasterKey(frame);

        // Step 3: Initialize all Swing UI panels, navigation, and status bar.
        PasswordManagerUI.setupUIComponents(frame);
    }
}
