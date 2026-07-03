package com.tiktok.regionpatcher

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.tiktok.regionpatcher.core.PatchPipeline
import com.tiktok.regionpatcher.core.TikTokDetector
import com.tiktok.regionpatcher.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val pipeline by lazy { PatchPipeline(this) }
    private var detectedPackage: String? = null

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

        binding.patchButton.setOnClickListener {
            val install = TikTokDetector.findInstalled(this)
            if (install == null) {
                setStatus(getString(R.string.tiktok_not_found))
                return@setOnClickListener
            }
            runPatch {
                pipeline.patchInstalledTikTok(install, workDir(), ::setStatus)
            }
        }

        binding.pickApkButton.setOnClickListener {
            pickApkLauncher.launch(arrayOf("application/*", "application/vnd.android.package-archive", "*/*"))
        }

        binding.uninstallButton.setOnClickListener {
            val pkg = detectedPackage ?: TikTokDetector.PACKAGE_CANDIDATES.first()
            val intent = Intent(Intent.ACTION_DELETE, "package:$pkg".toUri())
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTikTokStatus()
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
            binding.uninstallButton.visibility = View.GONE
            binding.infoText.text = getString(R.string.info_default)
        }
    }

    private fun patchFromUri(uri: Uri) {
        runPatch {
            val local = copyUriToCache(uri)
            pipeline.patchFromUserApk(local, workDir(), ::setStatus)
        }
    }

    private fun copyUriToCache(uri: Uri): File {
        val name = queryDisplayName(uri) ?: "picked.apk"
        val out = File(cacheDir, name)
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

    private fun runPatch(block: suspend () -> PatchPipeline.Result) {
        if (!packageManager.canRequestPackageInstalls()) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:$packageName"),
            )
            startActivity(intent)
            setStatus("Разрешите установку из этого приложения и нажмите снова")
            return
        }

        setBusy(true)
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { block() }
                setStatus(getString(R.string.done) + "\n" + result.report.summary())
                pipeline.installApks(result.signedApks)
            } catch (e: Exception) {
                setStatus(getString(R.string.error_prefix, e.message ?: e.toString()))
            } finally {
                setBusy(false)
            }
        }
    }

    private fun workDir(): File = File(cacheDir, "patch_work").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun setBusy(busy: Boolean) {
        binding.progressBar.visibility = if (busy) View.VISIBLE else View.GONE
        binding.patchButton.isEnabled = !busy
        binding.pickApkButton.isEnabled = !busy
        if (busy) setStatus(getString(R.string.working))
    }

    private fun setStatus(text: String) {
        runOnUiThread { binding.statusText.text = text }
    }
}
