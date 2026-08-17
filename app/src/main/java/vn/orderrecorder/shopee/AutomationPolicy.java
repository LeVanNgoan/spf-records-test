package vn.orderrecorder.shopee;

/**
 * v2.0.3 keeps the proven v0.2.5 automation engine.
 * The proven stable core is retained, with one production safety fuse around it:
 * maximum auto age, maximum attempts, and a per-attempt timeout prevent an unrecoverable
 * order from being clicked forever after its phone number is no longer available.
 */
public final class AutomationPolicy {
    public static final long SOUND_GRACE_MS = 1000L;
    public static final long USER_IDLE_MS = 0L;
    public static final long WORKER_TICK_MS = 0L;
    public static final long RETRY_DELAY_MS = 2800L;
    public static final long MAX_AUTO_AGE_MS = 8L * 60L * 1000L;
    public static final long STALE_PROCESSING_MS = 30_000L;
    public static final long PHONE_CAPTURE_MS = 15_000L;
    public static final long EXCLUSIVE_LOCK_MAX_MS = 0L;
    public static final long RAPID_SCAN_MS = 0L;
    public static final long RAPID_SCAN_TICK_MS = 0L;
    public static final int MAX_ATTEMPTS = 3;
    private AutomationPolicy() {}
}
