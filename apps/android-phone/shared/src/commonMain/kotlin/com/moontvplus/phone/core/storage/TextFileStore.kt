package com.moontvplus.phone.core.storage


import com.moontvplus.phone.core.platform.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.buffer

/**
 * 应用私有目录下的小型文本存储（设置、会话、首页缓存都走它）。
 *
 * 用 okio 而不是 java.io：Android 与 JVM 桌面共用同一份实现，
 * 后续加 iOS 也只需要替换 [Platform.appDataDir] 的来源。
 */
class TextFileStore(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val root: String = Platform.appDataDir,
) {
    private val mutex = Mutex()

    fun pathOf(relativePath: String) = "$root/$relativePath".toPath()

    suspend fun read(relativePath: String): String? = withContext(Dispatchers.IO) {
        val path = pathOf(relativePath)
        runCatching {
            if (!fileSystem.exists(path)) return@runCatching null
            fileSystem.source(path).buffer().use { it.readUtf8() }
        }.getOrNull()
    }

    suspend fun write(relativePath: String, content: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val path = pathOf(relativePath)
            runCatching {
                path.parent?.let { fileSystem.createDirectories(it) }
                // 先写临时文件再改名，避免写一半掉电把配置写坏
                val tmp = "$root/$relativePath.tmp".toPath()
                fileSystem.sink(tmp).buffer().use { it.writeUtf8(content) }
                fileSystem.atomicMove(tmp, path)
            }
        }
        Unit
    }

    suspend fun delete(relativePath: String) = withContext(Dispatchers.IO) {
        runCatching {
            val path = pathOf(relativePath)
            if (fileSystem.exists(path)) fileSystem.delete(path)
        }
        Unit
    }
}
