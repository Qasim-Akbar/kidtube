package com.bbro.kidtube.share

import androidx.annotation.StringRes
import com.bbro.kidtube.R
import com.bbro.kidtube.data.repo.PlacementSpec

/**
 * "How should it appear?" presets and what each one means in plan-item fields (SPEC section 7, agreed mapping):
 *
 * | preset          | required | view_target | daily_cap | engine meaning                                              |
 * |-----------------|----------|-------------|-----------|-------------------------------------------------------------|
 * | ONCE_A_DAY      | false    | null        | 1         | optional, unlimited: offered once every active day, forever  |
 * | THREE_TIMES     | false    | 3           | 1         | optional: offered once a day on three days, then complete    |
 * | MUST_WATCH      | true     | null        | null      | required (counts as 1 view): the feed locks to it first      |
 * | LIBRARY_ONLY    | -        | -           | -         | no plan item at all: the video is only added to the library  |
 *
 * `anchor_date`, `on_miss` and the watch threshold keep their defaults; no position is sent (the feed puts required
 * items first on its own).
 */
enum class SharePreset(@StringRes val label: Int) {
    ONCE_A_DAY(R.string.preset_once_a_day),
    THREE_TIMES(R.string.preset_three_times),
    MUST_WATCH(R.string.preset_must_watch_first),
    LIBRARY_ONLY(R.string.preset_library_only);

    /** The placement for one child, or null when this preset adds no plan item. */
    fun placementFor(childId: String): PlacementSpec? = when (this) {
        ONCE_A_DAY -> PlacementSpec(childId, required = false, viewTarget = null, dailyCap = 1)
        THREE_TIMES -> PlacementSpec(childId, required = false, viewTarget = 3, dailyCap = 1)
        MUST_WATCH -> PlacementSpec(childId, required = true, viewTarget = null, dailyCap = null)
        LIBRARY_ONLY -> null
    }

    val addsPlanItem: Boolean get() = this != LIBRARY_ONLY

    companion object {
        /** One concrete placement per child (always explicit, never "every child"); empty for [LIBRARY_ONLY]. */
        fun placements(preset: SharePreset, childIds: Collection<String>): List<PlacementSpec> =
            childIds.mapNotNull { preset.placementFor(it) }

        /** Reads a stored plan item back as a preset; null when it matches none (a custom item). */
        fun describe(required: Boolean, viewTarget: Int?, dailyCap: Int?): SharePreset? = when {
            required && viewTarget == null && dailyCap == null -> MUST_WATCH
            !required && viewTarget == null && dailyCap == 1 -> ONCE_A_DAY
            !required && viewTarget == 3 && dailyCap == 1 -> THREE_TIMES
            else -> null
        }
    }
}
