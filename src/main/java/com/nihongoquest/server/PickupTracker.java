package com.nihongoquest.server;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Detects "the player got new items" by comparing inventory totals from tick to tick.
 *
 * This catches every way of obtaining items (picking up drops, crafting, smelting, taking
 * from chests, trading, /give) without hooking each one separately. To avoid counting an
 * item that was just moved out and back in (rearranging, a crafting grid), recent losses
 * are remembered for a short cooldown and cancel out matching gains.
 *
 * Plain Java on purpose (item IDs as strings) so it can be unit-tested outside Minecraft.
 */
public final class PickupTracker {
    private Map<String, Integer> last;
    private final Map<String, Deque<long[]>> recentLosses = new HashMap<>(); // {amount, expiresAtTick}

    /**
     * @param totals   item ID -> total count the player holds right now
     * @param tick     current server tick
     * @param cooldown ticks a loss is remembered for
     * @return item ID -> genuinely new amount gained since the last call (empty on first call)
     */
    public Map<String, Integer> update(Map<String, Integer> totals, long tick, int cooldown) {
        Map<String, Integer> gains = new LinkedHashMap<>();
        if (last == null) {
            last = new HashMap<>(totals);
            return gains;
        }
        expire(tick);

        Set<String> ids = new HashSet<>(last.keySet());
        ids.addAll(totals.keySet());
        for (String id : ids) {
            int before = last.getOrDefault(id, 0);
            int now = totals.getOrDefault(id, 0);
            int delta = now - before;
            if (delta < 0 && cooldown > 0) {
                recentLosses.computeIfAbsent(id, k -> new ArrayDeque<>()).addLast(new long[]{-delta, tick + cooldown});
            } else if (delta > 0) {
                int genuine = consumeLosses(id, delta);
                if (genuine > 0) gains.put(id, genuine);
            }
        }
        last = new HashMap<>(totals);
        return gains;
    }

    /** Accept the current totals without counting anything (e.g. after the mod removed items). */
    public void resync(Map<String, Integer> totals, String changedItem) {
        last = new HashMap<>(totals);
        if (changedItem != null) recentLosses.remove(changedItem);
    }

    private int consumeLosses(String id, int gained) {
        Deque<long[]> q = recentLosses.get(id);
        while (q != null && !q.isEmpty() && gained > 0) {
            long[] loss = q.peekFirst();
            long used = Math.min(loss[0], gained);
            loss[0] -= used;
            gained -= (int) used;
            if (loss[0] <= 0) q.pollFirst();
        }
        if (q != null && q.isEmpty()) recentLosses.remove(id);
        return gained;
    }

    private void expire(long tick) {
        recentLosses.values().forEach(q -> q.removeIf(l -> l[1] < tick));
        recentLosses.values().removeIf(Deque::isEmpty);
    }
}
