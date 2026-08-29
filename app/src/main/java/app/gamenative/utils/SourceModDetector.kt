package app.gamenative.utils

import app.gamenative.data.GameSource
import app.gamenative.service.DownloadService
import app.gamenative.service.SteamService
import java.io.File
import java.io.IOException
import java.nio.file.Files
import timber.log.Timber

/**
 * Detects non-Steam GoldSrc/Source engine mods (folders shipped with a `liblist.gam` or
 * `gameinfo.txt` but no engine of their own, the classic ModDB-style download) and wires
 * them up to run through an already-installed base engine via `-game <moddir>`, the same way
 * they'd be dropped next to `hl.exe`/`hl2.exe` on a PC.
 */
object SourceModDetector {

    private const val TAG = "SourceModDetector"

    enum class EngineType { GOLDSRC, SOURCE }

    /** A detected mod: [contentRoot] is the folder that directly holds the liblist/gameinfo file. */
    data class ModInfo(
        val engineType: EngineType,
        val modDirName: String,
        val displayName: String,
        val contentRoot: File,
    )

    /** An installed game that can host mods of [engineType], found via its engine executable. */
    data class EngineCandidate(
        val appId: String,
        val name: String,
        val folderPath: String,
        val engineExeRelPath: String,
        val engineType: EngineType,
    )

    private val GOLDSRC_ENGINE_EXES = listOf("hl.exe")
    private val SOURCE_ENGINE_EXES = listOf(
        "hl2.exe", "left4dead.exe", "left4dead2.exe", "portal.exe", "portal2.exe",
    )

    private const val GOLDSRC_MARKER = "liblist.gam"
    private const val SOURCE_MARKER = "gameinfo.txt"

    /**
     * Detects whether [folder] is a bare Source/GoldSrc mod: the folder root, or exactly one of
     * its immediate subfolders, contains a liblist.gam/gameinfo.txt naming the mod.
     */
    fun detect(folder: File): ModInfo? {
        if (!folder.isDirectory) return null
        detectInDir(folder)?.let { return it }
        folder.listFiles { f -> f.isDirectory }?.forEach { sub ->
            detectInDir(sub)?.let { return it }
        }
        return null
    }

    private fun detectInDir(dir: File): ModInfo? {
        val liblist = dir.listFiles { f ->
            f.isFile && f.name.equals(GOLDSRC_MARKER, ignoreCase = true)
        }?.firstOrNull()
        if (liblist != null) {
            val name = parseQuotedValue(liblist, "game")?.takeIf { it.isNotBlank() } ?: dir.name
            return ModInfo(EngineType.GOLDSRC, dir.name, name, dir)
        }

        val gameinfo = dir.listFiles { f ->
            f.isFile && f.name.equals(SOURCE_MARKER, ignoreCase = true)
        }?.firstOrNull()
        if (gameinfo != null) {
            val name = parseQuotedValue(gameinfo, "game")?.takeIf { it.isNotBlank() } ?: dir.name
            return ModInfo(EngineType.SOURCE, dir.name, name, dir)
        }

        return null
    }

    /** Reads `"key" "value"` out of a Valve KeyValues-style text file (liblist.gam / gameinfo.txt). */
    private fun parseQuotedValue(file: File, key: String): String? {
        return try {
            val text = file.readText(Charsets.ISO_8859_1)
            val regex = Regex("(?i)\"?\\b${Regex.escape(key)}\\b\"?\\s+\"([^\"]*)\"")
            regex.find(text)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to parse $key from ${file.name}")
            null
        }
    }

    /**
     * Scans every installed Custom Game and Steam app for a folder that contains the engine
     * executable [engineType] needs, so the user can pick which installed game should run the mod.
     */
    fun findInstalledEngineCandidates(engineType: EngineType): List<EngineCandidate> {
        val exeNames = if (engineType == EngineType.GOLDSRC) GOLDSRC_ENGINE_EXES else SOURCE_ENGINE_EXES
        val candidates = mutableListOf<EngineCandidate>()

        for (item in CustomGameScanner.scanAsLibraryItems()) {
            val folderPath = CustomGameScanner.getFolderPathFromAppId(item.appId) ?: continue
            val exe = findEngineExe(File(folderPath), exeNames) ?: continue
            candidates.add(EngineCandidate(item.appId, item.name, folderPath, exe, engineType))
        }

        SteamService.getAllInstalledApps()?.forEach { appInfo ->
            if (!appInfo.isDownloaded) return@forEach
            val folderPath = SteamService.getAppDirPath(appInfo.id)
            val exe = findEngineExe(File(folderPath), exeNames) ?: return@forEach
            val name = SteamService.getAppInfoOf(appInfo.id)?.name ?: "App ${appInfo.id}"
            candidates.add(
                EngineCandidate("${GameSource.STEAM.name}_${appInfo.id}", name, folderPath, exe, engineType),
            )
        }

        return candidates.distinctBy { it.folderPath }
    }

