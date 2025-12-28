package managerV2;

/**
 * Entry
 * ------
 * Simple data container (POJO) representing a single password record in the vault.
 * Each Entry holds:
 *   - the website name (used as the lookup key)
 *   - the encrypted password string (AES + Base64 encoded)
 *
 * Used by FileUtils and PasswordManagerLogic for reading/writing vault entries.
 * Immutable-like behavior by convention—fields are package-private for simplicity.
 */
class Entry {
    String website;             // website or service identifier
    String encryptedPassword;   // AES-encrypted and Base64-encoded password

    /**
     * Constructs a new Entry for a given site and encrypted password.
     * @param website name of the site (plaintext identifier)
     * @param encryptedPassword AES-encrypted password string
     */
    Entry(String website, String encryptedPassword) {
        this.website = website;
        this.encryptedPassword = encryptedPassword;
    }
}
