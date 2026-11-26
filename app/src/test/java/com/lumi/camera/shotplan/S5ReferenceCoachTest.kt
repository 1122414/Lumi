package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class S5ReferenceCoachTest {

    @Test
    fun parsePortraitReferenceRecipeWithExposureAndPose() {
        val recipe = ReferenceRecipeProtocol.parseFields(
            mapOf(
                "is_portrait" to true,
                "shot_type" to "半身人像打卡",
                "composition" to "人物左三分，右侧留白给建筑",
                "camera_angle" to "微仰拍显身高",
                "lighting" to "侧逆光暖调柔光",
                "pose" to "侧身45度回头笑，右手轻搭栏杆",
                "choice" to "left_third",
                "exposure" to mapOf("ev" to 0.3, "wb" to 5600.0, "reason" to "提亮肤色保留暖调"),
            )
        )
        assertNotNull(recipe)
        assertTrue(recipe!!.isPortrait)
        assertEquals("半身人像打卡", recipe.shotType)
        assertEquals(CompositionChoice.LEFT_THIRD, recipe.targetChoice)
        assertEquals("侧身45度回头笑，右手轻搭栏杆", recipe.poseSummary)
        assertNotNull(recipe.targetExposure)
        assertEquals(0.3f, recipe.targetExposure!!.evOffset!!, 0.01f)
        assertEquals(5600, recipe.targetExposure!!.wbKelvin)

        val prompt = recipe.promptSummary()
        assertTrue(prompt.contains("人像打卡"))
        assertTrue(prompt.contains("动作要点：侧身45度回头笑"))
    }

    @Test
    fun parseNonPortraitRecipeDropsPoseSummary() {
        val recipe = ReferenceRecipeProtocol.parseFields(
            mapOf(
                "is_portrait" to false,
                "shot_type" to "咖啡静物",
                "composition" to "杯子居中俯拍",
                "camera_angle" to "45度俯拍",
                "lighting" to "窗边侧光",
                "pose" to "不应保留",
            )
        )
        assertNotNull(recipe)
        assertFalse(recipe!!.isPortrait)
        assertNull(recipe.poseSummary)
    }

    @Test
    fun referenceGuidanceParsesSpokenPoseCueAndBeyondTip() {
        val guidance = ReferenceGuidance.parse(
            poseRaw = "「肩膀再往右转一点，下巴微抬看斜上方。」",
            beyondRaw = "“借右侧窗光打亮轮廓，比原图更通透。”",
        )
        assertNotNull(guidance)
        assertEquals("肩膀再往右转一点，下巴微抬看斜上方", guidance!!.poseCue)
        assertEquals("借右侧窗光打亮轮廓，比原图更通透", guidance.beyondTip)

        // Control chars are rejected safely.
        assertNull(ReferenceGuidance.parse("正常\n注入", null))
        assertNull(ReferenceGuidance.parse(null, null))
    }

    @Test
    fun referenceGuidanceTruncatesOverlongStringsCleanly() {
        val longCue = "请你把左肩稍微往后收一点然后下巴轻轻抬起来看右上方窗外再自然笑一下保持住不要动"
        val guidance = ReferenceGuidance.parse(longCue, null)
        assertNotNull(guidance)
        assertTrue(guidance!!.poseCue!!.endsWith("…"))
        assertTrue(guidance.poseCue!!.codePointCount(0, guidance.poseCue!!.length) <= ReferenceGuidance.MAX_POSE_CHARS)
    }

    @Test
    fun candidatePromptIncludesDualTrackCopyAndBeyondInstructions() {
        val recipe = ReferenceRecipe(
            isPortrait = true,
            shotType = "全身打卡",
            composition = "人物右三分留白",
            cameraAngle = "低机位微仰拍",
            lightingMood = "夕阳逆光",
            poseSummary = "侧身迈步回头看镜头",
        )
        val candidates = listOf(
            SubjectCandidate(id = 1, label = "女孩", person = true, box = FaceBox(0.3, 0.2, 0.7, 0.9), seed = Uv(0.5, 0.5)),
        )
        val prompt = ChatProtocol.promptFor(
            preset = CompositionPreset.AUTO,
            candidates = candidates,
            referenceRecipe = recipe,
            hasReferenceImage = true,
        )
        assertTrue(prompt.contains("图1是用户想复刻的参考图"))
        assertTrue(prompt.contains("pose_cue"))
        assertTrue(prompt.contains("beyond_tip"))
        assertTrue(prompt.contains("复刻但超越复刻"))
        assertTrue(prompt.contains("侧身迈步回头看镜头"))
    }

    @Test
    fun recommendationAcceptsAndPreservesReferenceGuidance() {
        val candidates = listOf(
            SubjectCandidate(id = 1, label = "女孩", person = true, box = FaceBox(0.3, 0.2, 0.7, 0.85), seed = Uv(0.5, 0.5)),
        )
        val res = Recommendation.evaluateCandidate(
            fields = mapOf(
                "candidate" to 1.0,
                "name" to "女孩",
                "choice" to "left_third",
                "reason" to "左三分复刻打卡机位",
                "focus" to "pose",
                "tip" to "侧身微扬下巴",
                "pose_cue" to "左肩再转15度，右手轻扶栏杆看右上角",
                "beyond_tip" to "把左下角绿叶带入前景，比原图更有层次",
            ),
            candidates = candidates,
        )
        assertTrue(res is PlanResult.Accepted)
        val rec = (res as PlanResult.Accepted).value
        assertNotNull(rec.referenceGuidance)
        assertEquals("左肩再转15度，右手轻扶栏杆看右上角", rec.referenceGuidance!!.poseCue)
        assertEquals("把左下角绿叶带入前景，比原图更有层次", rec.referenceGuidance!!.beyondTip)

        val measured = rec.withMeasuredBounds(FaceBox(0.32, 0.22, 0.68, 0.84))
        assertEquals(rec.referenceGuidance, measured.referenceGuidance)
    }
}
