package tv.ember.client.player

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import tv.ember.client.data.PlaybackSpec
import tv.ember.client.settings.PlayerChoice

object ExternalPlayers {
    fun installedPackage(context: Context, choice: PlayerChoice): String? = choice.packages.firstOrNull {
        runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
    }
    fun available(context: Context, choice: PlayerChoice) = choice == PlayerChoice.INTERNAL || installedPackage(context, choice) != null
    fun launch(context: Context, choice: PlayerChoice, spec: PlaybackSpec, title: String, positionMs: Long) {
        val pkg = installedPackage(context, choice) ?: throw ActivityNotFoundException("${choice.label} 尚未安装")
        val headers = spec.headers.flatMap { listOf(it.key, it.value) }.toTypedArray()
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(spec.url), "video/*").setPackage(pkg)
            .putExtra("title", title).putExtra("headers", headers)
            .putExtra("position", positionMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .putExtra("return_result", true)
        if(choice == PlayerChoice.VLC) intent.putExtra("from_start", positionMs <= 0).putExtra("position", positionMs)
        context.startActivity(intent)
    }
}
