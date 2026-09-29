package vd.runtime;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Pure three-way classification of the tasks this owner launched, from ONE inventory snapshot.
 *
 * <ul>
 *   <li>live: still a root on the source display and its identity check passed;</li>
 *   <li>gone: the id no longer appears anywhere in the hierarchy, neither as a root id nor inside
 *       any root's {@code childTaskIds};</li>
 *   <li>escaped: anything else (another display, changed identity, nested elsewhere). An escaped
 *       task is never cleaned up or delivered: callers must fail closed.</li>
 * </ul>
 */
final class OwnedTaskStates {
    final Set<Integer> live;
    final Set<Integer> gone;
    final Set<Integer> escaped;

    private OwnedTaskStates(Set<Integer> live, Set<Integer> gone, Set<Integer> escaped) {
        this.live = Collections.unmodifiableSet(live);
        this.gone = Collections.unmodifiableSet(gone);
        this.escaped = Collections.unmodifiableSet(escaped);
    }

    /**
     * @param ownedIds     ids this owner registered, in registration order
     * @param presentIds   every task id visible in the snapshot (root ids plus all child ids)
     * @param verifiedLive owned ids that are roots on the source display with a passing identity check
     */
    static OwnedTaskStates classify(Iterable<Integer> ownedIds, Set<Integer> presentIds,
            Set<Integer> verifiedLive) {
        if (ownedIds == null || presentIds == null || verifiedLive == null)
            throw new IllegalArgumentException("inventory unknown");
        Set<Integer> live = new LinkedHashSet<Integer>();
        Set<Integer> gone = new LinkedHashSet<Integer>();
        Set<Integer> escaped = new LinkedHashSet<Integer>();
        for (Integer id : ownedIds) {
            if (id == null || id <= 0) throw new IllegalArgumentException("owned id");
            if (verifiedLive.contains(id) && presentIds.contains(id)) live.add(id);
            else if (!presentIds.contains(id)) gone.add(id);
            else escaped.add(id);
        }
        return new OwnedTaskStates(live, gone, escaped);
    }

    /** Device adapter: ids and identity come from the same {@code roots()} snapshot. */
    static OwnedTaskStates read(int source, Map<Integer, Object> roots,
            Map<Integer, OwnerHandoff.Task> owned) throws Exception {
        Set<Integer> present = new LinkedHashSet<Integer>();
        for (Map.Entry<Integer, Object> entry : roots.entrySet()) {
            present.add(entry.getKey());
            // An unreadable child array cannot prove absence, so the whole snapshot is unknown.
            int[] children = (int[]) OwnerHandoff.field(entry.getValue(), "childTaskIds");
            if (children == null) throw new IllegalStateException("child task ids unknown");
            for (int child : children) if (child > 0) present.add(child);
        }
        Set<Integer> verified = new LinkedHashSet<Integer>();
        for (Map.Entry<Integer, OwnerHandoff.Task> entry : owned.entrySet()) {
            Object task = roots.get(entry.getKey());
            if (task == null || OwnerHandoff.number(task, "displayId") != source) continue;
            try {
                entry.getValue().check(task, source);
                verified.add(entry.getKey());
            } catch (Exception ignored) {
                // Present but not provably ours: classified as escaped.
            }
        }
        return classify(owned.keySet(), present, verified);
    }
}
