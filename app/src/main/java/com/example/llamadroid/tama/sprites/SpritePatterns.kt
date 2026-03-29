package com.example.llamadroid.tama.sprites

import com.example.llamadroid.tama.data.GeneticTraits

/**
 * Retro pixel art sprite system with genetic variations.
 * Each sprite is a grid of characters where:
 * - '.' = transparent
 * - '#' = black pixel (main color)
 * - 'O' = white/light pixel (highlight)
 * - '@' = blush/pink pixel
 * - Character meanings for expressions: ^ v w - T etc.
 */
object SpritePatterns {
    
    // ==================== EYE STYLES ====================
    
    val EYES = listOf(
        "O..O",   // 0: Wide open
        ".##.",   // 1: Dots
        "^..^",   // 2: Happy closed
        ">..<",   // 3: Annoyed
        "T..T",   // 4: Crying
        "*..!"    // 5: Winking
    )
    
    val EYES_SLEEPING = "--..--"
    val EYES_SICK = "x..x"
    
    // ==================== MOUTH STYLES ====================
    
    val MOUTHS = listOf(
        ".ww.",   // 0: Cat mouth
        ".vv.",   // 1: Happy
        ".oo.",   // 2: Surprised
        ".nn.",   // 3: Sad
        ".__.",   // 4: Neutral line
        ".~~."    // 5: Wavy
    )
    
    val MOUTH_EATING = ".OO."
    
    // ==================== EAR STYLES ====================
    
    val EARS_TOP = listOf(
        "........",  // 0: No ears (slime)
        ".^....^.",  // 1: Pointy cat ears
        "o......o",  // 2: Round ears
        ".v....v.",  // 3: Droopy ears
        "########"   // 4: Spiky top
    )
    
    // ==================== BODY STYLES ====================
    
    val BODY_BABY = listOf(
        // 0: Round blob
        listOf(
            "..####..",
            ".#EYES#.",
            ".#....#.",
            ".#MOUT#.",
            "..####..",
            "..#..#.."
        ),
        // 1: Taller blob
        listOf(
            "..####..",
            ".#EYES#.",
            ".#....#.",
            ".#MOUT#.",
            ".######.",
            "..#..#.."
        ),
        // 2: Wide blob
        listOf(
            ".######.",
            "#.EYES.#",
            "#......#",
            "#.MOUT.#",
            ".######.",
            "..#..#.."
        )
    )
    
    val BODY_CHILD = listOf(
        // 0: Standard child
        listOf(
            "EARS....",
            "..####..",
            ".#EYES#.",
            ".#....#.",
            ".#MOUT#.",
            ".######.",
            ".#....#.",
            ".#....#."
        ),
        // 1: Chubby child
        listOf(
            "EARS....",
            ".######.",
            "#.EYES.#",
            "#......#",
            "#.MOUT.#",
            ".######.",
            ".#....#.",
            ".#....#."
        )
    )
    
    val BODY_ADULT = listOf(
        // 0: Standard adult
        listOf(
            "EARS....",
            ".######.",
            "#.EYES.#",
            "#......#",
            "#.MOUT.#",
            ".######.",
            "#......#",
            "#......#"
        ),
        // 1: Tall adult
        listOf(
            "EARS....",
            "..####..",
            ".#EYES#.",
            ".#....#.",
            ".#MOUT#.",
            ".######.",
            ".#....#.",
            "#......#"
        )
    )
    
    // ==================== EGG PATTERNS ====================
    
    val EGG_PATTERNS = listOf(
        // 0: Question marks
        listOf(
            "........",
            "..####..",
            ".#....#.",
            "#..!!..#",
            "#......#",
            ".#....#.",
            "..####..",
            "........"
        ),
        // 1: Heart
        listOf(
            "........",
            "..####..",
            ".#.##.#.",
            "#.#..#.#",
            "#......#",
            ".#....#.",
            "..####..",
            "........"
        ),
        // 2: Stripes
        listOf(
            "........",
            "..####..",
            ".#....#.",
            "#.####.#",
            "#......#",
            ".#....#.",
            "..####..",
            "........"
        ),
        // 3: Stars
        listOf(
            "........",
            "..####..",
            ".#..!.#.",
            "#.!...!#",
            "#......#",
            ".#....#.",
            "..####..",
            "........"
        )
    )
    
    // ==================== ROOM SPRITES ====================
    
    val ROOM_BED = """
        ................
        ................
        ....########....
        ...#........#...
        ...#..ZZZ...#...
        ...##########...
        ..#..........#..
        ..############..
        ................
    """.trimIndent()
    
    // ==================== FOOD SPRITES ====================
    
    val FOOD_SPRITES = listOf(
        // Apple
        ".#.\n###\n###\n.#.",
        // Cake
        ".O.\n###\n###\n...",
        // Fish
        ">#>\n###\n>#>",
        // Rice
        "...\n.#.\n###\n###"
    )
    
    // ==================== LOCATION BACKGROUNDS ====================
    
