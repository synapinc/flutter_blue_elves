package gao.xiaolei.flutter_blue_elves;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Serialises GATT operations for one connection.
 *
 * Android allows a single GATT operation in flight per connection. Anything
 * issued while one is pending is rejected (readCharacteristic and friends return
 * false) and nothing is reported back, so overlapping requests were silently
 * lost and only recovered on the caller's next poll - seconds later.
 *
 * Each operation is issued only once the previous one has completed (its
 * BluetoothGattCallback arrived) or timed out. Every method must be called on
 * the same thread; {@link Device} uses the main looper.
 *
 * Deliberately free of Android types so it can be unit tested on a plain JVM.
 */
final class GattOperationQueue {

    /** Outcome of handing an operation to the Android stack. */
    enum IssueResult {
        /** Accepted; its callback will complete it. */
        ISSUED,
        /** Rejected because the stack is busy; worth retrying shortly. */
        BUSY,
        /** Cannot succeed (characteristic missing, not connected); do not retry. */
        FAILED
    }

    interface Operation {
        /**
         * Identifies the callback that completes this operation, e.g. "READ:<uuid>".
         * A completion is only accepted for the operation currently in flight, so a
         * callback that arrives after its operation timed out cannot complete the
         * next one.
         */
        String key();

        IssueResult issue();

        /** Called once if the operation is abandoned: rejected, retried out, or timed out. */
        void onFailed(String reason);

        /** Reads of the same key queued behind each other are coalesced: the answer is identical. */
        boolean isCoalescable();
    }

    interface Scheduler {
        void postDelayed(Runnable task, long delayMs);

        void cancel(Runnable task);
    }

    interface Logger {
        void warn(String message);
    }

    static final long DEFAULT_TIMEOUT_MS = 3000;
    static final long BUSY_RETRY_DELAY_MS = 20;
    static final int MAX_BUSY_RETRIES = 25;

    private final ArrayDeque<Operation> pending = new ArrayDeque<>();
    private final Scheduler scheduler;
    private final Logger logger;
    private final long timeoutMs;

    private Operation inFlight;
    private int busyRetries;
    private boolean retryScheduled;

    private final Runnable timeoutTask = this::onTimeout;
    private final Runnable retryTask = () -> {
        retryScheduled = false;
        drain();
    };

    GattOperationQueue(Scheduler scheduler, Logger logger) {
        this(scheduler, logger, DEFAULT_TIMEOUT_MS);
    }

    GattOperationQueue(Scheduler scheduler, Logger logger, long timeoutMs) {
        this.scheduler = scheduler;
        this.logger = logger;
        this.timeoutMs = timeoutMs;
    }

    void enqueue(Operation op) {
        if (op.isCoalescable() && isQueued(op.key()))
            return;
        pending.addLast(op);
        drain();
    }

    /** Called from the GATT callback for {@code key}. */
    void complete(String key) {
        if (inFlight == null || !inFlight.key().equals(key))
            return;
        scheduler.cancel(timeoutTask);
        inFlight = null;
        drain();
    }

    /** Drops everything, e.g. on disconnect. Pending operations are not reported. */
    void clear() {
        scheduler.cancel(timeoutTask);
        scheduler.cancel(retryTask);
        retryScheduled = false;
        pending.clear();
        inFlight = null;
        busyRetries = 0;
    }

    int size() {
        return pending.size() + (inFlight == null ? 0 : 1);
    }

    /** Only waiting operations count: one already in flight may return an older value. */
    private boolean isQueued(String key) {
        for (Iterator<Operation> it = pending.iterator(); it.hasNext(); )
            if (it.next().key().equals(key))
                return true;
        return false;
    }

    private void drain() {
        while (inFlight == null && !retryScheduled && !pending.isEmpty()) {
            Operation op = pending.peekFirst();
            IssueResult result = op.issue();
            if (result == IssueResult.ISSUED) {
                pending.pollFirst();
                busyRetries = 0;
                inFlight = op;
                scheduler.postDelayed(timeoutTask, timeoutMs);
            } else if (result == IssueResult.BUSY && busyRetries < MAX_BUSY_RETRIES) {
                // Busy with something outside this queue; leave op at the head and try again.
                busyRetries++;
                retryScheduled = true;
                scheduler.postDelayed(retryTask, BUSY_RETRY_DELAY_MS);
            } else {
                pending.pollFirst();
                busyRetries = 0;
                String reason = result == IssueResult.BUSY ? "stack stayed busy" : "rejected";
                logger.warn("GATT " + op.key() + " " + reason);
                op.onFailed(reason);
            }
        }
    }

    private void onTimeout() {
        Operation op = inFlight;
        if (op == null)
            return;
        inFlight = null;
        logger.warn("GATT " + op.key() + " timed out after " + timeoutMs + "ms");
        op.onFailed("timeout");
        drain();
    }
}
