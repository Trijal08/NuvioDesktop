package com.nuvio.app.features.p2p

import com.nuvio.app.features.player.desktop.DesktopHostOs
import com.nuvio.engine.NuvioEngineRuntime
import com.nuvio.engine.NuvioTorrentProfile
import com.nuvio.engine.NuvioUploadMode
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopEngineTest {
    @Test
    fun appOwnedRoutesDisableAutomaticInactivityExpiry() {
        val uploading = buildNuvioEngineConfig(
            stateDirectory = File("state"),
            cacheDirectory = File("cache"),
            uploadEnabled = true,
            torrentProfile = P2pTorrentProfile.FAST,
            diskCacheCapacityBytes = P2pCacheSize.GB_5.bytes,
        )
        val downloadOnly = buildNuvioEngineConfig(
            stateDirectory = File("state"),
            cacheDirectory = File("cache"),
            uploadEnabled = false,
            torrentProfile = P2pTorrentProfile.SOFT,
            diskCacheCapacityBytes = P2pCacheSize.NONE.bytes,
        )

        assertEquals(0, uploading.streamInactivityTimeoutMilliseconds)
        assertEquals(NuvioUploadMode.Unlimited, uploading.uploadMode)
        assertEquals(NuvioTorrentProfile.Fast, uploading.torrentProfile)
        assertEquals(P2pCacheSize.GB_5.bytes, uploading.diskCacheCapacityBytes)
        assertEquals(NuvioUploadMode.Disabled, downloadOnly.uploadMode)
        assertEquals(NuvioTorrentProfile.Soft, downloadOnly.torrentProfile)
        assertEquals(0L, downloadOnly.diskCacheCapacityBytes)
    }

    @Test
    fun matchingUnsolicitedStopBecomesTerminalError() {
        assertEquals(
            P2pStreamingState.Error("stream expired after inactivity"),
            unexpectedStreamStopError(0L, "stream", "stream", "stream expired after inactivity", "unknown"),
        )
        assertEquals(
            P2pStreamingState.Error("unknown"),
            unexpectedStreamStopError(0L, "stream", "stream", "  ", "unknown"),
        )
    }

    @Test
    fun explicitAndStaleEventsAreIgnored() {
        assertNull(unexpectedStreamStopError(7L, "stream", "stream", "stopped", "unknown"))
        assertNull(unexpectedStreamStopError(0L, "old-stream", "stream", "stopped", "unknown"))
        assertNull(unexpectedTorrentError(9L, "torrent", "torrent", "failed", "unknown"))
        assertNull(unexpectedTorrentError(0L, "old-torrent", "torrent", "failed", "unknown"))
        assertNull(unexpectedTorrentError(0L, null, "torrent", "disk cache budget exceeded", "unknown"))
        assertEquals(
            P2pStreamingState.Error("file write failed"),
            unexpectedTorrentError(0L, "torrent", "torrent", "file write failed", "unknown"),
        )
    }

    @Test
    fun libraryNamesMatchPackagedFiles() {
        assertEquals("libnuvio_engine.dylib", DesktopEngineLibrary.libraryName(DesktopHostOs.MACOS))
        assertEquals("nuvio_engine.dll", DesktopEngineLibrary.libraryName(DesktopHostOs.WINDOWS))
        assertEquals("libnuvio_engine.so", DesktopEngineLibrary.libraryName(DesktopHostOs.LINUX))
    }

    @Test
    fun linuxSourceDirectoriesAreArchitectureSpecific() {
        assertEquals("linux/engine/x86_64", DesktopEngineLibrary.sourceDirectory(DesktopHostOs.LINUX, "x86_64"))
        assertEquals("linux/engine/aarch64", DesktopEngineLibrary.sourceDirectory(DesktopHostOs.LINUX, "aarch64"))
        assertEquals("macos/engine", DesktopEngineLibrary.sourceDirectory(DesktopHostOs.MACOS, "aarch64"))
        assertEquals("windows/engine", DesktopEngineLibrary.sourceDirectory(DesktopHostOs.WINDOWS, "x86_64"))
        assertEquals("aarch64", DesktopEngineLibrary.normalizedArchitecture("arm64"))
        assertEquals("x86_64", DesktopEngineLibrary.normalizedArchitecture("amd64"))
        assertNull(DesktopEngineLibrary.normalizedArchitecture("riscv64"))
    }

    @Test
    fun bundledLibraryLoadsAndCreatesEngine() {
        // An architecture without an engine build packages without P2P
        // support, so there is nothing to load here.
        val library = runCatching { DesktopEngineLibrary.resolve() }.getOrNull() ?: return
        val runtime = NuvioEngineRuntime.load(library)
        assertEquals("0.1.6", runtime.version)

        val root = Files.createTempDirectory("nuvio-engine-desktop-").toFile()
        try {
            val engine = runtime.create(
                buildNuvioEngineConfig(
                    stateDirectory = File(root, "state").apply { mkdirs() },
                    cacheDirectory = File(root, "cache").apply { mkdirs() },
                    uploadEnabled = false,
                    torrentProfile = P2pTorrentProfile.BALANCED,
                    diskCacheCapacityBytes = P2pCacheSize.NONE.bytes,
                ),
            )
            engine.close()
            assertTrue(root.isDirectory)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nestedPayloadMovesToTheEnginePayloadDirectory() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("cached")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("cached", File(cacheDirectory, "payload/$TORRENT_ID/video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun nestedPayloadNeverReplacesCurrentPayload() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            val current = File(cacheDirectory, "payload/$TORRENT_ID").apply { mkdirs() }
            File(current, "video.mkv").writeText("current")
            val legacy = File(cacheDirectory, "payload/payload/$TORRENT_ID").apply { mkdirs() }
            File(legacy, "video.mkv").writeText("stale")

            migrateNestedPayloadDirectory(cacheDirectory)

            assertEquals("current", File(current, "video.mkv").readText())
            assertFalse(File(cacheDirectory, "payload/payload").exists())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    @Test
    fun missingNestedPayloadLeavesCacheUntouched() {
        val cacheDirectory = Files.createTempDirectory("nuvio-engine-cache-").toFile()
        try {
            migrateNestedPayloadDirectory(cacheDirectory)

            assertTrue(cacheDirectory.listFiles().isNullOrEmpty())
        } finally {
            cacheDirectory.deleteRecursively()
        }
    }

    private companion object {
        const val TORRENT_ID = "0123456789abcdef0123456789abcdef01234567"
    }
}
