package app.ink.music

/** The play build reads only the critics' own feeds: no editors' lists. */
object EditorsPicks {
    const val EDITORS = "editors' picks"
    const val TUBE = "youtube music"
    fun read(): List<Curation.Pick> = emptyList()
}
