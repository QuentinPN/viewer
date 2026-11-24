package io.github.quentinpn.viewer.tv

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Writes [text] (UTF-8) to [target] through a temporary file beside it, moved over it in one step: a reader
 * finds the old content or the new one, never half of it. Throws [java.io.IOException].
 */
fun writeAtomically(target: File, text: String) {
    val temp = File(target.parentFile, "${target.name}.tmp")
    temp.writeText(text)
    Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
}
