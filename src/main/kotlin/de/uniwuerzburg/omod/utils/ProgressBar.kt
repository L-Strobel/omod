package de.uniwuerzburg.omod.utils

/**
 * Progress bar
 */
object ProgressBar {
    /**
     * Print progress
     * @param progress Percentage completed
     * @return String that depicts the progress bar
     */
    fun show(progress: Double) : String {
        val ticks = (50 * progress).toInt()
        val bar = "[${"=".repeat(ticks)}${" ".repeat(50 - ticks)}]"
        val number = "%.2f".format(null, 100.0 * progress)
        return "$bar $number %"
    }
    /**
     * Print completed progressbar
     * @return String that depicts the progress bar
     */
    fun done() : String {
        val bar = "[${"=".repeat(50)}]"
        return "$bar Done!"
    }
}