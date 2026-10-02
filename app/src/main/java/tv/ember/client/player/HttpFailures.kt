package tv.ember.client.player

import tv.ember.client.i18n.Tr
import tv.ember.client.i18n.UiText
import androidx.media3.datasource.HttpDataSource

object HttpFailures {
    fun find(error: Throwable): HttpDataSource.InvalidResponseCodeException? =
        generateSequence(error as Throwable?) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()

    fun message(code: Int, subtitle: Boolean = false): String {
        val source = if(subtitle) Tr.text(UiText.SUBTITLE_126) else Tr.text(UiText.VIDEO_127)
        val reason = when(code) {
            401 -> Tr.text(UiText.SESSION_OR_PLAYBACK_URL_AUTHENTICATION_EXPIRED_128)
            403 -> Tr.text(UiText.SERVER_CDN_DENIED_ACCESS_CHECK_PERMISSIONS_129)
            404, 410 -> Tr.text(UiText.PLAYBACK_URL_OR_SOURCE_IS_MISSING_130)
            416 -> Tr.text(UiText.SERVER_REJECTED_THE_RESUME_RANGE_REQUEST_131)
            429 -> Tr.text(UiText.TOO_MANY_SERVER_REQUESTS_TRY_AGAIN_132)
            in 500..599 -> Tr.text(UiText.SERVER_CDN_TEMPORARILY_UNAVAILABLE_133)
            else -> Tr.text(UiText.SERVER_RETURNED_AN_ERROR_RESPONSE_134)
        }
        return Tr.text(UiText.REQUEST_FAILED_HTTP_PRESS_MENU_TO_135 ,(source),(code),(reason))
    }
}
