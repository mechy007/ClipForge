package com.clipforge.app

/** Timeline planner for cause → reaction shorts. All times are clip-relative. */
enum class ShortView { SPLIT, GAMEPLAY_FULL, FACECAM_FULL }
data class ViewCut(val startMs: Long, val endMs: Long, val view: ShortView)

/** Keeps every shot >= 800ms, caps full-screen switches and supports no-facecam videos. */
object ReactionCutPlanner {
    fun plan(
        clipDurationMs: Long,
        causeMs: Long,
        reactionPeakMs: Long,
        hasFacecam: Boolean,
        maxFullScreenSwitches: Int = 4
    ): List<ViewCut> {
        require(clipDurationMs > 0)
        require(maxFullScreenSwitches >= 0)
        val minShot = 800L
        if (clipDurationMs < minShot * 4 || maxFullScreenSwitches < 2) {
            return listOf(ViewCut(0, clipDurationMs, ShortView.SPLIT))
        }
        val gameplayStart = (causeMs - 1_500L).coerceIn(minShot, clipDurationMs - 2 * minShot)
        val gameplayEnd = (reactionPeakMs - 100L).coerceIn(gameplayStart + minShot, clipDurationMs - minShot)
        if (hasFacecam && maxFullScreenSwitches >= 4 && gameplayEnd + 2 * minShot <= clipDurationMs) {
            val faceEnd = (gameplayEnd + 1_500L).coerceAtMost(clipDurationMs - minShot)
            return listOf(
                ViewCut(0, gameplayStart, ShortView.SPLIT),
                ViewCut(gameplayStart, gameplayEnd, ShortView.GAMEPLAY_FULL),
                ViewCut(gameplayEnd, faceEnd, ShortView.FACECAM_FULL),
                ViewCut(faceEnd, clipDurationMs, ShortView.SPLIT)
            )
        }
        if (gameplayEnd + minShot <= clipDurationMs) {
            return listOf(
                ViewCut(0, gameplayStart, ShortView.SPLIT),
                ViewCut(gameplayStart, gameplayEnd, ShortView.GAMEPLAY_FULL),
                ViewCut(gameplayEnd, clipDurationMs, ShortView.SPLIT)
            )
        }
        return listOf(ViewCut(0, clipDurationMs, ShortView.SPLIT))
    }
}
