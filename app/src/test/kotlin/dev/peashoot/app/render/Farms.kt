package dev.peashoot.app.render

import dev.peashoot.app.farm.Activity
import dev.peashoot.app.farm.Crop
import dev.peashoot.app.farm.FarmState
import dev.peashoot.app.farm.Field
import dev.peashoot.app.farm.Growth
import dev.peashoot.app.farm.Villager
import dev.peashoot.app.farm.nameFor

/**
 * A farm of a given size, built rather than replayed: the layout's corner cases and the bench's 200
 * entities both need farms bigger than any fixture, and one builder keeps them the same shape.
 *
 * Villagers are all main-thread sessions, either all walking to the well or all on their way home,
 * which is what makes the bench's villagers walk the whole length of the farm and back.
 */
internal fun syntheticFarm(
    fields: Int,
    crops: Int,
    lastFieldCrops: Int = crops,
    villagers: Int = 0,
    atWell: Boolean = false,
): FarmState {
    val people =
        (0 until villagers).associate { i ->
            val id = "sess-bench-$i"
            id to
                Villager(
                    id = id,
                    name = nameFor(id),
                    session = id,
                    parent = null,
                    activity = if (atWell) Activity.WALKING_TO_WELL else Activity.RETURNING,
                )
        }
    return FarmState(
        villagers = people,
        fields =
            (0 until fields).associate { field ->
                val directory = "src/field$field"
                directory to
                    Field(
                        label = directory,
                        crops =
                            cropsOf(directory, if (field == fields - 1) lastFieldCrops else crops),
                    )
            },
        wellQueue = if (atWell) people.keys.toList() else emptyList(),
    )
}

private fun cropsOf(directory: String, crops: Int): Map<String, Crop> =
    (0 until crops).associate { crop ->
        val path = "$directory/File$crop.kt"
        path to
            Crop(
                label = path,
                growth = Growth.entries[crop % Growth.entries.size],
                inspections = 0,
                lastTouched = null,
            )
    }