    private fun findEngineExe(folder: File, exeNames: List<String>): String? {
        if (!folder.isDirectory) return null
        val files = folder.listFiles { f -> f.isFile } ?: return null
        for (exeName in exeNames) {
            files.firstOrNull { it.name.equals(exeName, ignoreCase = true) }?.let { return it.name }
        }
        return null
    }

    /**
     * Builds a new managed Custom Game folder for [modInfo]: every top-level entry of the chosen
     * [engine]'s install folder is symlinked in (the engine binary and its base content), and the
     * mod's own files are copied in as a real sibling subfolder named [ModInfo.modDirName] — exactly
     * how the engine expects to find content passed via `-game`.
     *
     * @return the new shell folder, ready to be registered as a Custom Game.
     */
    fun buildModShellFolder(modInfo: ModInfo, engine: EngineCandidate): File {
        val importRoot = importRootMatching(engine.folderPath)
        val baseName = sanitizeFileName(modInfo.displayName.ifBlank { modInfo.modDirName })
        var dest = File(importRoot, baseName)
        var suffix = 1
        while (dest.exists()) {
            dest = File(importRoot, "$baseName ($suffix)")
            suffix++
        }
        if (!dest.mkdirs()) throw IOException("Could not create ${dest.absolutePath}")

        val engineFolder = File(engine.folderPath)
        val engineEntries = engineFolder.listFiles()
            ?: throw IOException("Could not read engine folder ${engineFolder.absolutePath}")
        engineEntries.forEach { entry ->
            if (entry.name.equals(modInfo.modDirName, ignoreCase = true)) return@forEach
            val link = File(dest, entry.name)
            try {
                Files.createSymbolicLink(link.toPath(), entry.toPath().toAbsolutePath().normalize())
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to symlink ${entry.name} from engine folder")
            }
        }

        val modDest = File(dest, modInfo.modDirName)
        if (!modInfo.contentRoot.copyRecursively(modDest, overwrite = true)) {
            throw IOException("Failed to copy mod content from ${modInfo.contentRoot}")
        }

        // Symlinks can't cross Android's internal/external storage boundary — verify the
        // engine executable actually landed rather than leaving a silently broken, exe-less
        // shell folder behind (Files.createSymbolicLink above swallows that failure per-entry).
        val exeFile = File(dest, engine.engineExeRelPath.replace('\\', File.separatorChar))
        if (!exeFile.exists() || !exeFile.isFile) {
            dest.deleteRecursively()
            throw IOException(
                "Could not link ${engine.engineExeRelPath} into the mod folder — the mod's " +
                    "storage location and ${engine.name}'s storage location aren't on the same " +
                    "volume, so symlinks between them aren't supported on this device.",
            )
        }

        return dest
    }

    /**
     * Picks the CustomGames root on the same storage volume as [engineFolderPath], since a
     * symlink can't reliably cross from internal to external storage (or vice versa) on Android.
     */
    private fun importRootMatching(engineFolderPath: String): String {
        val internalBase = DownloadService.baseDataDirPath
        if (internalBase.isNotEmpty() && engineFolderPath.startsWith(internalBase)) {
            val internalRoot = File(internalBase, "CustomGames")
            if (internalRoot.exists() || internalRoot.mkdirs()) return internalRoot.absolutePath
        }
        return CustomGameScanner.importRootPath
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .replace(Regex("[<>:\"/\\\\|?*\\x00-\\x1F]"), "_")
            .trim()
            .trimEnd('.', ' ')
        return cleaned.ifEmpty { "Mod" }
    }
}
