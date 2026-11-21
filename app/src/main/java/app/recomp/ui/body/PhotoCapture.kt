package app.recomp.ui.body

/**
 * The order of a capture (TRD §10 M17): with CAMERA declared in the manifest, firing the camera intent without
 * the permission throws, so Recomp always asks first and only fires once it's granted.
 */
object PhotoCapture {
    enum class Step { ASK_PERMISSION, LAUNCH_CAMERA, EXPLAIN_DENIED }

    /**
     * @param granted whether CAMERA is granted now.
     * @param askedBefore whether you've just answered the system prompt (so a "no" isn't asked again at once).
     */
    fun next(granted: Boolean, askedBefore: Boolean): Step = when {
        granted -> Step.LAUNCH_CAMERA
        askedBefore -> Step.EXPLAIN_DENIED
        else -> Step.ASK_PERMISSION
    }

    const val DENIED = "Recomp needs the camera to take progress photos. You can allow it in the phone's settings for Recomp."

    /** The FileProvider authority from the manifest, for this install's application id. */
    fun authority(applicationId: String) = "$applicationId.photos"
}
