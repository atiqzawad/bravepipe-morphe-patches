package app.bravepipe.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    // BravePipe "brave" and "braveConscrypt" flavors.
    val COMPATIBILITY_BRAVEPIPE = Compatibility(
        name = "BravePipe",
        packageName = "com.github.bravenewpipe",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE53935,
        targets = listOf(
            // Not tied to one version: the hooks only need class and method names, which the
            // release build keeps (-dontobfuscate). Marked experimental because no exact version
            // has been confirmed yet. Add confirmed versions here once you have tested them.
            AppTarget(
                version = null,
                isExperimental = true
            )
        )
    )

    // BravePipe "braveLegacy" (Kitkat) flavor, it has its own application id.
    val COMPATIBILITY_BRAVEPIPE_KITKAT = Compatibility(
        name = "BravePipe Kitkat",
        packageName = "com.github.bravenewpipe.kitkat",
        apkFileType = ApkFileType.APK,
        appIconColor = 0xE53935,
        targets = listOf(
            AppTarget(
                version = null,
                isExperimental = true
            )
        )
    )
}
