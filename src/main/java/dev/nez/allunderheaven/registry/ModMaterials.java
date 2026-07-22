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

    /** Star-forged: a hair above diamond, meteoric and enchant-hungry. */
    public static final ToolMaterial STAR_FORGED_TOOL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_DIAMOND_TOOL, 1900, 8.5F, 3.5F, 15, STAR_FORGED_INGOTS);

    /** Dragon-lord: the endgame, past netherite. */
    public static final ToolMaterial DRAGONLORD_TOOL = new ToolMaterial(
            BlockTags.INCORRECT_FOR_NETHERITE_TOOL, 2600, 9.5F, 4.5F, 18, DRAGONLORD_INGOTS);

    public static final ArmorMaterial STAR_FORGED_ARMOR = new ArmorMaterial(
            37, defense(3, 6, 8, 3, 11), 12, SoundEvents.ARMOR_EQUIP_DIAMOND,
            2.5F, 0.0F, STAR_FORGED_INGOTS, STAR_FORGED_ASSET);

    public static final ArmorMaterial DRAGONLORD_ARMOR = new ArmorMaterial(
            45, defense(3, 7, 9, 4, 13), 16, SoundEvents.ARMOR_EQUIP_NETHERITE,
            3.5F, 0.15F, DRAGONLORD_INGOTS, DRAGONLORD_ASSET);

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
