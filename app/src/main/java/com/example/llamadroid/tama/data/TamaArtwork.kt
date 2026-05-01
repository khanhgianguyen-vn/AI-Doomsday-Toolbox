package com.example.llamadroid.tama.data

import kotlin.random.Random

enum class TamaArtworkKind {
    DREAM,
    DAILY_DREAM,
    PAINTING
}

enum class TamaArtworkStatus {
    QUEUED,
    GENERATING,
    COMPLETED,
    FAILED
}

object TamaPicGenDefaults {
    const val DEFAULT_RESOLUTION = 256
    val RESOLUTION_PRESETS = listOf(256, 384, 512)
    const val DEFAULT_MODEL_BUNDLE_ID = "dreamshaper"
    const val DEFAULT_MODEL_PROVIDER_ID = "manuxd32"

    const val DREAM_POSITIVE_PROMPT =
        "child dreaming of a wonderful world, naive child drawing style, crayon drawing, uneven wobbly lines, simple shapes, imperfect proportions, primitive perspective, colorful scribbles, white paper background, whimsical imaginative world, rainbow in the sky, smiling sun with face, fluffy clouds, simple flowers, butterflies, stars, happy trees, small house, simple animals, playful fantasy elements, floating hearts, bright cheerful colors, doodle style, messy coloring outside the lines, kindergarten art style, innocent imagination, looks drawn by a young child age 4-6, rough wax crayon texture, minimal detail, hand drawn feel, magical childlike creativity"

    val DREAM_POSITIVE_PROMPTS = listOf(
        DREAM_POSITIVE_PROMPT,
        "child dreaming of a floating candy meadow, naive child drawing style, wax crayon drawing, wobbly uneven lines, simple shapes, imperfect proportions, primitive perspective, white paper background, smiling moon, soft rainbow, fluffy clouds, lollipops growing like flowers, tiny toy animals, floating stars, happy trees, playful hearts, messy coloring outside the lines, bright cheerful colors, kindergarten art style, rough crayon texture, minimal detail, hand drawn feel, innocent dream world",
        "child dreaming of a star train in the sky, naive child drawing style, crayon drawing, doodle look, uneven lines, simple shapes, white paper background, small train riding across puffy clouds, smiling stars, crescent moon with face, simple birds, colorful rainbow trail, tiny houses below, playful fantasy world, messy child coloring, primitive perspective, bright colors, rough wax crayon texture, minimal detail, looks drawn by a young child",
        "child dreaming of an underwater castle adventure, naive child drawing style, rough crayon drawing, simple shapes, imperfect proportions, white paper background, friendly fish, treasure chest, seaweed curls, bubble hearts, tiny castle, smiling sun peeking from corner, playful dolphins, colorful coral, whimsical child imagination, kindergarten drawing, messy coloring outside the lines, primitive perspective, bright cheerful palette",
        "child dreaming of a kite festival above the clouds, naive crayon drawing style, hand drawn feel, wobbly lines, simple rounded shapes, white paper background, giant colorful kites, smiling sun, fluffy clouds, simple birds, stars, tiny rainbow, little house below, happy trees, playful hearts, innocent imagination, rough wax crayon strokes, minimal detail, cheerful doodle world",
        "child dreaming of a toy dragon flying over a tiny village, naive child drawing style, crayon drawing, uneven lines, white paper background, simple dragon with friendly smile, rainbow sky, fluffy clouds, stars, little houses, simple flowers, happy trees, butterflies, playful fantasy elements, messy coloring outside the lines, primitive perspective, bright childlike colors, kindergarten art, rough crayon texture",
        "child dreaming of a treehouse world in the clouds, naive child drawing style, doodle crayon drawing, wobbly lines, simple shapes, imperfect perspective, white paper background, giant treehouse, rope ladder, smiling sun, puffy clouds, tiny birds, butterflies, rainbow, stars, playful hearts, cheerful animals, messy child coloring, bright colors, innocent imagination, hand drawn feel, minimal detail",
        "child dreaming of a moon boat on a sparkling river, naive child drawing style, rough crayon drawing, simple shapes, white paper background, crescent boat, smiling moon face, tiny stars, rainbow reflections, simple fish, happy trees, little house on shore, fluffy clouds, playful hearts, colorful doodles, primitive perspective, messy coloring outside the lines, kindergarten artwork, gentle magical dream scene",
        "child dreaming of a friendly robot picnic in a flower field, naive child drawing style, wax crayon drawing, uneven lines, simple shapes, white paper background, smiling robot, simple sandwiches, bright flowers, butterflies, rainbow, happy clouds, tiny birds, small house, playful stars, innocent fantasy, rough child scribbles, primitive perspective, cheerful colors, kindergarten doodle style",
        "child dreaming of a balloon parade over a sunny park, naive child drawing style, crayon drawing, hand drawn doodle feel, white paper background, many colorful balloons, smiling sun, fluffy clouds, simple flowers, little pond, happy animals, stars, rainbow, playful hearts, uneven lines, imperfect proportions, messy child coloring, bright cheerful palette, rough wax crayon texture, minimal detail"
    )

