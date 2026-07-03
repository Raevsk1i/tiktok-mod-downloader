package com.tiktok.regionpatcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.tiktok.regionpatcher.core.InstallErrorMapper
import com.tiktok.regionpatcher.core.PackageInstallHelper
import com.tiktok.regionpatcher.core.PatchCancelledException
import com.tiktok.regionpatcher.core.PatchPipeline
import com.tiktok.regionpatcher.core.RegionPreferences
import com.tiktok.regionpatcher.core.TikTokDetector
import com.tiktok.regionpatcher.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pipeline by lazy { PatchPipeline(this) }
    private var detectedPackage: String? = null
    private var patchJob: Job? = null
    private var pendingInstall: PatchPipeline.Result? = null

    private val installResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != InstallResultReceiver.ACTION_INSTALL_RESULT) return
            val success = intent.getBooleanExtra(InstallResultReceiver.EXTRA_SUCCESS, false)
            val message = intent.getStringExtra(InstallResultReceiver.EXTRA_MESSAGE).orEmpty()
            if (success) {
                pendingInstall = null
                updateInstallButton()
                setStatus(message)
            } else {
                showInstallError(message)
            }
        }
    }

    private val pickApkLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) patchFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        refreshTikTokStatus()
        refreshRegionSummary()

        binding.patchButton.setOnClickListener {
            val install = TikTokDetector.findInstalled(this)
            if (install == null) {
                setStatus(getString(R.string.tiktok_not_found))
                return@setOnClickListener
            }
            runPatch { isCancelled ->
                pipeline.patchInstalledTikTok(install, workDir(), ::setStatus, isCancelled)
            }
        }

        binding.pickApkButton.setOnClickListener {
            pickApkLauncher.launch(arrayOf("application/*", "application/vnd.android.package-archive", "*/*"))
        }

        binding.installButton.setOnClickListener {
            installPendingPatch()
        }

        binding.uninstallButton.setOnClickListener {
            val pkg = pendingInstall?.packageName
                ?: detectedPackage
                ?: TikTokDetector.PACKAGE_CANDIDATES.first()
            val intent = Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())
            startActivity(intent)
        }

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(InstallResultReceiver.ACTION_INSTALL_RESULT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(installResultReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(installResultReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(installResultReceiver)
    }

    override fun onResume() {
        super.onResume()
        refreshTikTokStatus()
        refreshRegionSummary()
        updateInstallButton()
    }

    private fun refreshRegionSummary() {
        binding.regionText.text = getString(
            R.string.region_current,
            RegionPreferences.displaySummary(this),
        )
    }

    private fun refreshTikTokStatus() {
        val install = TikTokDetector.findInstalled(this)
        if (install != null) {
            detectedPackage = install.packageName
            binding.uninstallButton.visibility = View.VISIBLE
            binding.infoText.text = getString(R.string.tiktok_found, install.label) +
                "\n\n" + getString(R.string.info_default)
        } else {
            detectedPackage = null
            if (pendingInstall == null) {
                binding.uninstallButton.visibility = View.GONE
            }
            binding.infoText.text = getString(R.string.info_default)
        }
    }

    private fun patchFromUri(uri: Uri) {
        runPatch { isCancelled ->
            val local = copyUriToCache(uri)
            try {
                pipeline.patchFromUserApk(local, workDir(), ::setStatus, isCancelled)
            } finally {
                local.delete()
            }
        }
    }

    private fun copyUriToCache(uri: Uri): File {
        val rawName = queryDisplayName(uri) ?: "picked.apk"
        val safeName = File(rawName).name
            .replace(Regex("[^a-zA-Z0-9._-]"), "_")
            .take(120)
            .ifBlank { "picked.apk" }
        val out = File(cacheDir, "picked_${System.currentTimeMillis()}_$safeName")
        contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(out).use { output -> input.copyTo(output) }
        } ?: throw IllegalStateException("Не удалось прочитать файл")
        return out
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) {
                return cursor.getString(idx)
            }
        }
        return null
    }

    private fun runPatch(block: suspend (isCancelled: () -> Boolean) -> PatchPipeline.Result) {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            setStatus("Разрешите установку из этого приложения и нажмите снова")
            return
        }

        patchJob?.cancel()
        setBusy(true)
        patchJob = lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    block { !isActive }
                }
                onPatchComplete(result)
            } catch (_: PatchCancelledException) {
                setStatus("Патч отменён")
            } catch (_: OutOfMemoryError) {
                cleanupWorkDirs()
                setStatus(
                    "Недостаточно памяти. Закройте другие приложения и перезапустите приложение.",
                )
            } catch (e: Exception) {
                setStatus(getString(R.string.error_prefix, e.message ?: e.toString()))
            } finally {
                setBusy(false)
                patchJob = null
            }
        }
    }

    private fun onPatchComplete(result: PatchPipeline.Result) {
        val packageName = PackageInstallHelper.resolvePackageName(
            this,
            result.signedApks,
            result.packageName,
        )
        val resolved = result.copy(packageName = packageName)
        pendingInstall = resolved
        updateInstallButton()

        val regionSummary = RegionPreferences.displaySummary(this)
        if (PackageInstallHelper.needsUninstallFirst(this, packageName)) {
            binding.uninstallButton.visibility = View.VISIBLE
            setStatus(
                getString(R.string.uninstall_required) + "\n\n" +
                    getString(R.string.region_current, regionSummary) + "\n\n" +
                    resolved.report.summary(),
            )
            return
        }

        setStatus(
            getString(R.string.install_prompt) + "\n" +
                getString(R.string.region_current, regionSummary) + "\n" +
                resolved.report.summary(),
        )
        pipeline.installApks(resolved.signedApks, resolved.packageName)
    }

    private fun installPendingPatch() {
        val pending = pendingInstall
        if (pending == null) {
            setStatus("Сначала создайте патч")
            return
        }
        val packageName = PackageInstallHelper.resolvePackageName(
            this,
            pending.signedApks,
            pending.packageName,
        )
        if (PackageInstallHelper.needsUninstallFirst(this, packageName)) {
            binding.uninstallButton.visibility = View.VISIBLE
            setStatus(getString(R.string.install_blocked_still_installed))
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            setStatus("Разрешите установку из этого приложения и нажмите снова")
            return
        }
        setStatus(getString(R.string.install_prompt))
        pipeline.installApks(pending.signedApks, pending.packageName)
    }

    private fun updateInstallButton() {
        val hasPending = pendingInstall != null
        binding.installButton.visibility = if (hasPending) View.VISIBLE else View.GONE
        binding.installButton.isEnabled = hasPending && !binding.progressBar.isShown
    }

    private fun showInstallError(message: String) {
        val friendly = InstallErrorMapper.translate(message)
        setStatus("Ошибка установки:\n$friendly")
        AlertDialog.Builder(this)
            .setTitle("Ошибка установки")
            .setMessage(friendly)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun workDir(): File = File(cacheDir, "patch_work").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun cleanupWorkDirs() {
        File(cacheDir, "patch_work").deleteRecursively()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.patchButton.isEnabled = !busy
        binding.pickApkButton.isEnabled = !busy
        binding.uninstallButton.isEnabled = !busy
        binding.installButton.isEnabled = !busy && pendingInstall != null
        binding.settingsButton.isEnabled = !busy
        if (busy) setStatus(getString(R.string.working))
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }
}
