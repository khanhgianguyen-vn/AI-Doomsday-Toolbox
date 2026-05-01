package com.example.llamadroid.tama.data

import android.content.Context
import androidx.annotation.StringRes
import com.example.llamadroid.R

enum class TamaPotionKind {
    STAGE,
    SPECIES,
    GROWTH_LOCK,
    GROWTH_UNLOCK,
    HEALING
}

enum class TamaPotionVendor {
    ALCHEMIST,
    HOSPITAL
}

data class TamaPotionDefinition(
    val id: String,
    val kind: TamaPotionKind,
    val vendor: TamaPotionVendor,
    val assetPath: String,
    @StringRes val titleRes: Int,
    @StringRes val descriptionRes: Int,
    val price: Int,
    val targetStage: GrowthStage? = null,
    val targetSpecies: PetSpeciesLine? = null,
    val healAmount: Int? = null
) {
    fun toInventoryItem(context: Context): InventoryItem {
        return InventoryItem(
            id = id,
            name = context.getString(titleRes),
            type = ItemType.POTION,
            quantity = 1
        )
    }
}

object TamaPotionCatalog {
    const val BABY_STAGE_ID = "potion_stage_baby"
    const val CHILD_STAGE_ID = "potion_stage_child"
    const val TEEN_STAGE_ID = "potion_stage_teen"
    const val ADULT_STAGE_ID = "potion_stage_adult"
    const val SENIOR_STAGE_ID = "potion_stage_senior"
    const val DRAGON_SPECIES_ID = "potion_species_dragon"
    const val KITSUNE_SPECIES_ID = "potion_species_kitsune"
    const val UNICORN_SPECIES_ID = "potion_species_unicorn"
    const val GROWTH_LOCK_ID = "potion_growth_lock"
    const val GROWTH_UNLOCK_ID = "potion_growth_unlock"
    const val HEAL_10_ID = "potion_heal_10"
    const val HEAL_20_ID = "potion_heal_20"
    const val HEAL_40_ID = "potion_heal_40"
    const val HEAL_60_ID = "potion_heal_60"
    const val HEAL_80_ID = "potion_heal_80"
    const val HEAL_100_ID = "potion_heal_100"

