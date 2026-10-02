package tv.ember.client.i18n

/** Shared by views, media labels and diagnostics, including background work. */
object Tr {
    @Volatile var language: String="en"
        internal set
    private val argument=Regex("\\{(\\d+)\\}")
    fun text(value: UiText,vararg args: Any?): String {
        val template=if(language=="zh") value.chinese else value.english
        return argument.replace(template) { match -> args.getOrNull(match.groupValues[1].toInt())?.toString() ?: match.value }
    }
}