    const val DREAM_NEGATIVE_PROMPT =
        "photorealistic, realistic, highly detailed illustration, professional artwork, perfect anatomy, cinematic lighting, 3d render, digital painting, complex shading, advanced perspective, detailed textures, concept art, sharp lines, clean lineart, smooth gradients, realistic proportions, high detail environment, hyper detailed, dark atmosphere, horror, realistic child, photography style"

    const val PAINTING_POSITIVE_PROMPT =
        "child's drawing, naive art style, crayon drawing, uneven lines, simple shapes, imperfect proportions, bright colors, white paper background, messy coloring, hand drawn, primitive perspective, playful composition, innocent style, kindergarten drawing, doodle style, rough strokes, simple sun in the corner, fluffy clouds, stick figures, simple house, grass, childlike imagination, minimal detail, authentic childish art style"

    val PAINTING_POSITIVE_PROMPTS = listOf(
        PAINTING_POSITIVE_PROMPT,
        "child's drawing of a family picnic in the park, naive art style, wax crayon drawing, wobbly lines, simple shapes, imperfect proportions, white paper background, picnic blanket, stick figure family, simple trees, bright grass, smiling sun, fluffy clouds, flowers, playful doodle style, messy coloring outside the lines, primitive perspective, innocent classroom painting",
        "child's drawing of a cheerful farm scene, naive art style, crayon drawing, simple barn, tiny chickens, happy cow, bright green grass, white paper background, uneven lines, rough strokes, simple flowers, fluffy clouds, smiling sun in the corner, messy coloring, hand drawn feel, kindergarten art, minimal detail, playful composition",
        "child's drawing of a rainy day with a big rainbow, naive art style, rough crayon drawing, simple shapes, white paper background, raindrops, colorful umbrella, puddles, simple house, stick figure child, smiling sun peeking out, fluffy clouds, bright rainbow, messy coloring outside the lines, primitive perspective, authentic childish painting",
        "child's drawing of a beach day, naive art style, crayon drawing, uneven lines, simple ocean waves, sand bucket, little crab, bright sun, fluffy clouds, stick figure children, colorful beach ball, white paper background, playful doodle style, rough strokes, minimal detail, innocent classroom painting, bright cheerful colors",
        "child's drawing of a birthday party, naive art style, wax crayon drawing, white paper background, colorful balloons, birthday cake, stick figure friends, party hat, simple table, bright gifts, smiling sun in the corner, fluffy clouds, uneven lines, messy coloring, primitive perspective, kindergarten doodle style, playful composition",
        "child's drawing of a flower garden, naive art style, crayon drawing, simple flowers of many colors, butterflies, little watering can, small fence, white paper background, bright grass, smiling sun, fluffy clouds, rough hand drawn strokes, uneven lines, minimal detail, innocent child imagination, authentic childish art",
        "child's drawing of a school day walk, naive art style, crayon drawing, simple school building, stick figure child with backpack, little path, trees, flowers, white paper background, bright colors, simple clouds, smiling sun, wobbly lines, messy coloring outside the lines, playful classroom painting, primitive perspective",
        "child's drawing of a cozy house at sunset, naive art style, rough crayon drawing, white paper background, simple house with chimney, pink-orange sky, bright flowers, happy tree, fluffy clouds, stick figure family, uneven lines, primitive perspective, rough strokes, doodle style, minimal detail, cheerful child painting",
        "child's drawing of a pet and its favorite toys, naive art style, crayon drawing, simple animal friend, toy ball, toy blocks, little blanket, white paper background, bright cheerful colors, smiling sun, fluffy clouds, uneven lines, messy coloring, primitive perspective, hand drawn kindergarten art, playful innocent composition"
    )

    const val PAINTING_NEGATIVE_PROMPT =
        "photorealistic, realistic, highly detailed, professional illustration, perfect anatomy, sharp lines, clean lineart, digital painting, 3d render, cinematic lighting, complex shading, detailed textures, high resolution illustration, concept art, anime, manga, smooth gradients, realistic proportions, advanced perspective, polished artwork"

    fun randomDreamPositivePrompt(random: Random = Random.Default): String {
        return DREAM_POSITIVE_PROMPTS[random.nextInt(DREAM_POSITIVE_PROMPTS.size)]
    }

    fun randomPaintingPositivePrompt(random: Random = Random.Default): String {
        return PAINTING_POSITIVE_PROMPTS[random.nextInt(PAINTING_POSITIVE_PROMPTS.size)]
    }
}
