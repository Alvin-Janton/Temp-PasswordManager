# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run Commands

This is a Maven-based Java Swing application targeting Java 22.

```bash
# Clean and compile
mvn clean compile

# Package into JAR
mvn clean package

# Run the application
java -jar target/PasswordManager-0.0.1-SNAPSHOT.jar

# Or run directly from Maven (if configured)
mvn exec:java -Dexec.mainClass="managerV2.PasswordManagerV18"
```

The main entry point is `managerV2.PasswordManagerV18`.

## Architecture Overview

### Core Security Model

This is a **vault-based password manager** where each vault is a line-based text file (`test.txt` by default) containing:
- Dynamic per-vault salt (stored in `__SALT__` section header)
- Encrypted verification data (`__VERIFICATION__` section with "check123")
- Recovery key hash (`__RECOVERY__` section with SHA-256 hash)
- Individual password entries (website, AES-encrypted password, separator)

**Critical security flows:**
1. **Master Key Derivation**: Uses PBKDF2-HMAC-SHA256 with 65,536 iterations and the vault's dynamic salt
2. **Encryption**: AES-128 in ECB mode (acknowledged limitation; CBC/GCM would be better for production)
3. **Authentication**: Master password is verified by attempting to decrypt the `__VERIFICATION__` block
4. **Recovery**: SHA-256 hash comparison (not key derivation) allows master password reset

### Key Components

**`PasswordManagerV18`** (main entry point)
- Applies saved theme → authenticates user → launches UI
- Minimal main() keeps startup predictable

**`MasterKeyManager`** (authentication & key lifecycle)
- First-run setup: prompts for master password, generates random 32-char recovery key
- Returning users: validates master password against `__VERIFICATION__` block
- Recovery flow: exports encrypted vault, resets master password, generates new recovery key
- Starts/stops `BackupScheduler` after authentication

**`EncryptionUtils`** (cryptography)
- `getSecretKey(password, salt)`: PBKDF2 → SecretKeySpec
- `encrypt()/decrypt()`: AES with Base64 encoding
- `readVaultSalt()`: extracts dynamic salt from vault file

**`FileUtils`** (vault I/O)
- `loadAllEntries()`: parses vault, skips metadata blocks (`__SALT__`, `__VERIFICATION__`, `__RECOVERY__`)
- `appendEntry()`: adds website + encrypted password + separator
- `writeEntriesWithVerification()`: rewrites vault preserving recovery block, regenerates verification block
- Vault format is line-based, NOT JSON/XML

**`PasswordManagerUI`** (Swing GUI)
- 3-column layout: Generate / Add / Retrieve
- Uses FlatLaf for modern theming (light/dark mode)
- StatusBar for non-intrusive feedback (avoids excessive dialogs)
- Menu bar handles Export/Import, Cloud Backup, Theme switching

**`PasswordManagerLogic`** (UI event handlers)
- `attachAddButtonLogic()`: validates, checks duplicates, encrypts, appends
- `attachEditButtonLogic()`: rewrites vault with updated entry
- `attachDeleteButtonLogic()`: removes entry, rewrites vault
- Duplicate detection is case-insensitive on website name

**`CloudBackupUtils`** (AWS S3 integration)
- Manual/scheduled backups to S3 using `CloudBackupLite` wrapper
- Hash-based change detection (`.lastbackup` sidecar file stores SHA-256 of vault)
- Configuration in `.backupConfigs` (gitignored): `enabled`, `type`, `hour`, etc.
- Automatic pruning keeps latest N backups (configurable)

**`BackupScheduler`** (automated backups)
- Single daemon thread using `ScheduledExecutorService`
- Supports DAILY/WEEKLY/MONTHLY schedules at specific hour
- Catch-up logic: runs immediately if overdue since last backup
- Silent operation (no UI dialogs during background runs)

**`Entry`** (data model)
- Simple POJO: `website` (plaintext) + `encryptedPassword` (Base64 AES ciphertext)

### Vault File Format

```
__SALT__
<Base64-encoded random salt>
------------------------
__VERIFICATION__
<AES-encrypted "check123">
------------------------
__RECOVERY__
<Base64-encoded SHA-256 hash of recovery key>
------------------------
website1
<encrypted password1>
------------------------
website2
<encrypted password2>
------------------------
```

All section headers (`__SALT__`, `__VERIFICATION__`, `__RECOVERY__`) must be preserved when rewriting the vault.

## Important Patterns

### Vault Modifications
When modifying vault contents:
1. Always read the current salt with `EncryptionUtils.readVaultSalt()`
2. Preserve the `__RECOVERY__` block if it exists
3. Use `FileUtils.writeEntriesWithVerification()` to rewrite with fresh verification
4. Never skip section headers when parsing

### Master Password Reset
The recovery flow intentionally:
- Exports the encrypted vault as backup (user keeps the old file)
- Generates NEW salt, NEW master password, NEW recovery key
- Does NOT decrypt old data (recovery key is not for decryption)
- User must import the old vault with the old password to migrate

### Threading
- UI code runs on EDT (Swing Event Dispatch Thread)
- Cloud backup operations use `SwingWorker` to avoid blocking UI
- `BackupScheduler` runs on dedicated daemon thread
- StatusBar updates must happen on EDT

### AWS Credentials
- Not stored in code or config files
- Expected via standard AWS credential chain (env vars, ~/.aws/credentials, IAM role)
- S3 region/bucket configured in `CloudBackupUtils` constants

## Dependencies

- **FlatLaf** (3.2): Modern Look & Feel for Swing
- **AWS SDK v2** (2.25.59): S3 client for cloud backups
- **OpenCSV** (5.9): CSV import/export functionality
- **SLF4J Simple** (2.0.13): Logging for AWS SDK

## Security Considerations

- **No plaintext storage**: Passwords encrypted with AES before writing to disk
- **Dynamic salt**: Each vault has unique random salt (recent security update)
- **Memory**: Master password kept in String (not char[]); no explicit zeroization
- **Recovery limitation**: Recovery key does NOT decrypt existing data by design
- **Encryption mode**: Uses AES/ECB (acknowledged limitation; CBC/GCM preferred for production)

## File Locations

- **Vault**: `test.txt` (configurable via `FileUtils.getPasswordFile()`)
- **Backup metadata**: `.lastbackup` (gitignored)
- **Scheduler config**: `.backupConfigs` (gitignored)
- **Build output**: `target/` (gitignored)
- **Eclipse metadata**: `.classpath`, `.project`, `.settings/`, `bin/` (gitignored)

## Current Development

The `SecurityUpdates` branch is active with recent changes:
- Dynamic salt implementation (replaces fixed salt)
- Updated all FileUtils methods to parse `__SALT__` section header
- Master key generation now uses per-vault salt
- PasswordManagerLogic and MasterKeyManager updated accordingly

## Notes for Future Work

- Consider migrating from String to char[] for master password (with explicit zeroization)
- Upgrade to AES-GCM or AES-CBC for better encryption mode
- Add unit tests (none exist currently)
- Database-backed storage for scalability
- Web-based version with authentication