    val LOCATION_SCENES = mapOf(
        "home" to listOf(
            "................",
            "................",
            "....|------|....",
            "....|      |....",
            "....|__  __|....",
            ".../        \\...",
            "..|          |..",
            "..|__________|.."
        ),
        "shop" to listOf(
            "################",
            "# [][][]  [][] #",
            "# [][][]  [][] #",
            "#              #",
            "#    ________  #",
            "#   |        | #",
            "#   |  SHOP  | #",
            "################"
        ),
        "park" to listOf(
            "..../\\..../\\....",
            ".../  \\../  \\...",
            "../    \\/    \\..",
            "................",
            "...___...___....",
            "../   \\./   \\...",
            ".|  O  |  O  |..",
            "..\\___/ \\___/..."
        ),
        "school" to listOf(
            "################",
            "#  __________  #",
            "# | A B C D  | #",
            "# | 1 2 3 4  | #",
            "# |__________| #",
            "#              #",
            "#    [====]    #",
            "################"
        ),
        "office" to listOf(
            "|==============|",
            "| [][][] [][][]|",
            "| [][][] [][][]|",
            "|              |",
            "|  _  _  _  _  |",
            "| | || || || | |",
            "| | || || || | |",
            "|==============|"
        ),
        "hospital" to listOf(
            "################",
            "#      +       #",
            "#    #####     #",
            "#      #       #",
            "#  _________   #",
            "# | [][][]  |  #",
            "# | [][][]  |  #",
            "################"
        )
    )
    
    // ==================== SPRITE GENERATOR ====================
    
    /**
     * Generate a complete sprite based on genetics and state.
     */
    fun generateSprite(
        stage: String,
        genetics: GeneticTraits,
        mood: String,
        action: String = "idle",
        isMad: Boolean = false
    ): String {
        return when (stage) {
            "EGG" -> generateEggSprite(genetics, action)
            "BABY" -> generateCreatureSprite(genetics, mood, action, BODY_BABY, isMad)
            "CHILD" -> generateCreatureSprite(genetics, mood, action, BODY_CHILD, isMad)
            "TEEN", "ADULT", "SENIOR" -> generateCreatureSprite(genetics, mood, action, BODY_ADULT, isMad)
            else -> generateCreatureSprite(genetics, mood, action, BODY_BABY, isMad)
        }
    }
    
    private fun generateEggSprite(genetics: GeneticTraits, action: String): String {
        val patternIndex = genetics.bodyStyle % EGG_PATTERNS.size
        val basePattern = EGG_PATTERNS[patternIndex].toMutableList()
        
        // Add wobble for animation
        if (action == "wobble") {
            // Shift bottom row
            basePattern[6] = if (System.currentTimeMillis() % 1000 < 500) ".####..." else "...####."
        }
        
        return basePattern.joinToString("\n")
    }
    
    private fun generateCreatureSprite(
        genetics: GeneticTraits,
        mood: String,
        action: String,
        bodyTemplates: List<List<String>>,
        isMad: Boolean = false
    ): String {
        // Select body shape based on genetics
        val bodyIndex = genetics.bodyStyle % bodyTemplates.size
        val template = bodyTemplates[bodyIndex].toMutableList()
        
        // Select eyes based on mood and genetics
        val eyes = when {
            action == "sleeping" || mood == "SLEEPING" -> EYES_SLEEPING
            mood == "SICK" -> EYES_SICK
            (isMad && (mood == "HAPPY" || mood == "NEUTRAL")) || mood == "ANGRY" -> EYES[3] // Annoyed
            mood == "HAPPY" || mood == "ECSTATIC" -> EYES[2]  // Happy eyes
            mood == "SAD" -> EYES[4]  // Crying
            else -> EYES[genetics.eyeStyle % EYES.size]
        }
        
        // Select mouth based on mood/action
        val mouth = when {
            action == "eating" -> MOUTH_EATING
            mood == "HAPPY" || mood == "ECSTATIC" -> MOUTHS[1]
            mood == "SAD" -> MOUTHS[3]
            action == "sleeping" || mood == "SLEEPING" -> MOUTHS[4]
            else -> MOUTHS[genetics.mouthStyle % MOUTHS.size]
        }
        
        // Select ears
        val ears = EARS_TOP[genetics.earStyle % EARS_TOP.size]
        
        // Replace placeholders in template
        val result = template.map { line ->
            line.replace("EYES", eyes.take(4).padEnd(4, '.'))
                .replace("MOUT", mouth.take(4).padEnd(4, '.'))
                .replace("EARS", ears.take(8).padEnd(8, '.'))
        }
        
        // Add ZZZ for sleeping
        if (action == "sleeping" || mood == "SLEEPING") {
            return result.joinToString("\n") + "\n..ZZZ..."
        }
        
        // Add bubbles for cleaning
        if (action == "cleaning") {
            return ".oO.oO..\n" + result.joinToString("\n")
        }
        
        return result.joinToString("\n")
    }
    
    // ==================== LEGACY STATIC SPRITES ====================
    // These are kept for fallback compatibility
    
    val SLIME_IDLE = """
        ........
        ..####..
        .#O..O#.
        .#....#.
        .#.ww.#.
        .######.
        ........
        ........
    """.trimIndent()
    
    val SLIME_SLEEPING = """
        ........
        ..####..
        .#--..#.
        .#..--#.
        .#....#.
        .######.
        ...ZZZ..
        ........
    """.trimIndent()
    
    /**
     * Legacy function for backward compatibility.
     */
    fun getSprite(
        stage: String,
        mood: String,
        action: String = "idle",
        frame: Int = 0
    ): String {
        // Use default genetics for legacy calls
        return generateSprite(stage, GeneticTraits.random(), mood, action)
    }
}
