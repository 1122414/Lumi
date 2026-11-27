package com.lumi.camera.shotplan

import com.lumi.camera.camera.Crop
import com.lumi.camera.camera.Extent
import com.lumi.camera.camera.FrameGeometry
import com.lumi.camera.camera.LumaStats
import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.LightGuidance
import com.lumi.camera.vision.LightPosition
import com.lumi.camera.vision.SceneFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2Milestone4Test {

    private fun testFrame(
        faces: List<FaceBox> = emptyList(),
        box: FaceBox? = null,
        meanLuma: Int? = 128,
        lumaStats: LumaStats? = null,
    ): SceneFrame = SceneFrame(
        sequence = 1L,
        capturedNs = 1_000_000_000L,
        geometry = FrameGeometry(Extent(480, 640), Crop(0, 0, 480, 640), 0),
        observation = null,
        box = box,
        background = null,
        faces = faces,
        meanLuma = meanLuma,
        lumaStats = lumaStats,
    )

    @Test
    fun expandedCompositionChoicesParseAndComputeTargets() {
        val choices = listOf(
            "lower_third" to CompositionChoice.LOWER_THIRD,
            "group_balance" to CompositionChoice.GROUP_BALANCE,
            "scenic_portrait" to CompositionChoice.SCENIC_PORTRAIT,
            "leading_frame" to CompositionChoice.LEADING_FRAME,
        )
        val box = FaceBox(0.25, 0.25, 0.55, 0.65)
        for ((wire, expected) in choices) {
            assertEquals(expected, CompositionChoice.parse(wire))
            val aim = CompositionChoice.aimPointFor(box, expected)
            val desired = CompositionChoice.desiredCenterFor(
                desired0 = Uv(0.5, 0.5),
                captureHeight = box.bottom - box.top,
                liveWidth = box.right - box.left,
                liveHeight = box.bottom - box.top,
                choice = expected,
            )
            assertTrue("aim.x in bounds for $wire", aim.x in 0.05..0.95)
            assertTrue("aim.y in bounds for $wire", aim.y in 0.05..0.95)
            assertTrue("desired.x in bounds for $wire", desired.x in 0.1..0.9)
            assertTrue("desired.y in bounds for $wire", desired.y in 0.1..0.9)
        }
    }

    @Test
    fun recommendationEvaluateAcceptsNewChoicesAndPoseSilhouette() {
        val subject = SelectedSubject.fromBox("女生", person = true, box = FaceBox(0.35, 0.25, 0.62, 0.72), seed = Uv(0.485, 0.42))
        val raw = mapOf(
            "subject" to mapOf(
                "name" to "女生",
                "kind" to "person",
                "box" to listOf(350, 250, 620, 720),
                "point" to listOf(485, 420),
            ),
            "choice" to "scenic_portrait",
            "pose_silhouette" to "full_scenic",
            "confidence" to 0.9,
            "reason" to "留出天空呼吸感",
        )
        val decision = Recommendation.evaluate(raw, subject)
        val accepted = decision as PlanResult.Accepted<Recommendation>
        assertEquals(CompositionChoice.SCENIC_PORTRAIT, accepted.value.choice)
        assertEquals(PoseSilhouette.FULL_BODY_SCENIC, accepted.value.poseSilhouette)
        assertEquals(false, accepted.value.isOfflineBaseline)
    }

    @Test
    fun poseSilhouetteAutoInfersOnlyForPortraits() {
        assertNull(PoseSilhouette.inferForPortrait(CompositionChoice.LEFT_THIRD, 0, isPerson = false))
        assertEquals(PoseSilhouette.COUPLE_GROUP, PoseSilhouette.inferForPortrait(CompositionChoice.GROUP_BALANCE, 2, true))
        assertEquals(PoseSilhouette.FULL_BODY_SCENIC, PoseSilhouette.inferForPortrait(CompositionChoice.SCENIC_PORTRAIT, 1, true))
        assertEquals(PoseSilhouette.HALF_BODY_LEFT, PoseSilhouette.inferForPortrait(CompositionChoice.LEFT_THIRD, 1, true))
        assertEquals(PoseSilhouette.HALF_BODY_RIGHT, PoseSilhouette.inferForPortrait(CompositionChoice.RIGHT_THIRD, 1, true))
        assertEquals(PoseSilhouette.CENTER_PORTRAIT, PoseSilhouette.inferForPortrait(CompositionChoice.CENTER, 1, true))
    }

    @Test
    fun sessionShotMemoryRollsExpiresAndBuildsProgressiveHint() {
        val memory = SessionShotMemory(maxItems = 3, expiryMs = 180_000L)
        assertNull(memory.promptSummary(nowMs = 1_000L))

        memory.record(
            SessionShotRecord(
                capturedAtMs = 1_000L,
                subjectName = "人物",
                choice = CompositionChoice.LEFT_THIRD,
                zoomRatio = 1.0f,
                wasReady = true,
                rollDeg = 0.5,
                lightTag = null,
                tipGiven = null,
            ),
        )
        memory.record(
            SessionShotRecord(
                capturedAtMs = 2_000L,
                subjectName = "人物",
                choice = CompositionChoice.LEFT_THIRD,
                zoomRatio = 1.0f,
                wasReady = true,
                rollDeg = 0.2,
                lightTag = "LEFT_SIDE",
                tipGiven = null,
            ),
        )
        val summary = memory.promptSummary(nowMs = 6_000L)
        assertNotNull(summary)
        assertTrue(summary!!.contains("连拍迭代记忆"))
        assertTrue(summary.contains("本组已连拍2张"))

        // Rolling capacity = 3
        memory.record(SessionShotRecord(7_000L, "人物", CompositionChoice.CENTER, 2.0f, false, 4.2, null, null))
        memory.record(SessionShotRecord(8_000L, "人物", CompositionChoice.RIGHT_THIRD, 2.0f, true, 0.1, null, null))
        assertEquals(3, memory.recentCount(nowMs = 9_000L))

        // Expired after expiryMs
        assertNull(memory.promptSummary(nowMs = 200_000L))
    }

    @Test
    fun onDeviceAdvisorHandlesSinglePortraitGroupScenicAndUserTap() {
        // 1. Single portrait on left -> LEFT_THIRD + HALF_BODY_LEFT
        val singleFace = FaceBox(0.18, 0.22, 0.34, 0.44)
        val singleCand = SubjectCandidate(
            id = 1,
            label = "人物",
            person = true,
            box = FaceBox(0.12, 0.18, 0.40, 0.72),
            seed = Uv(0.26, 0.35),
        )
        val singleAdvice = OnDeviceCompositionAdvisor.advise(
            frame = testFrame(faces = listOf(singleFace), box = singleFace),
            candidates = listOf(singleCand),
            preset = CompositionPreset.AUTO,
            currentZoom = 1f,
            zoomStops = listOf(1f, 2f),
        )
        assertNotNull(singleAdvice)
        assertEquals(CompositionChoice.LEFT_THIRD, singleAdvice!!.choice)
        assertEquals(PoseSilhouette.HALF_BODY_LEFT, singleAdvice.poseSilhouette)
        assertTrue(singleAdvice.isOfflineBaseline)

        // 2. Two people -> GROUP_BALANCE + COUPLE_GROUP
        val f1 = FaceBox(0.18, 0.28, 0.34, 0.48)
        val f2 = FaceBox(0.56, 0.26, 0.72, 0.46)
        val groupAdvice = OnDeviceCompositionAdvisor.advise(
            frame = testFrame(faces = listOf(f1, f2)),
            candidates = listOf(
                SubjectCandidate(1, "人物1", true, FaceBox(0.15, 0.25, 0.38, 0.75), Uv(0.26, 0.40)),
                SubjectCandidate(2, "人物2", true, FaceBox(0.52, 0.23, 0.76, 0.74), Uv(0.64, 0.38)),
            ),
            preset = CompositionPreset.AUTO,
            currentZoom = 1f,
            zoomStops = listOf(1f, 2f),
        )
        assertNotNull(groupAdvice)
        assertEquals(CompositionChoice.GROUP_BALANCE, groupAdvice!!.choice)
        assertEquals(PoseSilhouette.COUPLE_GROUP, groupAdvice.poseSilhouette)

        // 3. Distant person with backlight -> SCENIC_PORTRAIT + LIGHTING directorCue
        val smallFace = FaceBox(0.45, 0.48, 0.51, 0.56)
        val backlitStats = LumaStats(
            meanLuma = 120,
            highlightClipPct = 8,
            shadowClipPct = 5,
            subjectVsBgDiff = -45,
            lightGuidance = LightGuidance(LightPosition.BACKLIT, Uv(0.5, 0.1), "逆光暗脸 · 侧身找光", isActionable = true),
        )
        val scenicAdvice = OnDeviceCompositionAdvisor.advise(
            frame = testFrame(faces = listOf(smallFace), lumaStats = backlitStats),
            candidates = listOf(
                SubjectCandidate(1, "人物", true, FaceBox(0.42, 0.46, 0.56, 0.76), Uv(0.49, 0.54)),
            ),
            preset = CompositionPreset.AUTO,
            currentZoom = 1f,
            zoomStops = listOf(1f, 2f),
        )
        assertNotNull(scenicAdvice)
        assertEquals(CompositionChoice.SCENIC_PORTRAIT, scenicAdvice!!.choice)
        assertEquals(PoseSilhouette.FULL_BODY_SCENIC, scenicAdvice.poseSilhouette)
        assertEquals(DirectorCategory.LIGHTING, scenicAdvice.directorCue?.category)
        assertEquals(2f, scenicAdvice.suggestedZoom)

        // 4. User-picked non-person object -> keeps object subject & no portrait silhouette
        val tapObj = SubjectCandidate(
            id = 2,
            label = "咖啡杯",
            person = false,
            box = FaceBox(0.62, 0.35, 0.85, 0.70),
            seed = Uv(0.73, 0.52),
            userPicked = true,
        )
        val tapAdvice = OnDeviceCompositionAdvisor.advise(
            frame = testFrame(),
            candidates = listOf(singleCand, tapObj),
            preset = CompositionPreset.AUTO,
            currentZoom = 1f,
            zoomStops = listOf(1f, 2f),
            tapUv = Uv(0.72, 0.52),
        )
        assertNotNull(tapAdvice)
        assertEquals("咖啡杯", tapAdvice!!.subject.name)
        assertNull(tapAdvice.poseSilhouette)
    }
}
