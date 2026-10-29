package com.nihongoquest.server;

import com.nihongoquest.NihongoQuest;
import com.nihongoquest.config.ModConfig;
import com.nihongoquest.network.Payloads;
import com.nihongoquest.network.Payloads.ChallengeAnswer;
import com.nihongoquest.network.Payloads.ChallengeResolved;
import com.nihongoquest.network.Payloads.ChallengeStart;
import com.nihongoquest.server.PlayerDataStore.PlayerData;
import com.nihongoquest.vocab.Vocabulary;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side game logic. The server is the authority on inventories and on the timer, so
 * removing items works the same in singleplayer and on multiplayer servers (the server needs
 * the mod too). The client only reports "the player said it correctly".
 */
public final class ChallengeManager {
    private ChallengeManager() {}

    /** Extra time the server waits after the client's countdown ends, for network lag. */
    private static final int GRACE_TICKS = 40;
    private static final int AUTOSAVE_TICKS = 20 * 60 * 5;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static PlayerDataStore store;
    private static long tick;
    private static int nextChallengeId = 1;

    private static final class Session {
        final PlayerData data;
        final PickupTracker tracker = new PickupTracker();
        final Deque<Pending> queue = new ArrayDeque<>();
        Active active;

        Session(PlayerData data) {
            this.data = data;
        }
    }

    private static final class Pending {
        final String itemId;
        int stake;

        Pending(String itemId, int stake) {
            this.itemId = itemId;
            this.stake = stake;
        }
    }

    private static final class Active {
        final int id;
        final String itemId;
        int stake;
        int ticksLeft;

        Active(int id, String itemId, int stake, int ticksLeft) {
            this.id = id;
            this.itemId = itemId;
            this.stake = stake;
            this.ticksLeft = ticksLeft;
        }
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            ModConfig.load();
            Vocabulary.load();
            store = new PlayerDataStore(server.getWorldPath(LevelResource.ROOT).resolve(NihongoQuest.MOD_ID));
            tick = 0;
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            saveAll();
            SESSIONS.clear();
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> join(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> leave(handler.getPlayer()));
        ServerTickEvents.END_SERVER_TICK.register(ChallengeManager::onServerTick);
        ServerPlayNetworking.registerGlobalReceiver(ChallengeAnswer.TYPE,
                (payload, context) -> onAnswer(context.player(), payload));
    }

    // ------------------------------------------------------------------ players

    private static void join(ServerPlayer player) {
        if (store == null) return;
        SESSIONS.put(player.getUUID(), new Session(store.load(player.getUUID())));
    }

    private static void leave(ServerPlayer player) {
        Session s = SESSIONS.remove(player.getUUID());
        // A quiz in progress when you log out is simply dropped (no penalty).
        if (s != null && store != null) store.save(player.getUUID(), s.data);
    }

    private static void saveAll() {
        if (store == null) return;
        SESSIONS.forEach((uuid, s) -> store.save(uuid, s.data));
    }

    // ------------------------------------------------------------------ ticking

    private static void onServerTick(MinecraftServer server) {
        if (store == null) return;
        tick++;
        ModConfig cfg = ModConfig.get();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Session s = SESSIONS.get(player.getUUID());
            if (s == null) {
                join(player);
                continue;
            }

            Map<String, Integer> totals = countItems(player);
            Map<String, Integer> gains = s.tracker.update(totals, tick, cfg.recountCooldownTicks());

            boolean quizzable = !(cfg.ignoreCreativeMode && (player.isCreative() || player.isSpectator()))
                    && ServerPlayNetworking.canSend(player, ChallengeStart.TYPE);
            if (quizzable) {
                gains.forEach((itemId, amount) -> onGain(s, itemId, amount, cfg));
            }

            if (s.active != null) {
                if (--s.active.ticksLeft <= 0) fail(player, s);
            }
            if (s.active == null && !s.queue.isEmpty()) {
                if (quizzable) start(player, s, cfg);
                else s.queue.clear();
            }
        }

