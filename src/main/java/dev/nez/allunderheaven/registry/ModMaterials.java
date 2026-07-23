package dev.nez.allunderheaven.registry;

import java.util.EnumMap;
import java.util.Map;

import dev.nez.allunderheaven.AllUnderHeaven;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAsset;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.resources.ResourceKey;

/**
 * Tool and armour materials for the two mod tiers. Neither is craftable from
 * scratch — Star-forged Steel comes from smelting star dust, and Dragon-lord
 * Steel only from the Dragon-lord Forge — so both sit deliberately above
 * diamond/netherite in the loop rather than beside them.
 *
 * <p>26.2 note: tools are plain {@code Item}s whose {@link Item.Properties}
 * carry the material (via {@code .sword()/.pickaxe()} or the
 * Shovel/Axe/HoeItem ctors); armour is a plain {@code Item} with
 * {@code .humanoidArmor(material, type)}. The worn look is an
 * {@link EquipmentAsset} keyed here and defined by an equipment JSON +
 * layer textures under {@code assets/allunderheaven/}.
 */
public final class ModMaterials {
    // repair-ingredient / material tags (populated in ModItemTagsProvider)
    public static final TagKey<Item> STAR_FORGED_INGOTS =
            TagKey.create(Registries.ITEM, AllUnderHeaven.id("star_forged_ingots"));
    public static final TagKey<Item> DRAGONLORD_INGOTS =
            TagKey.create(Registries.ITEM, AllUnderHeaven.id("dragonlord_ingots"));

    public static final ResourceKey<EquipmentAsset> STAR_FORGED_ASSET =
            ResourceKey.create(EquipmentAssets.ROOT_ID, AllUnderHeaven.id("star_forged"));
    public static final ResourceKey<EquipmentAsset> DRAGONLORD_ASSET =
            ResourceKey.create(EquipmentAssets.ROOT_ID, AllUnderHeaven.id("dragonlord"));

    // Modpack-tuned: both tiers mine at the netherite level (ancient debris and
    // all), and sit a fixed step above netherite so they read as true endgame.
    //   Star-forged = netherite + 2  (attack, mining speed, per-slot defence)
    //   Dragon-lord = netherite + 6
    // Netherite baselines: tool speed 9.0, attack bonus 4.0; armour defence
    // {boots 3, legs 6, chest 8, helm 3, body 11}.

    /** Star-forged: netherite + 2 — meteoric and enchant-hungry. */
    public static final ToolMaterial STAR_FORGED_TOOL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2400, 11.0F, 6.0F, 15, STAR_FORGED_INGOTS);

    /** Dragon-lord: netherite + 6 — the blood-forged apex. */
    public static final ToolMaterial DRAGONLORD_TOOL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 3400, 15.0F, 10.0F, 18, DRAGONLORD_INGOTS);

    public static final ArmorMaterial STAR_FORGED_ARMOR = new ArmorMaterial(
            40, defense(5, 8, 10, 5, 13), 15, SoundEvents.ARMOR_EQUIP_DIAMOND,
            3.0F, 0.10F, STAR_FORGED_INGOTS, STAR_FORGED_ASSET);

    public static final ArmorMaterial DRAGONLORD_ARMOR = new ArmorMaterial(
            50, defense(9, 12, 14, 9, 17), 18, SoundEvents.ARMOR_EQUIP_NETHERITE,
            4.0F, 0.15F, DRAGONLORD_INGOTS, DRAGONLORD_ASSET);

    private static Map<ArmorType, Integer> defense(int boots, int legs, int chest,
            int helm, int body) {
        Map<ArmorType, Integer> map = new EnumMap<>(ArmorType.class);
        map.put(ArmorType.BOOTS, boots);
        map.put(ArmorType.LEGGINGS, legs);
        map.put(ArmorType.CHESTPLATE, chest);
        map.put(ArmorType.HELMET, helm);
        map.put(ArmorType.BODY, body);
        return map;
    }

    private ModMaterials() {
    }
}
