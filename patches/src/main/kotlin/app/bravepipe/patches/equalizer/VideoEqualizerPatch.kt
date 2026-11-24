package app.bravepipe.patches.equalizer

import app.bravepipe.patches.shared.Constants.COMPATIBILITY_BRAVEPIPE
import app.bravepipe.patches.shared.Constants.COMPATIBILITY_BRAVEPIPE_KITKAT
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.Compatibility
import app.morphe.patcher.patch.patch

private const val EXTENSION_CLASS = "Lapp/bravepipe/extension/equalizer/VideoEqualizer;"

private fun videoEqualizerPatch(patchName: String, compatibility: Compatibility) = patch(
    name = patchName,
    description = "Adds an EQ button to the player to change the brightness, contrast and " +
        "saturation of the playing video. Not available in the popup player.",
    default = true
) {
    compatibleWith(compatibility)

    extendWith("extensions/extension.mpe")

    execute {
        // A new ExoPlayer is about to be used: forget what was attached to the old one.
        InitPlayerFingerprint.method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS->onInitPlayer(Ljava/lang/Object;)V"
        )

        // If the equalizer is active the video is rendered into a TextureView (needed for the
        // color filter), so the original SurfaceView setup must be skipped.
        SetupVideoSurfaceFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { p0 }, $EXTENSION_CLASS->skipSurfaceSetup(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :run_original
                return-void
                :run_original
                nop
            """
        )
    }
}

@Suppress("unused")
val videoEqualizerPatch = videoEqualizerPatch("Video equalizer", COMPATIBILITY_BRAVEPIPE)

@Suppress("unused")
val videoEqualizerKitkatPatch =
    videoEqualizerPatch("Video equalizer (Kitkat)", COMPATIBILITY_BRAVEPIPE_KITKAT)
