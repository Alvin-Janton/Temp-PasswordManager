# Development Logs

### 2025-12-19 — Encrypt Website Names at Rest (Phase 2-lite)

- **Goal**: Encrypt website names at rest in vault file. Previously only passwords were encrypted; website names were stored in plaintext. After this change, both website and password are encrypted using AES-128 with the session key.

- **Files changed**:
  - `src/managerV2/FileUtils.java` (9 methods updated)
  - `src/managerV2/PasswordManagerLogic.java` (2 methods updated)
  - `src/managerV2/Utils.java` (1 method updated)

- **Key changes**:
  - **FileUtils.java**:
    - `appendEntry()`: Now encrypts website before writing to vault
    - `loadAllEntries()`: Decrypts website line when reading entries
    - `writeEntriesWithVerification()`: Encrypts website when rewriting vault
    - `importEncryptedBackupSafely()`: Decrypts websites from both source and destination vaults, re-encrypts with destination key
    - `exportDecryptedBackup()`: Decrypts website before writing to plaintext export
    - `importDecryptedBackup()`: Decrypts existing vault websites for duplicate detection, encrypts imported website
    - `exportToCsvMinimal()`: Decrypts website before writing to CSV
    - `importFromCsvMinimal()`: Decrypts existing vault websites for duplicate detection, encrypts CSV website on import
    - `importAndDecryptOldBackup()`: Decrypts website for display
  - **PasswordManagerLogic.java**:
    - `attachAddButtonLogic()`: Uses `FileUtils.loadAllEntries()` for duplicate checking (replaces raw file scanning)
    - `attachCopyFromDropdownLogic()`: Uses `FileUtils.loadAllEntries()` instead of raw file scanning
  - **Utils.java**:
    - `populateDropdown()`: Decrypts website lines before adding to dropdown for UI display

- **Vault format**: Each entry now stored as:
  ```
  <encryptedWebsiteBase64>
  <encryptedPasswordBase64>
  ------------------------
  ```

- **In-memory vs disk**: Website names are encrypted on disk but stored as plaintext in memory (Entry objects) for easy comparison and UI display. Dropdown shows plaintext website names.

- **Tests/verification performed**:
  - Code review and implementation complete
  - All encryption/decryption paths updated consistently
  - Import/export flows handle key transitions correctly
  - Duplicate detection works with plaintext comparisons

- **Notes**:
  - No backward compatibility: existing vaults with plaintext website names must be recreated or migrated via export decrypted → import decrypted
  - Uses `MasterKeyManager.key` (active session key) for all encryption/decryption
  - Follows "disk = encrypted, memory = plaintext" principle throughout

### 2025-12-26 — Replace Fully-Qualified Class Names with Imports

- **Goal**: Improve code readability by replacing fully-qualified class name references (e.g., `java.util.concurrent.TimeUnit`) with simple class names (e.g., `TimeUnit`) and adding proper import statements. This is a pure readability refactor with no behavior changes.

- **Files changed**:
  - `src/managerV2/BackupScheduler.java`
  - `src/managerV2/CloudBackupLite.java`
  - `src/managerV2/CloudBackupUtils.java`
  - `src/managerV2/FileUtils.java`
  - `src/managerV2/PasswordManagerLogic.java`

- **Key changes**:
  - **BackupScheduler.java**: Replaced `java.util.concurrent.TimeUnit.MILLISECONDS` → `TimeUnit.MILLISECONDS` (line 156)
  - **CloudBackupLite.java**: Added imports for `Files`, `Path`, and `DateTimeFormatter`; replaced 6 FQCNs with simple names
  - **CloudBackupUtils.java**: Added 10 imports (`Instant`, `ZonedDateTime`, `DateTimeFormatter`, `ZoneId`, `DayOfWeek`, `Locale`, `Properties`, `MessageDigest`, `StandardOpenOption`, `InputStream`); replaced 100+ FQCNs throughout the file including all `java.nio.file.*`, `java.time.*`, `javax.swing.*`, and `java.awt.Component` references
  - **FileUtils.java**: Replaced 5 instances of `java.nio.charset.StandardCharsets.UTF_8` → `StandardCharsets.UTF_8` (lines 182, 203, 262, 360, 406)
  - **PasswordManagerLogic.java**: Added imports for `DocumentListener` and `DocumentEvent`; replaced 4 FQCNs with simple names

- **Tests/verification performed**:
  - All replacements verified for correctness
  - No name collision conflicts detected
  - Import statements properly organized at the top of each file
  - Existing wildcard imports preserved where present

- **Notes**:
  - Pure cosmetic refactor—zero behavior changes
  - No formatting or style changes beyond the FQCN replacements
  - All imports are explicit (no new wildcard imports introduced)
  - Total: 15 new imports added, 120+ FQCN replacements made across 5 files

### 2025-12-26 — Complete FQCN Cleanup in CloudBackupUtils

- **Goal**: Replace remaining fully-qualified class names (java.util.List, java.awt.*) with simple names and proper imports in CloudBackupUtils.java to complete the readability refactor.

- **Files changed**:
  - `src/managerV2/CloudBackupUtils.java`

- **Key changes**:
  - Added 6 new imports: `List`, `GridBagLayout`, `GridBagConstraints`, `Insets`, `FlowLayout`, `ActionListener`
  - Replaced 10 FQCN occurrences:
    - `java.util.List<CloudBackupLite.BackupObject>` → `List<CloudBackupLite.BackupObject>` (3 occurrences, lines 177-184)
    - `java.awt.GridBagLayout` → `GridBagLayout` (line 622)
    - `java.awt.GridBagConstraints` → `GridBagConstraints` (2 occurrences, lines 623, 625)
    - `java.awt.Insets` → `Insets` (line 624)
    - `java.awt.FlowLayout` → `FlowLayout` (2 occurrences, line 632)
    - `java.awt.event.ActionListener` → `ActionListener` (line 664)

- **Tests/verification performed**:
  - Verified no duplicate imports added
  - Confirmed no remaining FQCNs in CloudBackupUtils.java
  - All replacements maintain identical behavior

- **Notes**:
  - Completes the FQCN refactor started earlier today
  - CloudBackupUtils.java now has consistent import usage throughout
  - No name collisions detected
