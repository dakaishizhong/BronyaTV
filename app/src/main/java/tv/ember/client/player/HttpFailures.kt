package tv.ember.client.player

import androidx.media3.datasource.HttpDataSource

object HttpFailures {
    fun find(error: Throwable): HttpDataSource.InvalidResponseCodeException? =
        generateSequence(error as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

    fun message(code: Int, subtitle: Boolean = false): String {
        val source = if(subtitle) "字幕" else "视频"
        val reason = when(code) {
            401 -> "登录凭证或播放地址认证已失效"
            403 -> "服务器/CDN 拒绝访问，可能是播放权限、地址签名或请求头要求"
            404, 410 -> "播放地址或片源不存在/已失效"
            416 -> "服务器不接受当前续播位置的 Range 请求，请返回详情选择从头播放"
            429 -> "服务器请求过多，请稍后重试"
            in 500..599 -> "服务器/CDN 暂时异常"
            else -> "服务器返回了错误响应"
        }
        return "$source 请求失败（HTTP $code）：$reason。按菜单键可重新获取播放地址或使用外部播放器。"
    }
}
