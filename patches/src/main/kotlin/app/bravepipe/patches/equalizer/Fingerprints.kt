package app.bravepipe.patches.equalizer

import app.morphe.patcher.Fingerprint

private const val VIDEO_PLAYER_UI = "Lorg/schabi/newpipe/player/ui/VideoPlayerUi;"

/**
 * VideoPlayerUi.initPlayer(): called every time the player gets a new ExoPlayer instance.
 * It overrides PlayerUi.initPlayer(), so R8 can not inline it away.
 *
 * BravePipe's release build uses -dontobfuscate, so class and method names are stable.
 */
object InitPlayerFingerprint : Fingerprint(
    definingClass = VIDEO_PLAYER_UI,
    name = "initPlayer",
    returnType = "V",
    parameters = listOf()
)

/**
 * VideoPlayerUi.setupVideoSurfaceIfNeeded(): connects the SurfaceView to ExoPlayer.
 * It is public and called from several classes, which keeps R8 from removing or inlining it.
 */
object SetupVideoSurfaceFingerprint : Fingerprint(
    definingClass = VIDEO_PLAYER_UI,
    name = "setupVideoSurfaceIfNeeded",
    returnType = "V",
    parameters = listOf()
)
