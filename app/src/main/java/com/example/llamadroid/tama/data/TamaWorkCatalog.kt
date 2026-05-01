package com.example.llamadroid.tama.data

import com.example.llamadroid.R

data class TamaWorkDefinition(
    val id: String,
    val titleRes: Int,
    val requiredEducation: Int,
    val hourlyPay: Long,
    val backgroundAssetPath: String
)

object TamaWorkCatalog {
    val jobs: List<TamaWorkDefinition> = listOf(
        TamaWorkDefinition(
            id = "town_runner",
            titleRes = R.string.tama_job_town_runner,
            requiredEducation = 0,
            hourlyPay = 8,
            backgroundAssetPath = "tama/backgrounds/town_runner_work.png"
        ),
        TamaWorkDefinition(
            id = "shop_helper",
            titleRes = R.string.tama_job_shop_helper,
            requiredEducation = 15,
            hourlyPay = 14,
            backgroundAssetPath = "tama/backgrounds/shop_helper_work.png"
        ),
        TamaWorkDefinition(
            id = "garden_keeper",
            titleRes = R.string.tama_job_garden_keeper,
            requiredEducation = 30,
            hourlyPay = 24,
            backgroundAssetPath = "tama/backgrounds/garden_keeper_work.png"
        ),
        TamaWorkDefinition(
            id = "library_helper",
            titleRes = R.string.tama_job_library_helper,
            requiredEducation = 50,
            hourlyPay = 40,
            backgroundAssetPath = "tama/backgrounds/library_helper_work.png"
        ),
        TamaWorkDefinition(
            id = "potion_assistant",
            titleRes = R.string.tama_job_potion_assistant,
            requiredEducation = 70,
            hourlyPay = 58,
            backgroundAssetPath = "tama/backgrounds/potion_assistant_work.png"
        ),
        TamaWorkDefinition(
            id = "town_scholar",
            titleRes = R.string.tama_job_town_scholar,
            requiredEducation = 90,
            hourlyPay = 80,
            backgroundAssetPath = "tama/backgrounds/town_scholar_work.png"
        )
    )

    fun jobById(jobId: String?): TamaWorkDefinition? {
        return jobs.firstOrNull { it.id.equals(jobId, ignoreCase = true) }
    }
}
