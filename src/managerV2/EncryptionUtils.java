package managerV2;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.io.File;
import java.util.*;

/**
 * EncryptionUtils
 * ----------------
 * Minimal helper for symmetric AES encryption/decryption and key derivation.
 * - Derives a 128-bit AES key from the user’s master password using PBKDF2 (HMAC-SHA-256).
 * - Uses a fixed salt and iteration count for reproducibility (future versions should randomize this).
 * - Provides simple Base64-encoded encrypt/decrypt helpers for storing text safely in files.
 *
 * Security Notes:
 *  • PBKDF2 slows down brute-force attacks by applying many hash iterations (here: 65,536).
 *  • AES (Advanced Encryption Standard) is used in ECB mode by default via "AES" — secure enough for
 *    this project, but not ideal for large or structured data (CBC/GCM recommended in real systems).
 *  • Static salt is maintained for backward compatibility — in production, you would generate
 *    a random salt per vault and store it with the encrypted data.
 */
public class EncryptionUtils {

    private static final String ENCRYPTION_ALGO = "AES";                 // symmetric cipher algorithm
    private static final String DERIVATION_ALGO = "PBKDF2WithHmacSHA256"; // password → key derivation
    private static final byte[] SALT = readVaultSalt();  // Reads the salt from the vault file. (Dynamic Per-Vault)
    
    /**
     * Derive a 128-bit AES key from a master password.
     * Uses PBKDF2 (Password-Based Key Derivation Function) with SHA-256 hashing.
     *
     * @param masterPassword User’s plaintext master password
     * @return 128-bit AES key as a {@link SecretKeySpec}
     */
    public static SecretKeySpec getSecretKey(String masterPassword) throws Exception {
        KeySpec spec = new PBEKeySpec(masterPassword.toCharArray(), SALT, 65536, 128); // PBEKeySpec: holds password chars, salt, iteration count, and target key length (in bits).        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(DERIVATION_ALGO); // SecretKeyFactory generates the key material using PBKDF2WithHmacSHA256.
        byte[] keyBytes = factory.generateSecret(spec).getEncoded(); // derived raw bytes       
        return new SecretKeySpec(keyBytes, ENCRYPTION_ALGO); // Wrap in SecretKeySpec so it can be used by Cipher.init()
    }
    
    public static SecretKeySpec getSecretKey(String masterPassword, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(masterPassword.toCharArray(), salt, 65536, 128); // PBEKeySpec: holds password chars, salt, iteration count, and target key length (in bits).        
        SecretKeyFactory factory = SecretKeyFactory.getInstance(DERIVATION_ALGO); // SecretKeyFactory generates the key material using PBKDF2WithHmacSHA256.
        byte[] keyBytes = factory.generateSecret(spec).getEncoded(); // derived raw bytes       
        return new SecretKeySpec(keyBytes, ENCRYPTION_ALGO); // Wrap in SecretKeySpec so it can be used by Cipher.init()
    }

    /**
     * Encrypt a plaintext string using AES and return Base64 text.
     * Steps:
     *  1. Initialize cipher in ENCRYPT_MODE with derived AES key.
     *  2. Encrypt plaintext bytes → ciphertext bytes.
     *  3. Encode ciphertext in Base64 so it can be stored as plain text.
     */
    public static String encrypt(String input, SecretKeySpec key) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGO);
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return Base64.getEncoder().encodeToString(cipher.doFinal(input.getBytes()));
    }

    /**
     * Decrypt a Base64-encoded ciphertext string using AES.
     * Steps:
     *  1. Decode Base64 back to raw ciphertext bytes.
     *  2. Initialize cipher in DECRYPT_MODE with the same key.
     *  3. Decrypt bytes → plaintext string.
     */
    public static String decrypt(String input, SecretKeySpec key) throws Exception {
        Cipher cipher = Cipher.getInstance(ENCRYPTION_ALGO);
        cipher.init(Cipher.DECRYPT_MODE, key);
        return new String(cipher.doFinal(Base64.getDecoder().decode(input)));
    }
    
    public static byte[] readVaultSalt() {
    	File vault = FileUtils.getPasswordFile();
    	
    	if (vault == null || !vault.exists()) return null;

        try (Scanner sc = new Scanner(vault)) {
            while (sc.hasNextLine()) {
                String line = sc.nextLine();
                if ("__SALT__".equals(line)) {
                    if (sc.hasNextLine()) {
                        String saltB64 = sc.nextLine();
                        return Base64.getDecoder().decode(saltB64);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}