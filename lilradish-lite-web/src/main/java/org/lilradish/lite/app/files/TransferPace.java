package org.lilradish.lite.app.files;

public final class TransferPace {

    public static final long BYTES_PER_SECOND = 256L * 1024L;
    public static final long GRACE_NANOS = 30_000_000_000L;

    private TransferPace() {}

    public static boolean tooSlow(long startedAtNanos, long transferred, long nowNanos) {
        long seconds = transferred / BYTES_PER_SECOND;
        if (seconds >= (Long.MAX_VALUE - GRACE_NANOS) / 1_000_000_000L) {
            return false;
        }
        long remainder = transferred % BYTES_PER_SECOND;
        long allowed = GRACE_NANOS + seconds * 1_000_000_000L + remainder * 1_000_000_000L / BYTES_PER_SECOND;
        return nowNanos - startedAtNanos > allowed;
    }
}
