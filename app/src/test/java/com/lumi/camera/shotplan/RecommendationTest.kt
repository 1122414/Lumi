package com.lumi.camera.shotplan

import com.lumi.camera.guidance.*
import com.lumi.camera.vision.FaceBox
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan
import org.junit.Assert.*
import org.junit.Test

class RecommendationTest {
    private val source = Observation(10L, Pose(1.0, 0.0, 0.0, 0.0), Lens(1.1, 0.9, 0.5, 0.5),
        SubjectPosition(7, Uv(0.55, 0.48), 0.14))
    private val box = FaceBox(0.48, 0.38, 0.62, 0.58)
    private val selected = mapOf("name" to "玩偶", "kind" to "object", "outline" to listOf(
        listOf(400,300),listOf(500,350),listOf(600,300),listOf(640,680),listOf(500,700),listOf(370,680)))
    private fun fields(x: Double = 0.5, y: Double = 0.5): Map<String, Any?> = mapOf(
        "aimPoint" to mapOf("x" to x, "y" to y), "confidence" to 0.82,
        "alreadyGood" to (x == 0.5 && y == 0.5), "reason" to "玩偶视线前留白", "subject" to selected)
    private fun checkPoint(expected: Uv, actual: Uv?) {
        assertNotNull(actual)
        assertEquals(expected.x, actual!!.x, 1e-7)
        assertEquals(expected.y, actual.y, 1e-7)
    }

    @Test fun centerRightAndUpUseTheSentUprightViewIncludingAnOffCenterLens() {
        val from = source.copy(lens = source.lens.copy(cx = 0.47, cy = 0.53))
        for (aim in listOf(Uv.CENTER, Uv(0.68, 0.5), Uv(0.5, 0.32), Uv(0.62, 0.39))) {
            val target = Recommendation.validated(fields(aim.x, aim.y))!!.targetFrom(from, box)!!
            val guide = RotationGuidance(target).update(from, from.timeNs, true)
            checkPoint(aim, guide.ring)
            assertFalse("a model verdict never skips settling", guide.ready)
        }
    }

    @Test fun phoneHasAlreadyTurnedWhileWaitingForTheModel() {
        val angle = Math.toRadians(8.0)
        fun yaw(a: Double) = Pose(cos(a/2), 0.0, -sin(a/2), 0.0)
        val sent = source.copy(pose = yaw(0.7))
        val aimX = 0.5 + sent.lens.fx * tan(angle)
        val target = Recommendation.validated(fields(aimX, 0.5))!!.targetFrom(sent, box)!!
        val turned = sent.copy(timeNs = 5_000_000_000, pose = sent.pose * yaw(angle))
        val face = sent.subject!!
        val actual = turned.copy(subject = face.copy(center = RotationProjection(sent, turned).project(face.center)!!))
        val guidance = RotationGuidance(target)
        checkPoint(Uv.CENTER, guidance.update(actual, actual.timeNs, true).ring)
        var ready = false
        for (i in 1..8) {
            val frame = actual.copy(timeNs = actual.timeNs + i * 50_000_000L)
            ready = guidance.update(frame, frame.timeNs, true).ready
        }
        assertTrue(ready)
    }

    @Test fun ringMovesTowardsCenterAndCrossesItWhenThePhoneOvershootsBeforeTheReply() {
        fun yaw(degrees: Double): Pose {
            val half = Math.toRadians(degrees) / 2
            return Pose(cos(half), 0.0, -sin(half), 0.0)
        }
        val target = Recommendation.validated(fields(0.5 + source.lens.fx * tan(Math.toRadians(8.0)), 0.5))!!
            .targetFrom(source, box)!!
        for (angle in listOf(3.0, 12.0)) {
            val turned = source.copy(timeNs = 9_000_000_000L, pose = source.pose * yaw(angle))
            val face = source.subject!!
            val current = turned.copy(subject = face.copy(center = RotationProjection(source, turned).project(face.center)!!))
            val guide = RotationGuidance(target).update(current, current.timeNs, true)
            checkPoint(Uv(0.5 + source.lens.fx * tan(Math.toRadians(8.0 - angle)), 0.5), guide.ring)
            assertFalse(guide.ready)
        }
    }

    @Test fun rejectsMissingExtraWrongTypeOutOfBoundsLowConfidenceAndContradictoryFields() {
        val valid = fields()
        for (key in valid.keys) assertNull(Recommendation.validated(valid - key))
        for (change in listOf("extra" to 1, "aimPoint" to mapOf("x" to 0.5),
            "aimPoint" to mapOf("x" to 0.5, "y" to -0.01), "aimPoint" to mapOf("x" to "0.5", "y" to 0.5),
            "confidence" to 0.69, "confidence" to 1.01, "confidence" to Double.NaN, "confidence" to "0.9",
            "alreadyGood" to "true", "reason" to "", "reason" to 3)) {
            assertNull(change.first, Recommendation.validated(valid + change))
        }
        assertNull(Recommendation.validated(fields(0.55, 0.5) + ("alreadyGood" to true)))
    }

