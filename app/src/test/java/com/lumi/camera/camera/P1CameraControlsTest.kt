package com.lumi.camera.camera

import android.view.Surface
import androidx.camera.core.ImageCapture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P1CameraControlsTest {

    @Test
    fun `flash option cycles through four states and maps CameraX flags cleanly`() {
        assertEquals(FlashOption.AUTO, FlashOption.OFF.next())
        assertEquals(FlashOption.ON, FlashOption.AUTO.next())
        assertEquals(FlashOption.TORCH, FlashOption.ON.next())
        assertEquals(FlashOption.OFF, FlashOption.TORCH.next())

        assertEquals(ImageCapture.FLASH_MODE_OFF, FlashOption.OFF.toImageCaptureFlashMode())
        assertEquals(ImageCapture.FLASH_MODE_AUTO, FlashOption.AUTO.toImageCaptureFlashMode())
        assertEquals(ImageCapture.FLASH_MODE_ON, FlashOption.ON.toImageCaptureFlashMode())
        assertEquals(ImageCapture.FLASH_MODE_OFF, FlashOption.TORCH.toImageCaptureFlashMode())
        assertTrue(FlashOption.TORCH.usesTorch)
        assertFalse(FlashOption.ON.usesTorch)
    }

    @Test
    fun `aspect ratio crop computes centered portrait and landscape pixel bounds accurately`() {
        // Portrait 3:4 photo (3000 x 4000)
        val p34 = CaptureAspectRatio.RATIO_3_4.cropRectFor(3000, 4000)
        assertEquals(PixelCropRect(0, 0, 3000, 4000), p34)

        val p11 = CaptureAspectRatio.RATIO_1_1.cropRectFor(3000, 4000)
        assertEquals(PixelCropRect(0, 500, 3000, 3000), p11)

        val p916 = CaptureAspectRatio.RATIO_9_16.cropRectFor(3000, 4000)
        assertEquals(2250, p916.width)
        assertEquals(4000, p916.height)
        assertEquals(375, p916.left)
        assertEquals(0, p916.top)

        // Landscape 4:3 photo (4000 x 3000)
        val l11 = CaptureAspectRatio.RATIO_1_1.cropRectFor(4000, 3000)
        assertEquals(PixelCropRect(500, 0, 3000, 3000), l11)

        val l169 = CaptureAspectRatio.RATIO_9_16.cropRectFor(4000, 3000)
        assertEquals(4000, l169.width)
        assertEquals(2250, l169.height)
        assertEquals(0, l169.left)
        assertEquals(375, l169.top)
    }

    @Test
    fun `timer option cycles and round-trips wire values`() {
        assertEquals(TimerOption.SEC_3, TimerOption.OFF.next())
        assertEquals(TimerOption.SEC_10, TimerOption.SEC_3.next())
        assertEquals(TimerOption.OFF, TimerOption.SEC_10.next())
        TimerOption.entries.forEach {
            assertEquals(it, TimerOption.fromWire(it.wire))
        }
    }

    @Test
    fun `device orientation helper applies hysteresis and maps surface and UI rotations`() {
        // Unknown orientation keeps current bucket
        assertEquals(0, DeviceOrientationHelper.resolveBucketDegrees(-1, 0))
        assertEquals(90, DeviceOrientationHelper.resolveBucketDegrees(-1, 90))

        // Inside hysteresis margin around 45 deg (e.g. 52 deg) does NOT jitter away from 0
        assertEquals(0, DeviceOrientationHelper.resolveBucketDegrees(52, 0))
        // Past hysteresis margin (e.g. 75 deg) switches cleanly to 90
        assertEquals(90, DeviceOrientationHelper.resolveBucketDegrees(75, 0))
        // Switching to landscape left (270 deg)
        assertEquals(270, DeviceOrientationHelper.resolveBucketDegrees(265, 0))

        assertEquals(Surface.ROTATION_0, DeviceOrientationHelper.toSurfaceRotation(0))
        assertEquals(Surface.ROTATION_270, DeviceOrientationHelper.toSurfaceRotation(90))
        assertEquals(Surface.ROTATION_90, DeviceOrientationHelper.toSurfaceRotation(270))

        assertEquals(0f, DeviceOrientationHelper.toUiIconRotation(0), 0.01f)
        assertEquals(-90f, DeviceOrientationHelper.toUiIconRotation(90), 0.01f)
        assertEquals(90f, DeviceOrientationHelper.toUiIconRotation(270), 0.01f)
    }
}
