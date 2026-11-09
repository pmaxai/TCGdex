package io.github.pmaxai.cardscanner.recording

/** Recording mode (YouTube mode): the screen as a video, a log of every card shown, both uploaded to S3. */
object Recording {
    /** the event log's format; a breaking change moves it to v2 */
    const val SCHEMA = "tcgdex.recording.v1"
}
