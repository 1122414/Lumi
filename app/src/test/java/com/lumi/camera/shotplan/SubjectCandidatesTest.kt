package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import org.junit.Assert.*
import org.junit.Test

class SubjectCandidatesTest {

    @Test fun ranksMultiFaceGroupPortraitAndDeduplicatesOverlappingObjects() {
        val leftFace = FaceBox(0.24, 0.25, 0.34, 0.38)
        val rightFace = FaceBox(0.60, 0.27, 0.70, 0.40)
        // Object nearly identical to left person's expanded body should be deduplicated.
        val duplicatePersonObj = SubjectCandidates.expandFaceToUpperBody(leftFace)
        val distinctToy = FaceBox(0.42, 0.65, 0.56, 0.85)
        val tinyNoise = FaceBox(0.05, 0.05, 0.08, 0.08)

        val candidates = SubjectCandidates.build(
            faces = listOf(leftFace, rightFace),
            objects = listOf(duplicatePersonObj, distinctToy, tinyNoise),
        )

        assertEquals(4, candidates.size)
        assertEquals(listOf(1, 2, 3, 4), candidates.map { it.id })
        assertTrue("group portrait should rank first", candidates[0].isGroup)
        assertEquals("合照", candidates[0].label)
        assertTrue(candidates[0].person)
        assertTrue(candidates[0].box.left <= leftFace.left && candidates[0].box.right >= rightFace.right)
        assertTrue("distinct object should survive deduplication", candidates.any { !it.person })
    }

    @Test fun userTapPromotesHitCandidateOrSynthesizesTargetAtTapPoint() {
        val objA = FaceBox(0.10, 0.20, 0.35, 0.60)
        val objB = FaceBox(0.62, 0.25, 0.88, 0.75)

        val hitTap = Uv(0.74, 0.48)
        val promoted = SubjectCandidates.build(emptyList(), listOf(objA, objB), tapUv = hitTap)
        assertEquals(1, promoted.first().id)
        assertTrue(promoted.first().userPicked)
        assertFalse(promoted.first().syntheticTap)
        assertEquals(objB, promoted.first().box)
        assertEquals(hitTap.x, promoted.first().seed.x, 1e-6)
        assertEquals(hitTap.y, promoted.first().seed.y, 1e-6)

        val emptySpaceTap = Uv(0.50, 0.82)
        val synthetic = SubjectCandidates.build(emptyList(), listOf(objA), tapUv = emptySpaceTap)
        assertEquals(1, synthetic.first().id)
        assertTrue(synthetic.first().userPicked)
        assertTrue(synthetic.first().syntheticTap)
        assertTrue(emptySpaceTap.x in synthetic.first().box.left..synthetic.first().box.right)
        assertTrue(emptySpaceTap.y in synthetic.first().box.top..synthetic.first().box.bottom)
    }

    @Test fun parsesCompactCandidateChoiceAndSuggestedZoomAndRejectsInvalidReplies() {
        val candidates = SubjectCandidates.build(
            faces = listOf(FaceBox(0.42, 0.24, 0.56, 0.40)),
            objects = emptyList(),
        )
        val stops = listOf(0.6f, 1.0f, 2.0f, 4.0f)

        val valid = ModelReply.from(
            fields = mapOf(
                "candidate" to 1,
                "name" to "女孩",
                "choice" to "left_third",
                "zoom" to 2.0,
                "reason" to "人物左三分右侧留白",
            ),
            complete = true,
            candidates = candidates,
            zoomStops = stops,
            currentZoom = 1.0f,
        )!!
        assertFalse(valid.usedFallback)
        assertEquals(1, valid.candidateId)
        assertEquals(2.0f, valid.suggestedZoom)
        val plan = (valid.decision as PlanResult.Accepted).value
        assertEquals("女孩", plan.subject.name)
        assertEquals(CompositionChoice.LEFT_THIRD, plan.choice)
        assertEquals(2.0f, plan.suggestedZoom)
        assertTrue("left_third aims right of center", plan.aimPoint.x > 0.52)

        // Out-of-bounds candidate id, unknown choice, and unexpected fields must be rejected.
        for (bad in listOf(
            mapOf("candidate" to 9, "choice" to "center", "reason" to "居中"),
            mapOf("candidate" to 1, "choice" to "diagonal", "reason" to "对角"),
            mapOf("candidate" to 1, "choice" to "center", "reason" to ""),
            mapOf("candidate" to 1, "choice" to "center", "reason" to "居中", "extra" to 1),
        )) {
            val rejected = ModelReply.from(bad, complete = true, candidates = candidates, zoomStops = stops, currentZoom = 1.0f)!!
            assertEquals(PlanResult.Rejected(PlanProblem.INVALID_FIELDS), rejected.decision)
        }
    }

    @Test fun resolvesOpticalZoomStopsAndIgnoresCurrentOrInvalidZoom() {
        val stops = listOf(0.6f, 1.0f, 2.0f, 4.0f)
        assertEquals(2.0f, SubjectCandidates.resolveSuggestedZoom(2.0, stops, currentZoom = 1.0f))
        assertEquals(0.6f, SubjectCandidates.resolveSuggestedZoom("0.6x", stops, currentZoom = 1.0f))
        assertNull("already at 1x", SubjectCandidates.resolveSuggestedZoom(1.0, stops, currentZoom = 1.0f))
        assertNull("unlisted stop 7x", SubjectCandidates.resolveSuggestedZoom(7.0, stops, currentZoom = 1.0f))
        assertNull("single lens device", SubjectCandidates.resolveSuggestedZoom(2.0, listOf(1.0f), currentZoom = 1.0f))
        assertEquals("2×", SubjectCandidates.formatZoomLabel(2.0f))
        assertEquals("0.6×", SubjectCandidates.formatZoomLabel(0.6f))
    }

