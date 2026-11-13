package com.nuvio.app.features.p2p

import com.nuvio.app.core.storage.DesktopCache
import com.nuvio.app.features.player.desktop.DesktopHostOs
import java.io.File
import java.util.Locale

internal object DesktopEngineLibrary {
    fun resolve(): File {
        val platform = DesktopHostOs.current
        val architecture = requireSupportedArchitecture(platform)
        val directory = directoryName(platform)
        val name = libraryName(platform)
        return packagedLibrary(directory, name)
            ?: sourceLibrary(sourceDirectory(platform, architecture), name)
            ?: extractedLibrary(directory, name)
            ?: throw P2pStreamingException(
                "The torrent engine library is not bundled for $directory ($architecture)",
            )
    }

    internal fun libraryName(platform: DesktopHostOs): String = when (platform) {
        DesktopHostOs.MACOS -> "libnuvio_engine.dylib"
        DesktopHostOs.WINDOWS -> "nuvio_engine.dll"
        DesktopHostOs.LINUX -> "libnuvio_engine.so"
        DesktopHostOs.UNKNOWN -> throw unsupportedPlatform()
    }

    internal fun directoryName(platform: DesktopHostOs): String = when (platform) {
        DesktopHostOs.MACOS -> "macos"
        DesktopHostOs.WINDOWS -> "windows"
        DesktopHostOs.LINUX -> "linux"
        DesktopHostOs.UNKNOWN -> throw unsupportedPlatform()
    }

    // Linux keeps one engine build per architecture in the source tree so the
    // x86_64 and arm64 packages come out of the same checkout. macOS ships a
    // universal library and Windows a single x86_64 one.
    internal fun sourceDirectory(platform: DesktopHostOs, architecture: String): String =
        when (platform) {
            DesktopHostOs.LINUX -> "linux/engine/$architecture"
            else -> "${directoryName(platform)}/engine"
        }

    internal fun normalizedArchitecture(value: String): String? =
        when (value.lowercase(Locale.ROOT)) {
            "amd64", "x86_64", "x64" -> "x86_64"
            "aarch64", "arm64" -> "aarch64"
            else -> null
        }

    private fun requireSupportedArchitecture(platform: DesktopHostOs): String {
        val reported = System.getProperty("os.arch").orEmpty()
        val architecture = normalizedArchitecture(reported)
        val supported = when (platform) {
            DesktopHostOs.MACOS, DesktopHostOs.LINUX -> architecture != null
            DesktopHostOs.WINDOWS -> architecture == "x86_64"
            DesktopHostOs.UNKNOWN -> throw unsupportedPlatform()
        }
        if (!supported || architecture == null) {
            throw P2pStreamingException("P2P streaming is not available for $reported")
        }
        return architecture
    }

    private fun packagedLibrary(directory: String, name: String): File? =
        System.getProperty("compose.application.resources.dir")
            ?.takeIf(String::isNotBlank)
            ?.let { File(it, "native/$directory/$name") }
            ?.takeIf(File::isFile)

    private fun sourceLibrary(directory: String, name: String): File? =
        listOf(
            File("src/desktopMain/native/$directory/$name"),
            File("composeApp/src/desktopMain/native/$directory/$name"),
        ).firstOrNull(File::isFile)

    private fun extractedLibrary(directory: String, name: String): File? {
        val resource = DesktopEngineLibrary::class.java
            .getResource("/native/$directory/$name")
            ?: return null
        return DesktopCache.installVersionedResources("nuvio-engine/$directory", mapOf(name to resource))
            .resolve(name)
            .toFile()
    }

    private fun unsupportedPlatform() =
        P2pStreamingException("P2P streaming is not available on this operating system")
}
