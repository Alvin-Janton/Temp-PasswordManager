# 🔐 Java Password Manager (PasswordManager18)

A secure, GUI-based password manager built in Java using Swing, designed to explore real-world security concepts such as encryption, key management, backups, and secure recovery workflows.

This project was developed incrementally as a learning-focused but production-inspired application, emphasizing **security-first design**, **clean architecture**, and **user-friendly UI/UX**.

---

## ✨ Features

### 🔑 Core Security
- **Encryption:** AES encryption/decryption for all stored passwords.
- **Authentication:** Master password authentication and PBKDF2 key derivation with per-vault dynamic (random) salt storage.
- **Vaults:** Per-vault encryption model.
- **Memory:** Secure handling of sensitive data in memory.

### 🔄 Recovery & Resilience
- **Recovery Key:** System for master password reset.
- **Safe Reset Workflow:**
  - Existing encrypted vault is exported as a backup.
  - New master password and recovery key are generated.
  - *Clear acknowledgment that recovery is impossible if both keys are lost (by design).*

### 📦 Import / Export
- **Export:**
  - Encrypted vault backups.
  - Decrypted plaintext backups (with warnings).
- **Import:**
  - Encrypted backups (requires original master password).
  - Decrypted backups for migration and recovery.
  - Duplicate detection during imports.

### ☁️ Cloud Backups (AWS)
- Manual S3 backups.
- Scheduled automated backups.
- Lightweight scheduling config file (`.backupConfigs`).
- Optional enable/disable toggle.
- Hash-based change detection to avoid unnecessary uploads.

### 🖥️ GUI & UX
- Built entirely with **Java Swing**.
- Modern UI styling using **FlatLaf**.
- Non-intrusive status bar for feedback and progress.
- Dialogs reserved for critical confirmations/errors.
- Consistent spacing, typography, and layout tokens.

---

## 🧱 Architecture Overview

- **`PasswordManager18`**: Main application entry point.
- **`managerV2` package**: Refactored core logic.
- **`Entry` class**: Encapsulates individual password records.
- **Separation of Concerns**:
  - UI logic
  - Encryption utilities
  - File handling
  - Cloud backup helpers
  - Vault files store required cryptographic metadata (salt, verification data, recovery key)
- *Designed for future extensibility (database, web version, PKI).*

---

## 🛠️ Technologies Used

| Category | Technology |
| :--- | :--- |
| **Language** | Java |
| **GUI** | Swing |
| **Encryption** | AES, PBKDF2 |
| **Cloud** | AWS S3 |
| **Build Tool** | Maven |
| **Theming** | FlatLaf |
| **Version Control** | Git / GitHub |

---

## 🚀 Getting Started

### Prerequisites
- Java 17+ recommended
- Maven
- *(Optional)* AWS credentials configured for S3 backups

### Run the Application
```bash
mvn clean package
java -jar target/PasswordManager18.jar
```

---

## 🔐 Security Design Notes
> **Note:** Trade-offs were intentionally chosen to prioritize security over convenience.

- No passwords or keys are ever stored in plaintext.
- Encrypted files are meaningless without the master password.
- Recovery keys do not decrypt existing data.
- This mirrors real-world password manager constraints.

---

## 📈 Project Goals
This project was built to:
1. Apply cryptography concepts in a real application.
2. Practice secure GUI application design.
3. Explore backup and disaster recovery workflows.
4. Demonstrate clean refactoring and incremental development.
5. Serve as a portfolio-ready security project.

---

## 🧪 Current Status
- ✅ Backend functionality complete
- ✅ Recovery and backup systems implemented
- 🔧 UI/UX polish ongoing
- 📄 Documentation and cleanup in progress
-    Security improvements in progress

---

## 🧭 Future Enhancements
- Database-backed storage
- Web-based version with authentication
- Public key encryption for sharing
- Enhanced search and table-based retrieval
- Additional cloud providers

---

## ⚠️ Disclaimer
*This project is intended for educational and portfolio purposes.*

*While it follows strong security principles, it has not undergone formal security auditing.*