    @Test fun fallbackProtocolWorksWithoutCandidatesAndPromptReflectsMode() {
        val promptFallback = ChatProtocol.promptFor(CompositionPreset.AUTO, candidates = emptyList(), zoomStops = listOf(1f, 2f), currentZoom = 1f)
        assertTrue("fallback prompt asks for subject box", "box" in promptFallback)
        assertTrue("multi-lens prompt lists stops", "1/2" in promptFallback)

        val candidates = SubjectCandidates.build(emptyList(), listOf(FaceBox(0.3, 0.3, 0.7, 0.7)), tapUv = Uv(0.5, 0.5))
        val promptCandidate = ChatProtocol.promptFor(CompositionPreset.AUTO, candidates = candidates, zoomStops = listOf(1f), currentZoom = 1f)
        assertTrue("candidate prompt uses candidate id", "candidate" in promptCandidate)
        assertTrue("user tap instructs candidate=1", "candidate=1" in promptCandidate)
        assertFalse("single-lens omits zoom stops", "可选焦段" in promptCandidate)

        val fallbackReply = ModelReply.from(
            fields = mapOf(
                "subject" to mapOf("name" to "玩偶", "kind" to "object", "box" to listOf(300, 300, 700, 750), "point" to listOf(500, 520)),
                "choice" to "center",
                "zoom" to 2.0,
                "confidence" to 0.85,
                "reason" to "主体居中突出",
            ),
            complete = true,
            candidates = emptyList(),
            zoomStops = listOf(1.0f, 2.0f),
            currentZoom = 1.0f,
        )!!
        assertTrue(fallbackReply.usedFallback)
        assertEquals(2.0f, (fallbackReply.decision as PlanResult.Accepted).value.suggestedZoom)
    }

    @Test fun boxFallbackRecoversWhenSegmentationIsEmptyAndViewpointDriftBlocksFalseMerge() {
        fun pitch(deg: Double): com.lumi.camera.guidance.Pose {
            val h = Math.toRadians(deg) / 2.0
            return com.lumi.camera.guidance.Pose(kotlin.math.cos(h), kotlin.math.sin(h), 0.0, 0.0)
        }
        val upright = pitch(90.0)
        val lens = com.lumi.camera.guidance.Lens(1.0, 1.0, 0.5, 0.5)
        val obs = com.lumi.camera.guidance.Observation(100L, upright, lens, null)
        val pixels = com.lumi.camera.vision.SubjectPixels(64, 64, IntArray(64 * 64) { 0xFF8899AA.toInt() })
        val selected = SelectedSubject.fromBox("浮漂", false, FaceBox(0.25, 0.45, 0.65, 0.85), Uv(0.45, 0.65))

        // Without fallback, empty segmentation returns null; with allowBoxFallback=true it recovers cleanly.
        val noFallback = com.lumi.camera.vision.SubjectOutline.diagnose(obs, selected, pixels, { _, _ -> null }, allowBoxFallback = false)
        assertNull(noFallback.outline)
        val recovered = com.lumi.camera.vision.SubjectOutline.diagnose(obs, selected, pixels, { _, _ -> null }, allowBoxFallback = true)
        assertNotNull(recovered.outline)
        assertEquals("OK_BOX_FALLBACK", recovered.status)

        // Build a RememberedComposition at eye level (pitch=90° -> elevation 0°).
        val geometry = com.lumi.camera.camera.FrameGeometry(
            com.lumi.camera.camera.Extent(64, 64),
            com.lumi.camera.camera.Crop(0, 0, 64, 64),
            0,
        )
        val scene = com.lumi.camera.vision.SceneFrame(100L, 100L, geometry, obs, null, null, pixels)
        val reply = ModelReply.from(
            fields = mapOf("candidate" to 1, "name" to "浮漂", "choice" to "center", "reason" to "居中突出"),
            complete = true,
            candidates = listOf(SubjectCandidate(1, "浮漂", false, selected.bounds, Uv(0.45, 0.65))),
        )!!
        val plan = (reply.decision as PlanResult.Accepted).value
        val remembered = (RememberedComposition.recommended(scene, plan, recovered.outline) as PlanResult.Accepted).value

        // Pitching phone down 30° with a big tracker shift must NOT pull targetPose down to match the downward pitch.
        val pitchedDown = pitch(60.0)
        assertTrue(remembered.isViewpointDrifted(pitchedDown, lens))
        val bigShift = com.lumi.camera.vision.TrackedShift(0.0, -0.35, lens, 200L, matched = true)
        val liveTarget = remembered.targetFor(pitchedDown, lens, bigShift)
        assertTrue(
            "pitched-down phone must remain far (> 15°) from anchored eye-level target",
            pitchedDown.degreesTo(liveTarget.pose) > 15.0,
        )
    }
}
