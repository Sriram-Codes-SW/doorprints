package app.doorprints.shared.export

/**
 * The four languages a copy can be made in, by their **native** names (docs/05 section 8).
 *
 * One list for every place that shows a language: the Export screen's "Language of the copy" choices, the Settings
 * language picker, and the cover of the HTML, PDF and Markdown copies. The cover used to print the raw code
 * (`ta`), which means nothing to the family member a copy is sent to; the native name is recognisable whatever
 * language the reader or the phone is in.
 */
object ExportLanguages {

    /** Code to native name, in the order the choices are offered. */
    val NATIVE_NAMES: Map<String, String> = mapOf(
        "en" to "English",
        "hi" to "हिन्दी",
        "ta" to "தமிழ்",
        "te" to "తెలుగు",
    )

    /** The native name of [code], or the code itself for anything outside en/hi/ta/te. */
    fun nativeName(code: String): String = NATIVE_NAMES[code] ?: code
}
