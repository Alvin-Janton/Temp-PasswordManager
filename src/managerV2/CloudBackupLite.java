package managerV2;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * CloudBackupLite
 * ---------------
 * Minimal, beginner-friendly S3 helper used by the UI layer to move **already-encrypted** vault files.
 * What it does:
 *  - Builds a short-lived S3Client using the default AWS credential chain (env vars, shared config/credentials, SSO, etc.).
 *  - Uploads encrypted files with timestamped names (keeps your local filename in the S3 key by design).
 *  - Lists backups under an optional "folder" prefix and sorts newest → oldest.
 *  - Downloads either the newest backup or a specific key.
 *  - Prunes older backups keeping only the latest N for a given original filename.
 *
 * What it does NOT do:
 *  - No server-side encryption, presigning, or metadata management. Your app handles crypto; S3 stores opaque bytes.
 *  - No pagination for extremely large listings (OK for personal backups; see NOTE in listBackups()).
 *
 * Lifecycle:
 *  - Construct with {@link CloudBackupLite#builder()}, use it, then let try-with-resources call {@link #close()}.
 */
public class CloudBackupLite implements AutoCloseable {

    private final S3Client s3;
    private final String bucket;
    private final String prefix; // optional, like "backups/desktop1"

    private CloudBackupLite(S3Client s3, String bucket, String prefix) {
        this.s3 = s3;
        this.bucket = bucket;
        this.prefix = prefix == null ? "" : trimSlashes(prefix);
    }

    // ---------- Builder ----------

    /** Entry point to construct a client with a fluent API (set bucket/region/prefix, then build). */
    public static Builder builder() { return new Builder(); } /** Returns a new Builder so callers can chain .bucket(...).region(...).prefix(...).build(). */
    
    /**
     * Builder
     * -------
     * Small helper to configure and create a {@link CloudBackupLite} instance.
     * Under the hood it builds an {@link S3Client} with:
     *   - Region: required (e.g., "us-east-1")
     *   - Credentials: {@link DefaultCredentialsProvider} (tries env vars, shared config, EC2/ECS role, SSO, etc.)
     *   - HTTP client: Apache implementation (sane defaults)
     */
    public static final class Builder {
        private String bucket;
        private String prefix = "";
        private Region region;

        /** Set your S3 bucket name (must already exist and be accessible with your credentials). */
        public Builder bucket(String bucket) { this.bucket = Objects.requireNonNull(bucket); return this; }
        
        /** Optional "folder" inside the bucket (stored as a key prefix like "backups/desktop1/"). */
        public Builder prefix(String prefix) { this.prefix = prefix == null ? "" : prefix; return this; }
        
        /** Region string (e.g., "us-east-1"). The SDK wraps it in a {@link Region} object. */
        public Builder region(String region) { this.region = Region.of(Objects.requireNonNull(region)); return this; }

        /** Create the fully configured CloudBackupLite with a short-lived S3 client. */
        public CloudBackupLite build() {
            if (bucket == null || bucket.isBlank()) throw new IllegalArgumentException("bucket is required");
            if (region == null) throw new IllegalArgumentException("region is required");

            var http = ApacheHttpClient.builder().build(); // default Apache HTTP client
            var s3cfg = S3Configuration.builder().build(); // default S3 config

            S3Client s3 = S3Client.builder()
                    .region(region)
                    .credentialsProvider(DefaultCredentialsProvider.create()) // uses the standard AWS provider chain
                    .serviceConfiguration(s3cfg)
                    .httpClient(http)
                    .build();

            return new CloudBackupLite(s3, bucket, prefix);
        }
    }

    // ---------- Types ----------
    /** Tiny description of an S3 object we care about (key, last modified time, size). */
    public static final class BackupObject {
        public final String key;
        public final Instant lastModified;
        public final long size;
        public BackupObject(String key, Instant lastModified, long size) {
            this.key = key; this.lastModified = lastModified; this.size = size;
        }
    }

    /** Download result: the S3 key and the ciphertext bytes (your app decrypts later). */
    public static final class DownloadedBackup {
        public final String key;
        public final byte[] data;
        public DownloadedBackup(String key, byte[] data) { this.key = key; this.data = data; }
    }

    // ---------- Public API ----------

    /** Quick health check that your bucket is reachable with current creds/region. */
    public boolean verifyBucketAccess() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return true;
        } catch (S3Exception e) {
            return false; // false on 403/404/region mismatch/etc.
        }
    }


    /**
     * Upload an already-encrypted file from disk, preserving its local filename in the S3 key,
     * and appending a timestamp so each upload is unique.
     *
     * @param file            path to the encrypted file on disk
     * @param timestampFirst  if true: "<ts>__<base><ext>", else: "<base>__<ts><ext>"
     * @return full S3 key (including prefix if set)
     */
    public String uploadEncrypted(Path file, boolean timestampFirst) {
        Objects.requireNonNull(file, "file");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("Not a file: " + file);
        }

        String original = file.getFileName().toString();
        String base = sanitize(baseOf(original)); // base name without extension; sanitize to keep S3-friendly characters
        String ext  = extOf(original);            // extension including the leading '.', or "" if none

        // If no extension was provided, use .pm.enc (matches your convention)
        if (ext.isEmpty()) ext = ".pm.enc";

        // Yes—this is a nested ternary. It chooses which side of the filename the timestamp goes on.
        String keyName = timestampFirst
                ? ts() + "__" + (base.isEmpty() ? "backup" : base) + ext
                : (base.isEmpty() ? "backup" : base) + "__" + ts() + ext;

        String key = withPrefix(keyName);

        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/octet-stream") // generic binary; encryption is client-side
                .build();

        s3.putObject(req, RequestBody.fromFile(file)); // uploads the file bytes to S3
        return key;
    }


    /**
     * List backups under the configured prefix, newest first.
     * NOTE: For very large buckets, ListObjectsV2 can paginate. This method fetches only the first page
     * which is typically fine for a personal "backups" prefix. If you ever store thousands of files,
     * consider looping with ContinuationTokens.
     */
    public List<BackupObject> listBackups() { 
        ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                .bucket(bucket)
                .prefix(effectivePrefix())
                .build();

        ListObjectsV2Response resp = s3.listObjectsV2(listReq);
        if (resp.contents() == null) return List.of();

        return resp.contents().stream()
                .filter(o -> !o.key().endsWith("/")) // ignore "directory markers"
                .sorted(Comparator.comparing(S3Object::lastModified).reversed())
                .map(o -> new BackupObject(o.key(), o.lastModified(), o.size()))
                .collect(Collectors.toList());
    }

    /** Download the newest backup object. Throws if none exist. */
    public DownloadedBackup downloadLatest() {
        List<BackupObject> items = listBackups();
        if (items.isEmpty()) throw new NoSuchElementException("No backups found under prefix: " + effectivePrefix());
        String key = items.get(0).key;

        GetObjectRequest get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();

        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(get); // convenience: downloads whole object into memory
        return new DownloadedBackup(key, bytes.asByteArray());
    }
    
    // In managerV2.CloudBackupLite
    /** Download a specific backup by its exact S3 key (as returned by listBackups or upload). */
    public DownloadedBackup downloadByKey(String key) {
        Objects.requireNonNull(key, "key");
        var get = GetObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build();
        ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(get);
        return new DownloadedBackup(key, bytes.asByteArray());
    }


    // ---------- Helpers ----------

    /** Ensure prefix ends with a single slash when present; empty string means "bucket root". */
    private String effectivePrefix() { return prefix.isBlank() ? "" : prefix + "/"; }
    
    /** Strip leading/trailing slashes so we don't produce double slashes in keys. */
    private static String trimSlashes(String s) { return s.replaceAll("^/+|/+$", ""); }

    /** Close the underlying S3Client (called automatically in try-with-resources). */
    @Override public void close() {
        try { if (s3 != null) s3.close(); } catch (Exception ignored) {}
    }
    
    /** Current timestamp formatted for filenames (yyyy-MM-dd_HH-mm-ss). */
    private static String ts() {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
                .format(ZonedDateTime.now());
    }

    /**
     * Make a string S3-key-friendly:
     *   keep letters, digits, dot, underscore, hyphen; replace anything else with '-'.
     *   e.g., "my vault (home).pm.enc" -> "my-vault--home-.pm.enc"
     */
    private static String sanitize(String s) {
        // Keep letters, digits, dot, underscore, hyphen; replace others with '-'
        return s == null ? "" : s.replaceAll("[^A-Za-z0-9._-]", "-");
    }
    
    /**
     * Return the extension (including '.') or "" if none.
     * Visual:
     *   "vault.pm.enc" -> ".enc"
     *   "vault.txt"    -> ".txt"
     *   "vault"        -> ""
     */
    private static String extOf(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0 && i < name.length() - 1) ? name.substring(i) : "";
    }

    /**
     * Return the base name without the final extension.
     * Visual:
     *   "vault.pm.enc" -> "vault.pm"
     *   "vault.txt"    -> "vault"
     *   "vault"        -> "vault"
     */
    private static String baseOf(String name) {
        int i = name.lastIndexOf('.');
        return (i > 0) ? name.substring(0, i) : name;
    }

    /** Prepend the configured prefix (if any). */
    private String withPrefix(String name) {
        String p = effectivePrefix();
        return p.isBlank() ? name : p + name;
    }
    
    /**
     * Prune old backups that belong to the given original file name, keeping only the latest N.
     * Matching is based on the naming produced by uploadEncrypted(file, timestampFirst):
     *   - timestampFirst = false:  <base>__<timestamp><ext>
     *   - timestampFirst = true:   <timestamp>__<base><ext>
     *
     * @param originalFileName the local file name (e.g., "passwords.txt")
     * @param timestampFirst   should match how you uploaded
     * @param retainLatestN    how many newest backups to keep (minimum 1)
     * @return number of deleted S3 objects
     */
    public int pruneOldBackups(String originalFileName, boolean timestampFirst, int retainLatestN) {
        Objects.requireNonNull(originalFileName, "originalFileName");
        if (retainLatestN < 1) retainLatestN = 1;

        String base = sanitize(baseOf(originalFileName));
        String ext  = extOf(originalFileName);
        if (ext.isEmpty()) ext = ".pm.enc";

        // Collect matching objects, newest first (listBackups already sorts newest first)
        List<BackupObject> all = listBackups();
        List<BackupObject> mine = new ArrayList<>();
        for (BackupObject o : all) {
            String name = keyFileName(o.key);
            boolean matches = timestampFirst
                    ? (name.endsWith(base + ext) && name.contains("__"))
                    : (name.startsWith(base + "__") && name.endsWith(ext));
            if (matches) mine.add(o);
        }

        if (mine.size() <= retainLatestN) return 0;

        // Everything after the first retainLatestN is old and should be deleted
        List<BackupObject> toDelete = mine.subList(retainLatestN, mine.size());

        // Batch delete (S3 allows up to 1000 per request)
        List<ObjectIdentifier> ids = new ArrayList<>(toDelete.size());
        for (BackupObject o : toDelete) {
            ids.add(ObjectIdentifier.builder().key(o.key).build());
        }
        int deleted = 0;
        if (!ids.isEmpty()) {
            DeleteObjectsRequest delReq = DeleteObjectsRequest.builder()
                    .bucket(bucket)
                    .delete(Delete.builder().objects(ids).build())
                    .build();
            DeleteObjectsResponse delResp = s3.deleteObjects(delReq);
            if (delResp != null && delResp.deleted() != null) {
                deleted = delResp.deleted().size();
            }
        }

        return deleted;
    }

    /**
     * Return just the last path segment (filename) for a given S3 key.
     * Visual:
     *   "backups/desktop1/vault__2025-11-06_14-22-01.pm.enc" -> "vault__2025-11-06_14-22-01.pm.enc"
     *   "vault.pm.enc" -> "vault.pm.enc"
     */
    private static String keyFileName(String key) {
        int i = key.lastIndexOf('/');
        return (i >= 0) ? key.substring(i + 1) : key;
    }

}
