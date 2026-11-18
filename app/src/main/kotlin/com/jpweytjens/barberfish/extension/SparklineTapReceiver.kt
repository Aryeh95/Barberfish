package com.jpweytjens.barberfish.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jpweytjens.barberfish.datatype.shared.effectiveLookaheadMInt
import com.jpweytjens.barberfish.datatype.shared.withNextLookahead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class SparklineTapReceiver : BroadcastReceiver() {
    data class TapEvent(
        val timestampMs: Long = 0L,
        val meters: Int = 10_000,
        val isCustom: Boolean = false,
    )

    companion object {
        const val ACTION = "com.jpweytjens.barberfish.SPARKLINE_TAP"
        const val EXTRA_SURFACE = "surface"
        const val SURFACE_HUD = "hud"
        const val SURFACE_FIELD = "field"
        // Only the HUD strip listens (2s transition overlay). Timestamp is always unique (ms),
        // preventing MutableStateFlow deduplication even when a single custom scale is set.
        val tapSignal = MutableStateFlow(TapEvent())
    }

    override fun onReceive(context: Context, intent: Intent) {
        val isField = intent.getStringExtra(EXTRA_SURFACE) == SURFACE_FIELD
        val result = goAsync()
        val job = Job()
        CoroutineScope(Dispatchers.IO + job).launch {
            try {
                withTimeout(5_000L) {
                    if (isField) {
                        val cfg = context.streamFieldSparklineConfig().first()
                        context.saveFieldSparklineConfig(cfg.withNextLookahead())
                    } else {
                        val cfg = context.streamHudSparklineConfig().first()
                        val updated = cfg.withNextLookahead()
                        tapSignal.value =
                            TapEvent(
                                timestampMs = System.currentTimeMillis(),
                                meters = updated.effectiveLookaheadMInt(),
                                isCustom = updated.lookaheadScalesM.isNotEmpty(),
                            )
                        context.saveHudSparklineConfig(updated)
                    }
                }
            } finally {
                result.finish()
                job.cancel()
            }
        }
    }
}
