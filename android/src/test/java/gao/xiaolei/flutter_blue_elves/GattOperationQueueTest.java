package gao.xiaolei.flutter_blue_elves;

import java.util.ArrayList;
import java.util.List;

/**
 * Plain-JVM tests for {@link GattOperationQueue}; the plugin has no test runner,
 * so this is a main(). From android/:
 *
 *   javac -d build/gattq src/main/java/gao/xiaolei/flutter_blue_elves/GattOperationQueue.java \
 *         src/test/java/gao/xiaolei/flutter_blue_elves/GattOperationQueueTest.java
 *   java -ea -cp build/gattq gao.xiaolei.flutter_blue_elves.GattOperationQueueTest
 */
public class GattOperationQueueTest {

    /** Runs delayed tasks only when told to. */
    static final class FakeScheduler implements GattOperationQueue.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();
        final List<Long> delays = new ArrayList<>();

        public void postDelayed(Runnable task, long delayMs) {
            tasks.add(task);
            delays.add(delayMs);
        }

        public void cancel(Runnable task) {
            int i;
            while ((i = tasks.indexOf(task)) >= 0) {
                tasks.remove(i);
                delays.remove(i);
            }
        }

        void runAll() {
            List<Runnable> now = new ArrayList<>(tasks);
            tasks.clear();
            delays.clear();
            for (Runnable r : now) r.run();
        }
    }

    /** Records issue order; the stack answers with whatever is queued in {@code answers}. */
    static final class FakeOp implements GattOperationQueue.Operation {
        final String key;
        final List<String> log;
        final List<GattOperationQueue.IssueResult> answers = new ArrayList<>();
        final boolean coalescable;
        String failedReason;

        FakeOp(String key, List<String> log, boolean coalescable) {
            this.key = key;
            this.log = log;
            this.coalescable = coalescable;
        }

        public String key() { return key; }

        public GattOperationQueue.IssueResult issue() {
            log.add("issue " + key);
            return answers.isEmpty() ? GattOperationQueue.IssueResult.ISSUED : answers.remove(0);
        }

        public void onFailed(String reason) { failedReason = reason; }

        public boolean isCoalescable() { return coalescable; }
    }

    static final List<String> warnings = new ArrayList<>();
    static int passed;

    static GattOperationQueue newQueue(FakeScheduler s) {
        warnings.clear();
        return new GattOperationQueue(s, warnings::add, 3000);
    }

    static void check(boolean condition, String what) {
        if (!condition) throw new AssertionError(what);
    }

    static void onlyOneInFlightUntilCallback() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        q.enqueue(new FakeOp("READ:a", log, true));
        q.enqueue(new FakeOp("READ:b", log, true));
        q.enqueue(new FakeOp("WRITE:c", log, false));
        check(log.equals(List.of("issue READ:a")), "second op issued before first completed: " + log);
        q.complete("READ:a");
        check(log.equals(List.of("issue READ:a", "issue READ:b")), "next op not issued on completion: " + log);
        q.complete("READ:b");
        q.complete("WRITE:c");
        check(log.size() == 3 && q.size() == 0, "queue did not drain: " + log);
    }

    static void staleCallbackDoesNotCompleteNextOp() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        FakeOp a = new FakeOp("READ:a", log, true);
        q.enqueue(a);
        q.enqueue(new FakeOp("READ:b", log, true));
        s.runAll(); // a times out, b is issued
        check("timeout".equals(a.failedReason), "timed-out op not failed");
        q.complete("READ:a"); // a's callback finally turns up
        q.enqueue(new FakeOp("READ:c", log, true));
        check(!log.contains("issue READ:c"), "late callback for a completed b: " + log);
        q.complete("READ:b");
        check(log.contains("issue READ:c"), "c not issued after b: " + log);
    }

    static void duplicateQueuedReadsCoalesce() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        q.enqueue(new FakeOp("READ:a", log, true));
        q.enqueue(new FakeOp("READ:a", log, true)); // a in flight: a fresh read is kept
        q.enqueue(new FakeOp("READ:a", log, true)); // already queued: dropped
        check(q.size() == 2, "expected in-flight + one queued, got " + q.size());
        q.enqueue(new FakeOp("WRITE:w", log, false));
        q.enqueue(new FakeOp("WRITE:w", log, false)); // writes are never coalesced
        check(q.size() == 4, "writes were coalesced: " + q.size());
    }

    static void busyStackIsRetriedThenGivenUp() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        FakeOp a = new FakeOp("READ:a", log, true);
        a.answers.add(GattOperationQueue.IssueResult.BUSY);
        a.answers.add(GattOperationQueue.IssueResult.BUSY);
        q.enqueue(a);
        check(log.size() == 1 && a.failedReason == null, "busy op given up too early");
        s.runAll();
        s.runAll(); // third attempt is accepted
        check(log.size() == 3 && q.size() == 1, "busy op not retried until accepted: " + log);

        FakeOp b = new FakeOp("READ:b", log, true);
        for (int i = 0; i <= GattOperationQueue.MAX_BUSY_RETRIES; i++)
            b.answers.add(GattOperationQueue.IssueResult.BUSY);
        FakeOp c = new FakeOp("READ:c", log, true);
        q.complete("READ:a");
        q.enqueue(b);
        q.enqueue(c);
        for (int i = 0; i < GattOperationQueue.MAX_BUSY_RETRIES; i++) s.runAll();
        check("stack stayed busy".equals(b.failedReason), "b not failed after retries: " + b.failedReason);
        check(log.contains("issue READ:c"), "queue stuck behind a permanently busy op");
    }

    static void failedIssueIsReportedAndSkipped() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        FakeOp a = new FakeOp("READ:missing", log, true);
        a.answers.add(GattOperationQueue.IssueResult.FAILED);
        q.enqueue(a);
        q.enqueue(new FakeOp("READ:b", log, true));
        check("rejected".equals(a.failedReason), "failed op not reported");
        check(log.contains("issue READ:b"), "queue stuck behind a failed op");
        check(warnings.size() == 1, "failure not logged");
    }

    static void clearDropsEverything() {
        FakeScheduler s = new FakeScheduler();
        GattOperationQueue q = newQueue(s);
        List<String> log = new ArrayList<>();
        q.enqueue(new FakeOp("READ:a", log, true));
        q.enqueue(new FakeOp("READ:b", log, true));
        q.clear();
        check(q.size() == 0 && s.tasks.isEmpty(), "clear left work behind");
        q.enqueue(new FakeOp("READ:c", log, true));
        check(log.contains("issue READ:c"), "queue unusable after clear");
    }

    public static void main(String[] args) {
        Runnable[] tests = {
                GattOperationQueueTest::onlyOneInFlightUntilCallback,
                GattOperationQueueTest::staleCallbackDoesNotCompleteNextOp,
                GattOperationQueueTest::duplicateQueuedReadsCoalesce,
                GattOperationQueueTest::busyStackIsRetriedThenGivenUp,
                GattOperationQueueTest::failedIssueIsReportedAndSkipped,
                GattOperationQueueTest::clearDropsEverything,
        };
        for (Runnable t : tests) {
            t.run();
            passed++;
        }
        System.out.println(passed + "/" + tests.length + " GattOperationQueue tests passed");
    }
}
