package tv.ember.client.emby

import java.text.Normalizer
import kotlinx.coroutines.*

/** Fold keyboard width/spacing, while preserving accents and the actual title words. */
fun normalizeSearch(raw: String): String = Normalizer.normalize(raw,Normalizer.Form.NFKC)
    .replace(Regex("[\\s\\p{Z}]+")," ").trim()

/** One pending query per screen. Explicit remote/IME submission bypasses the typing delay. */
class SearchInput(private val scope: CoroutineScope,private val deliver: (String)->Unit) {
    private var pending: Job?=null
    private var delivered: String?=null
    fun change(raw: String,immediate: Boolean=false) {
        val term=normalizeSearch(raw)
        pending?.cancel()
        if(term==delivered) return
        pending=scope.launch {
            if(!immediate && term.isNotEmpty()) delay(300)
            delivered=term;deliver(term)
        }
    }
    fun cancel() { pending?.cancel();delivered=null }
}