    val potions: List<TamaPotionDefinition> = listOf(
        TamaPotionDefinition(
            id = BABY_STAGE_ID,
            kind = TamaPotionKind.STAGE,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/stage_baby.png",
            titleRes = R.string.tama_potion_stage_baby_title,
            descriptionRes = R.string.tama_potion_stage_baby_desc,
            price = 5000,
            targetStage = GrowthStage.BABY
        ),
        TamaPotionDefinition(
            id = CHILD_STAGE_ID,
            kind = TamaPotionKind.STAGE,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/stage_child.png",
            titleRes = R.string.tama_potion_stage_child_title,
            descriptionRes = R.string.tama_potion_stage_child_desc,
            price = 5000,
            targetStage = GrowthStage.CHILD
        ),
        TamaPotionDefinition(
            id = TEEN_STAGE_ID,
            kind = TamaPotionKind.STAGE,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/stage_teen.png",
            titleRes = R.string.tama_potion_stage_teen_title,
            descriptionRes = R.string.tama_potion_stage_teen_desc,
            price = 5000,
            targetStage = GrowthStage.TEEN
        ),
        TamaPotionDefinition(
            id = ADULT_STAGE_ID,
            kind = TamaPotionKind.STAGE,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/stage_adult.png",
            titleRes = R.string.tama_potion_stage_adult_title,
            descriptionRes = R.string.tama_potion_stage_adult_desc,
            price = 5000,
            targetStage = GrowthStage.ADULT
        ),
        TamaPotionDefinition(
            id = SENIOR_STAGE_ID,
            kind = TamaPotionKind.STAGE,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/stage_senior.png",
            titleRes = R.string.tama_potion_stage_senior_title,
            descriptionRes = R.string.tama_potion_stage_senior_desc,
            price = 5000,
            targetStage = GrowthStage.SENIOR
        ),
        TamaPotionDefinition(
            id = DRAGON_SPECIES_ID,
            kind = TamaPotionKind.SPECIES,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/species_dragon.png",
            titleRes = R.string.tama_potion_species_dragon_title,
            descriptionRes = R.string.tama_potion_species_dragon_desc,
            price = 10000,
            targetSpecies = PetSpeciesLine.DRAGON
        ),
        TamaPotionDefinition(
            id = KITSUNE_SPECIES_ID,
            kind = TamaPotionKind.SPECIES,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/species_kitsune.png",
            titleRes = R.string.tama_potion_species_kitsune_title,
            descriptionRes = R.string.tama_potion_species_kitsune_desc,
            price = 10000,
            targetSpecies = PetSpeciesLine.KITSUNE
        ),
        TamaPotionDefinition(
            id = UNICORN_SPECIES_ID,
            kind = TamaPotionKind.SPECIES,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/species_unicorn.png",
            titleRes = R.string.tama_potion_species_unicorn_title,
            descriptionRes = R.string.tama_potion_species_unicorn_desc,
            price = 10000,
            targetSpecies = PetSpeciesLine.UNICORN
        ),
        TamaPotionDefinition(
            id = GROWTH_LOCK_ID,
            kind = TamaPotionKind.GROWTH_LOCK,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/growth_lock.png",
            titleRes = R.string.tama_potion_growth_lock_title,
            descriptionRes = R.string.tama_potion_growth_lock_desc,
            price = 12000
        ),
        TamaPotionDefinition(
            id = GROWTH_UNLOCK_ID,
            kind = TamaPotionKind.GROWTH_UNLOCK,
            vendor = TamaPotionVendor.ALCHEMIST,
            assetPath = "tama/potions/growth_unlock.png",
            titleRes = R.string.tama_potion_growth_unlock_title,
            descriptionRes = R.string.tama_potion_growth_unlock_desc,
            price = 12000
        ),
        TamaPotionDefinition(
            id = HEAL_10_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_10.png",
            titleRes = R.string.tama_potion_heal_10_title,
            descriptionRes = R.string.tama_potion_heal_10_desc,
            price = 200,
            healAmount = 10
        ),
        TamaPotionDefinition(
            id = HEAL_20_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_20.png",
            titleRes = R.string.tama_potion_heal_20_title,
            descriptionRes = R.string.tama_potion_heal_20_desc,
            price = 400,
            healAmount = 20
        ),
        TamaPotionDefinition(
            id = HEAL_40_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_40.png",
            titleRes = R.string.tama_potion_heal_40_title,
            descriptionRes = R.string.tama_potion_heal_40_desc,
            price = 800,
            healAmount = 40
        ),
        TamaPotionDefinition(
            id = HEAL_60_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_60.png",
            titleRes = R.string.tama_potion_heal_60_title,
            descriptionRes = R.string.tama_potion_heal_60_desc,
            price = 1200,
            healAmount = 60
        ),
        TamaPotionDefinition(
            id = HEAL_80_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_80.png",
            titleRes = R.string.tama_potion_heal_80_title,
            descriptionRes = R.string.tama_potion_heal_80_desc,
            price = 1600,
            healAmount = 80
        ),
        TamaPotionDefinition(
            id = HEAL_100_ID,
            kind = TamaPotionKind.HEALING,
            vendor = TamaPotionVendor.HOSPITAL,
            assetPath = "tama/potions/heal_100.png",
            titleRes = R.string.tama_potion_heal_100_title,
            descriptionRes = R.string.tama_potion_heal_100_desc,
            price = 2000,
            healAmount = 100
        )
    )

    private val byId = potions.associateBy { it.id }

    fun byId(id: String?): TamaPotionDefinition? = id?.let(byId::get)

    fun isPotionId(id: String?): Boolean = id != null && byId.containsKey(id)

    fun byVendor(vendor: TamaPotionVendor): List<TamaPotionDefinition> = potions.filter { it.vendor == vendor }
}