        if (tick % AUTOSAVE_TICKS == 0) saveAll();
    }

    private static void onGain(Session s, String itemId, int amount, ModConfig cfg) {
        // New copies of an item that's already being quizzed are also at stake.
        boolean alreadyQuizzing = false;
        if (s.active != null && s.active.itemId.equals(itemId)) {
            s.active.stake += amount;
            alreadyQuizzing = true;
        }
        for (Pending p : s.queue) {
            if (p.itemId.equals(itemId)) {
                p.stake += amount;
                alreadyQuizzing = true;
            }
        }

        int before = s.data.pickups.getOrDefault(itemId, 0);
        int after = before + (cfg.countEveryItem() ? amount : 1);
        s.data.pickups.put(itemId, after);

        int every = cfg.quizEveryNPickups();
        boolean due = (after / every) > (before / every) || (cfg.quizOnFirstPickup && before == 0);
        if (!due || alreadyQuizzing) return;
        if (cfg.isExcluded(itemId)) return;
        if (cfg.onlyItemsWithBuiltInWords && !Vocabulary.has(itemId, cfg.includeColorWords)) return;

        s.queue.addLast(new Pending(itemId, amount));
    }

    private static void start(ServerPlayer player, Session s, ModConfig cfg) {
        Pending p = s.queue.pollFirst();
        if (p == null) return;
        int ticks = cfg.timeLimitSeconds() * 20;
        s.active = new Active(nextChallengeId++, p.itemId, p.stake, ticks + GRACE_TICKS);
        ServerPlayNetworking.send(player, new ChallengeStart(s.active.id, p.itemId, ticks, p.stake));
    }

    // ------------------------------------------------------------------ results

    private static void onAnswer(ServerPlayer player, ChallengeAnswer answer) {
        Session s = SESSIONS.get(player.getUUID());
        if (s == null || s.active == null || s.active.id != answer.challengeId()) return;

        if (answer.status() == Payloads.ANSWER_PASSED) {
            s.data.correct.merge(s.active.itemId, 1, Integer::sum);
            ServerPlayNetworking.send(player, new ChallengeResolved(s.active.id, Payloads.OUTCOME_PASSED, 0));
            s.active = null;
        } else if (ModConfig.get().penalizeWhenSpeechUnavailable) {
            fail(player, s);
        } else {
            ServerPlayNetworking.send(player, new ChallengeResolved(s.active.id, Payloads.OUTCOME_CANCELLED, 0));
            s.active = null;
        }
    }

    private static void fail(ServerPlayer player, Session s) {
        Active a = s.active;
        s.active = null;
        int removed = removeItems(player, a.itemId, a.stake);
        s.data.failed.merge(a.itemId, 1, Integer::sum);
        // Don't let the removal register as "recently lost" items.
        s.tracker.resync(countItems(player), a.itemId);
        ServerPlayNetworking.send(player, new ChallengeResolved(a.id, Payloads.OUTCOME_FAILED, removed));
    }

    // ------------------------------------------------------------------ inventory

    private static String idOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    /** Totals across the inventory (incl. armor/offhand) and the stack held on the mouse cursor. */
    private static Map<String, Integer> countItems(ServerPlayer player) {
        Map<String, Integer> totals = new HashMap<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty()) totals.merge(idOf(stack), stack.getCount(), Integer::sum);
        }
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty()) totals.merge(idOf(carried), carried.getCount(), Integer::sum);
        return totals;
    }

    /** Removes up to {@code amount} of the item. Returns how many were actually removed. */
    private static int removeItems(ServerPlayer player, String itemId, int amount) {
        int left = amount;
        ItemStack carried = player.containerMenu.getCarried();
        if (!carried.isEmpty() && idOf(carried).equals(itemId)) {
            int take = Math.min(left, carried.getCount());
            carried.shrink(take);
            left -= take;
        }
        Inventory inv = player.getInventory();
        // Take from the main inventory/hotbar first; worn armor and the offhand only as a last resort.
        int size = inv.getContainerSize();
        int mainSize = Math.min(Inventory.INVENTORY_SIZE, size);
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            int from = pass == 0 ? 0 : mainSize;
            int to = pass == 0 ? mainSize : size;
            for (int i = from; i < to && left > 0; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || !idOf(stack).equals(itemId)) continue;
                int take = Math.min(left, stack.getCount());
                stack.shrink(take);
                left -= take;
            }
        }
        inv.setChanged();
        player.containerMenu.broadcastChanges();
        return amount - left;
    }
}
