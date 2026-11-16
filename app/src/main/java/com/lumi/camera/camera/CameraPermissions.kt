package com.lumi.camera.camera

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

internal class CameraPermissions(
    private val activity: ComponentActivity,
    onCameraResult: (Boolean) -> Unit,
    private val onAlbumResult: (Boolean) -> Unit,
) {
    private val camera = activity.registerForActivityResult(ActivityResultContracts.RequestPermission(), onCameraResult)
    private val album = activity.registerForActivityResult(ActivityResultContracts.RequestPermission(), onAlbumResult)

    fun hasCamera(): Boolean = granted(Manifest.permission.CAMERA)
    fun requestCamera() = camera.launch(Manifest.permission.CAMERA)
    fun requestAlbum() {
        if (Build.VERSION.SDK_INT <= 28 && !granted(Manifest.permission.WRITE_EXTERNAL_STORAGE))
            album.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        else onAlbumResult(true)
    }

    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
}
