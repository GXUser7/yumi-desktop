package com.mydrop.vpn.data

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Publish only durable writes: pairing acknowledgements must survive an immediate exit. */
class JsonStore<T>(
    private val file: File,
    private val serializer: KSerializer<T>,
    private val defaultValue: T,
    scope: CoroutineScope,
    private val onWriteFailure: (Throwable) -> Unit = {},
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val parentDir: File = file.absoluteFile.parentFile
    private val mutable = MutableStateFlow(read())
    val state = mutable.asStateFlow()
    val value get() = mutable.value

    @Synchronized
    fun update(transform: (T) -> T) {
        val next = transform(value)
        try {
            parentDir.mkdirs()
            val temp = File(parentDir, file.name + ".tmp")
            java.io.FileOutputStream(temp).use { output ->
                output.write(json.encodeToString(serializer, next).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            setOwnerOnlyPermissions(temp.toPath())
            moveWithRetry(temp.toPath(), file.toPath())
            setOwnerOnlyPermissions(file.toPath())
            mutable.value = next
        } catch (error: Throwable) {
            onWriteFailure(error)
            throw error
        }
    }

    private fun read(): T {
        if (!file.exists()) return defaultValue
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (error: Exception) {
            runCatching {
                Files.copy(
                    file.toPath(),
                    File(parentDir, file.name + ".corrupt-" + System.currentTimeMillis()).toPath(),
                    REPLACE_EXISTING,
                )
            }
            onWriteFailure(error)
            defaultValue
        }
    }

    private fun moveWithRetry(source: Path, target: Path) {
        val maxAttempts = 5
        var lastError: IOException? = null
        for (attempt in 1..maxAttempts) {
            try {
                try {
                    Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(source, target, REPLACE_EXISTING)
                }
                return
            } catch (error: IOException) {
                lastError = error
                if (attempt < maxAttempts) {
                    Thread.sleep(25L)
                }
            }
        }
        throw lastError!!
    }

    private fun setOwnerOnlyPermissions(path: Path) {
        runCatching {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
            }
        }
    }
}
