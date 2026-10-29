package com.nihongoquest.network;

import com.nihongoquest.NihongoQuest;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Packets between server (which owns inventories) and client (which owns the microphone). */
public final class Payloads {
    private Payloads() {}

    public static final int OUTCOME_PASSED = 0;
    public static final int OUTCOME_FAILED = 1;
    public static final int OUTCOME_CANCELLED = 2;

    public static final int ANSWER_PASSED = 0;
    public static final int ANSWER_UNAVAILABLE = 1; // no mic / no model / no word for this item

    /** Server -> client: "say the Japanese word for this item". */
    public record ChallengeStart(int challengeId, String itemId, int timeLimitTicks, int amountAtStake)
            implements CustomPacketPayload {
        public static final Type<ChallengeStart> TYPE = new Type<>(NihongoQuest.id("challenge_start"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeStart> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ChallengeStart::challengeId,
                ByteBufCodecs.STRING_UTF8, ChallengeStart::itemId,
                ByteBufCodecs.VAR_INT, ChallengeStart::timeLimitTicks,
                ByteBufCodecs.VAR_INT, ChallengeStart::amountAtStake,
                ChallengeStart::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Server -> client: final result of a challenge. */
    public record ChallengeResolved(int challengeId, int outcome, int removed) implements CustomPacketPayload {
        public static final Type<ChallengeResolved> TYPE = new Type<>(NihongoQuest.id("challenge_resolved"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeResolved> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ChallengeResolved::challengeId,
                ByteBufCodecs.VAR_INT, ChallengeResolved::outcome,
                ByteBufCodecs.VAR_INT, ChallengeResolved::removed,
                ChallengeResolved::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Client -> server: the player said it, or the quiz can't run on this client. */
    public record ChallengeAnswer(int challengeId, int status) implements CustomPacketPayload {
        public static final Type<ChallengeAnswer> TYPE = new Type<>(NihongoQuest.id("challenge_answer"));
        public static final StreamCodec<RegistryFriendlyByteBuf, ChallengeAnswer> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ChallengeAnswer::challengeId,
                ByteBufCodecs.VAR_INT, ChallengeAnswer::status,
                ChallengeAnswer::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void register() {
        PayloadTypeRegistry.clientboundPlay().register(ChallengeStart.TYPE, ChallengeStart.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(ChallengeResolved.TYPE, ChallengeResolved.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(ChallengeAnswer.TYPE, ChallengeAnswer.CODEC);
    }
}
