package local.smartdisplay

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 振動（タイマーの通知と、設定画面の「振動テスト」で共通）。
 * アラーム用途(USAGE_ALARM)として鳴らすので、端末の「タッチ操作の振動」などの設定に左右されにくい。
 */
object Vib {

    private val PATTERN = longArrayOf(0, 600, 400)   // 待つ0ms → 振動600ms → 休み400ms を繰り返す

    @Suppress("DEPRECATION")
    private fun vibrator(ctx: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    /** 振動を始める。振動機能がない・始められなかったときは false */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun start(ctx: Context): Boolean {
        val v = vibrator(ctx) ?: return false
        if (!v.hasVibrator()) return false
        return try {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            v.vibrate(VibrationEffect.createWaveform(PATTERN, 0), attrs)   // 0 = 止めるまで繰り返す
            true
        } catch (e: Exception) {
            try {
                v.vibrate(VibrationEffect.createWaveform(PATTERN, 0))      // 属性つきがだめなら、属性なしで
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    fun stop(ctx: Context) {
        try { vibrator(ctx)?.cancel() } catch (e: Exception) { /* 何もしない */ }
    }
}
