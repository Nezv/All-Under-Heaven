package dev.nez.allunderheaven.registry;

import com.google.common.collect.ImmutableSet;

import dev.nez.allunderheaven.AllUnderHeaven;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.trading.TradeSet;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Dragon Keeper — a villager who tends the Dragon-lord Forge and trades
 * the mod's rare goods (star dust for emeralds among them). Both the job-site
 * POI and the profession are code registries; the trades themselves are
 * data-driven JSON ({@code trade_set} / {@code villager_trade}) whose keys are
 * referenced here.
 */
public final class ModVillagers {
    public static final DeferredRegister<PoiType> POI_TYPES =
            DeferredRegister.create(Registries.POINT_OF_INTEREST_TYPE, AllUnderHeaven.MOD_ID);
    public static final DeferredRegister<VillagerProfession> PROFESSIONS =
            DeferredRegister.create(Registries.VILLAGER_PROFESSION, AllUnderHeaven.MOD_ID);

    public static final ResourceKey<PoiType> DRAGON_KEEPER_POI_KEY =
            ResourceKey.create(Registries.POINT_OF_INTEREST_TYPE, AllUnderHeaven.id("dragon_keeper"));

    /** The Dragon-lord Forge is the keeper's workstation. */
    public static final DeferredHolder<PoiType, PoiType> DRAGON_KEEPER_POI =
            POI_TYPES.register("dragon_keeper", () -> new PoiType(
                    ImmutableSet.copyOf(ModBlocks.DRAGONLORD_FORGE.get()
                            .getStateDefinition().getPossibleStates()),
                    1, 1));

    private static ResourceKey<TradeSet> tradeSet(String path) {
        return ResourceKey.create(Registries.TRADE_SET, AllUnderHeaven.id(path));
    }

    public static final DeferredHolder<VillagerProfession, VillagerProfession> DRAGON_KEEPER =
            PROFESSIONS.register("dragon_keeper", () -> new VillagerProfession(
                    Component.translatable("entity.minecraft.villager.dragon_keeper"),
                    (Holder<PoiType> h) -> h.is(DRAGON_KEEPER_POI_KEY),
                    (Holder<PoiType> h) -> h.is(DRAGON_KEEPER_POI_KEY),
                    ImmutableSet.of(),
                    ImmutableSet.of(),
                    SoundEvents.VILLAGER_WORK_ARMORER,
                    tradesByLevel()));

    private static Int2ObjectMap<ResourceKey<TradeSet>> tradesByLevel() {
        Int2ObjectMap<ResourceKey<TradeSet>> map = new Int2ObjectOpenHashMap<>();
        map.put(1, tradeSet("dragon_keeper/level_1"));
        map.put(2, tradeSet("dragon_keeper/level_2"));
        return map;
    }

    private ModVillagers() {
    }
}
