package managerV2;

import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.concurrent.*;

/**
 * BackupScheduler
 * ---------------
 * Runs scheduled backups on a single background thread without UI:
 *  - On {@link #startAfterAuth()} it optionally performs a catch-up run if a scheduled time
 *    has elapsed since the last recorded backup AND the vault content hash changed.
 *  - After each run, it computes and schedules the next exact occurrence (daily/weekly/monthly @ hour).
 *  - At each tick it calls {@code CloudBackupUtils.performBackupIfChangedNow()} (silent, no dialogs).
 *
 * Threading:
 *  - Uses a {@link ScheduledExecutorService} with one daemon thread so the app can exit cleanly.
 *  - All work here is off the EDT; UI notification (if any) is handled elsewhere (StatusBar).
 */
public final class BackupScheduler {

    public enum ScheduleType { DAILY, WEEKLY, MONTHLY }

    private final ScheduleType type;
    private final int hourLocal;         // 0-23 local wall clock hour
    private final DayOfWeek dayOfWeek;   // for WEEKLY
    private final int dayOfMonth;        // for MONTHLY (1-28 recommended)

    /** Pool for delayed/scheduled tasks (single thread, fixed). */
    private final ScheduledExecutorService exec; // Schedules tasks for future execution on a timer

    /** Handle to the scheduled “next tick” task so we can cancel/replace it. */
    private ScheduledFuture<?> nextTask; // ScheduledFuture<?> = result/handle of a scheduled job; <?> = unknown (no return type)

    public BackupScheduler(ScheduleType type, int hourLocal, DayOfWeek dayOfWeek, int dayOfMonth) {
        this.type = Objects.requireNonNull(type);
        this.hourLocal = Math.max(0, Math.min(23, hourLocal));
        this.dayOfWeek = (dayOfWeek == null ? DayOfWeek.SUNDAY : dayOfWeek);
        this.dayOfMonth = Math.max(1, Math.min(28, dayOfMonth));

        // Create a single-thread scheduler with a custom thread factory naming the thread and marking it daemon.
        this.exec = Executors.newSingleThreadScheduledExecutor(r -> { // ThreadFactory: builds threads for the scheduler
            Thread t = new Thread(r, "VaultBackupScheduler");
            t.setDaemon(true); // daemon = won’t keep the JVM alive on app exit
            return t;
        });
    }

    /** Call once after user is authenticated / vault is ready. */
    public void startAfterAuth() {
        // Kick work off-thread immediately: perform catch-up check, then schedule next.
        exec.execute(this::catchUpIfOverdueThenScheduleNext);
    }

    /** Call on app shutdown. (If not invoked explicitly, daemon thread won’t block exit.) */
    public void shutdown() {
        if (nextTask != null) nextTask.cancel(false); // cancel waiting task; do not interrupt if already running
        exec.shutdownNow(); // stop scheduler (best-effort)
    }

    // ---------- internals ----------

    /** If a scheduled time has passed since the last backup, try an immediate run, then enqueue the next tick. */
    private void catchUpIfOverdueThenScheduleNext() {
        try {
            // 1) Catch-up if overdue AND changed
            boolean due = isDueNow();
            if (due) {
                // Silent, off-EDT attempt; uploads only if content hash differs
                boolean uploaded = CloudBackupUtils.performBackupIfChangedNow();
                // Optional: log uploaded
                // System.out.println("Catch-up uploaded? " + uploaded);
            } else {
                // No-op if not yet due; continue to scheduling the proper next tick
            }
        } finally {
            scheduleNextTick();
        }
    }

    /** Compute delay to the next scheduled occurrence and schedule a one-shot run; that run re-schedules itself. */
    private void scheduleNextTick() {
        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime next = computeNextOccurrence(now);
        long delayMs = Duration.between(now, next).toMillis();
        if (delayMs < 0) delayMs = 0; // guard clock skews

        if (nextTask != null) nextTask.cancel(false); // replace prior task if any
        nextTask = exec.schedule(() -> {
            try {
                // Attempt backup at the scheduled time (no-op if unchanged)
                boolean uploaded = CloudBackupUtils.performBackupIfChangedNow();

            } finally {
                // Always schedule the subsequent occurrence
                scheduleNextTick();
            }
        }, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Decide whether a catch-up run is warranted at app start (or reschedule). */
    private boolean isDueNow() {
        Instant last = CloudBackupUtils.getLastBackupAtUtcOrNull();
        // If never backed up, consider due for an initial attempt
        if (last == null) return true;

        ZonedDateTime now = ZonedDateTime.now();
        ZonedDateTime lastOcc = computeLastOccurrenceOnOrBefore(now);
        ZonedDateTime lastBackupLocal = ZonedDateTime.ofInstant(last, ZoneId.systemDefault());

        // Due if the last scheduled occurrence happened AFTER the last backup timestamp
        return lastBackupLocal.isBefore(lastOcc);
    }

    /**
     * Compute the most recent scheduled occurrence at or before “now” based on the cadence.
     * switch-case selects the rule set for each ScheduleType.
     */
    private ZonedDateTime computeLastOccurrenceOnOrBefore(ZonedDateTime now) {
        switch (type) { // Choose logic per cadence and clamp time to hourLocal (minutes/seconds zeroed)
            case DAILY: {
                ZonedDateTime today = now.withHour(hourLocal).withMinute(0).withSecond(0).withNano(0);
                return now.isBefore(today) ? today.minusDays(1) : today;
            }
            case WEEKLY: {
                ZonedDateTime target = now.with(TemporalAdjusters.previousOrSame(dayOfWeek))
                        .withHour(hourLocal).withMinute(0).withSecond(0).withNano(0);
                return target.isAfter(now) ? target.minusWeeks(1) : target;
            }
            case MONTHLY: {
                int dom = Math.max(1, Math.min(dayOfMonth, 28));
                // Clamp to month length (28 chosen earlier to avoid Feb pitfalls)
                int actualDom = Math.min(dom, now.toLocalDate().lengthOfMonth());
                ZonedDateTime thisMonth = now.withDayOfMonth(actualDom)
                        .withHour(hourLocal).withMinute(0).withSecond(0).withNano(0);
                return now.isBefore(thisMonth) ? thisMonth.minusMonths(1) : thisMonth;
            }
            default: throw new IllegalStateException("Unsupported schedule type");
        }
    }

    /** Next scheduled occurrence strictly after “now” derived from the last-on-or-before occurrence. */
    private ZonedDateTime computeNextOccurrence(ZonedDateTime now) {
        switch (type) {
            case DAILY:   return computeLastOccurrenceOnOrBefore(now).plusDays(1);
            case WEEKLY:  return computeLastOccurrenceOnOrBefore(now).plusWeeks(1);
            case MONTHLY: return computeLastOccurrenceOnOrBefore(now).plusMonths(1);
            default: throw new IllegalStateException("Unsupported schedule type");
        }
    }
    
    /** Debug/testing helper: run one scheduled cycle in N seconds, then continue on the normal schedule. */
    public void runOnceInSeconds(int seconds) {
        long delayMs = Math.max(0, seconds) * 1000L;
        exec.schedule(this::catchUpIfOverdueThenScheduleNext, delayMs, TimeUnit.MILLISECONDS);
    }
}