    @Test fun bottomCaptionKeepsShortReasonsAndFitsTenUnicodeCharacters() {
        fun caption(reason: String) = Recommendation.validated(fields() + ("reason" to reason))!!.caption
        assertEquals("玩偶·视线前方留白", caption("视线前方留白"))
        assertEquals("玩偶突出", caption("主体突出，背景的线条更干净"))
        assertEquals("玩偶·窗边的光线衬…", caption("窗边的光线衬托出了玩偶的笑容"))
        assertEquals("玩偶" + "视".repeat(7) + "𠮷", caption("玩偶" + "视".repeat(7) + "𠮷"))
        assertEquals("玩偶" + "视".repeat(6) + "𠮷…", caption("玩偶" + "视".repeat(6) + "𠮷留白"))
    }

    @Test fun aRejectedAimOrLowScoreStillIdentifiesTheToyAnywhereInTheFrame() {
        for (dx in listOf(-275, 0, 265)) {
            val shifted = selected + ("outline" to listOf(listOf(400+dx,300), listOf(500+dx,350),
                listOf(600+dx,300), listOf(640+dx,680), listOf(500+dx,700), listOf(370+dx,680)))
            val valid = fields() + ("subject" to shifted)
            for (fields in listOf(valid + ("confidence" to 0.56), valid - "aimPoint", valid + ("reason" to null))) {
                val reply = ModelReply.from(fields, complete = true)!!
                assertEquals("玩偶", reply.name)
                assertNotNull(reply.subject)
                assertNull(reply.plan)
            }
            val partial = ModelReply.from(valid, complete = false)!!
            assertNotNull(partial.subject)
            assertNull(partial.plan)
            assertNotNull(ModelReply.from(valid, complete = true)!!.plan)
        }
    }

    @Test fun aDetailedRealModelSizedContourCanGenerateAPlan() {
        val points = (0 until 41).map {
            val angle = it * 2 * Math.PI / 41
            listOf((660 + 120 * cos(angle)).toInt(), (510 + 200 * sin(angle)).toInt())
        }
        val detailed = selected + ("outline" to points)
        val reply = ModelReply.from(fields(0.62, 0.5) + ("subject" to detailed), complete = true)!!
        assertEquals(41, reply.subject!!.outline.size)
        assertNotNull(reply.plan)
    }

    @Test fun aNameWithoutUsableGeometryDoesNotCreateAnOutlineOrAGreenRing() {
        val missing = fields() + ("subject" to mapOf("name" to "兔子玩偶", "kind" to "object"))
        val reply = ModelReply.from(missing, complete = true)!!
        assertEquals("兔子玩偶", reply.name)
        assertNull(reply.subject)
        assertNull(reply.plan)
        assertNull(ModelReply.from(fields() - "subject", complete = true))
    }

    @Test fun modelTextMustUseJsonBooleansNumbersAndStringEscapes() {
        val example = """{"aimPoint":{"x":0.5,"y":5e-1},"confidence":1,"alreadyGood":true,"reason":"人物与\u80cc景。"}"""
        assertTrue(Recommendation.hasJsonTokens(example))
        for (bad in listOf(example.replace("true", "TRUE"), example.replace("0.5", ".5"),
            example.replace("\\u80cc", "\\x55"), example.replace("景", "\t景"),
            "'aimPoint'", "NaN", "null", "[]")) assertFalse(Recommendation.hasJsonTokens(bad))
    }

    @Test fun aVisibleCenterDoesNotExcuseClippingTheFaceAtItsCorner() {
        val left = source.copy(subject = SubjectPosition(8, Uv(0.19, 0.5), 0.26))
        val plan = Recommendation.validated(fields(0.60, 0.5))!!
        val smaller = plan.targetFrom(left, FaceBox(0.18, 0.48, 0.20, 0.52))
        assertNotNull(smaller)
        assertNull(plan.targetFrom(left, FaceBox(0.06, 0.38, 0.32, 0.62)))
    }

    @Test fun onlyTheNewestLiveRequestCanInstallATargetAndOnlyOnce() {
        val requests = RecommendationRequests()
        val old = requests.begin(0)
        val new = requests.begin(1_000_000)
        assertEquals(RecommendationRequests.Completion.OBSOLETE, requests.finish(old, 5_000_000))
        assertEquals(RecommendationRequests.Completion.ACCEPT, requests.finish(new, 6_000_000))
        assertEquals(RecommendationRequests.Completion.OBSOLETE, requests.finish(new, 7_000_000))
        val backgrounded = requests.begin(8_000_000)
        requests.cancel()
        assertEquals(RecommendationRequests.Completion.OBSOLETE, requests.finish(backgrounded, 9_000_000))
        val slow = requests.begin(10_000_000)
        assertEquals(RecommendationRequests.Completion.EXPIRED, requests.finish(slow, slow.deadlineNs))
    }
}
