package com.lumi.camera.shotplan

import com.lumi.camera.guidance.Uv
import com.lumi.camera.vision.FaceBox
import com.lumi.camera.vision.LightPosition
import com.lumi.camera.vision.SceneFrame
import com.lumi.camera.vision.SubjectPixels
import kotlin.math.abs

// Zero-network (< 15ms) on-device composition baseline.
// Guarantees instant framing guidance while cloud VLM is thinking AND full offline usability in
// airplane mode, elevators, scenic spots with weak signal, or before any API key is configured.
internal object OnDeviceCompositionAdvisor {

    fun advise(
        frame: SceneFrame,
        candidates: List<SubjectCandidate>,
        preset: CompositionPreset,
        currentZoom: Float,
        zoomStops: List<Float>,
        tapUv: Uv? = null,
    ): Recommendation? {
        val picked = pickSubject(frame, candidates, tapUv) ?: return null
        val box = picked.bounds
        val cx = (box.left + box.right) / 2.0
        val cy = (box.top + box.bottom) / 2.0
        val w = (box.right - box.left).coerceIn(0.02, 1.0)
        val h = (box.bottom - box.top).coerceIn(0.02, 1.0)
        val faceCount = frame.faces.size
        val lightPos = frame.lumaStats?.lightGuidance?.position
        val rollDeg = frame.observation?.pose?.horizonRollDeg() ?: 0.0

        val choice = resolveChoice(
            box = box,
            cx = cx,
            cy = cy,
            w = w,
            h = h,
            isPerson = picked.person,
            faceCount = faceCount,
            preset = preset,
            lightPosition = lightPos,
        )

        val suggestedZoom = resolveZoomSuggestion(
            w = w,
            h = h,
            isPerson = picked.person,
            faceCount = faceCount,
            currentZoom = currentZoom,
            zoomStops = zoomStops,
        )

        val directorCue = resolveDirectorCue(
            isPerson = picked.person,
            faceCount = faceCount,
            rollDeg = rollDeg,
            lightPosition = lightPos,
            choice = choice,
            backgroundClutter = frame.backgroundClutter,
        )

        val poseSilhouette = PoseSilhouette.inferForPortrait(
            choice = choice,
            faceCount = faceCount,
            isPerson = picked.person,
        )

        val reason = buildReason(picked.name, choice, faceCount)
        val aim = CompositionChoice.aimPointFor(box, choice)

        return Recommendation.offline(
            aimPoint = aim,
            reason = reason,
            subject = picked,
            choice = choice,
            suggestedZoom = suggestedZoom,
            directorCue = directorCue,
            poseSilhouette = poseSilhouette,
        )
    }

    private fun pickSubject(
        frame: SceneFrame,
        candidates: List<SubjectCandidate>,
        tapUv: Uv?,
    ): SelectedSubject? {
        // 1. User-tapped candidate always wins
        candidates.firstOrNull { it.userPicked }?.let { return it.toSelectedSubject(null) }

        // 2. Multi-person group: build union subject so nobody gets cut off
        if (frame.faces.size >= 2) {
            val union = SubjectCandidates.unionOf(frame.faces)
            if (union != null) {
                val expanded = expandFaceToBody(union, isGroup = true)
                return SelectedSubject.fromBox(
                    name = "合影",
                    person = true,
                    box = expanded,
                    seed = Uv((expanded.left + expanded.right) / 2.0, (expanded.top + expanded.bottom) / 2.0),
                )
            }
        }

        // 3. Highest-priority detected candidate (person > salient object)
        candidates.firstOrNull()?.let { return it.toSelectedSubject(null) }

        // 4. Single live face directly from ML Kit
        frame.box?.let { face ->
            val body = expandFaceToBody(face, isGroup = false)
            return SelectedSubject.fromBox(
                name = "人物",
                person = true,
                box = body,
                seed = Uv((face.left + face.right) / 2.0, (face.top + face.bottom) / 2.0),
            )
        }

        // 5. Tap point without ML Kit box: synthesize a compact local subject box around tap
        if (tapUv != null) {
            val half = 0.16
            val box = FaceBox(
                left = (tapUv.x - half).coerceIn(0.05, 0.68),
                top = (tapUv.y - half).coerceIn(0.05, 0.68),
                right = (tapUv.x + half).coerceIn(0.32, 0.95),
                bottom = (tapUv.y + half).coerceIn(0.32, 0.95),
            )
            return SelectedSubject.fromBox(
                name = "主体",
                person = false,
                box = box,
                seed = tapUv,
            )
        }

        // 6. Pure edge/contrast saliency fallback from SubjectPixels (architecture / landscape / tabletop)
        val salient = frame.pixels?.let(::estimateSalientBox) ?: return null
        return SelectedSubject.fromBox(
            name = "焦点",
            person = false,
            box = salient,
            seed = Uv((salient.left + salient.right) / 2.0, (salient.top + salient.bottom) / 2.0),
        )
    }

    private fun resolveChoice(
        box: FaceBox,
        cx: Double,
        cy: Double,
        w: Double,
        h: Double,
        isPerson: Boolean,
        faceCount: Int,
        preset: CompositionPreset,
        lightPosition: LightPosition?,
    ): CompositionChoice {
        if (preset != CompositionPreset.AUTO) {
            preset.resolveChoice(box, null)?.let { return it }
        }
        // Multi-person group -> balance whole group safely
        if (faceCount >= 2) {
            return if (box.left < 0.06 || box.right > 0.94 || box.top < 0.06) {
                CompositionChoice.PULL_IN
            } else {
                CompositionChoice.GROUP_BALANCE
            }
        }
        // Edge-clipped subject -> pull safely back inside frame
        if (box.left < 0.05 || box.right > 0.95 || box.top < 0.04 || box.bottom > 0.96) {
            return CompositionChoice.PULL_IN
        }
        if (isPerson) {
            // Wide scenic shot with distant small person -> scenic portrait
            if (w <= 0.28 && h <= 0.42 && cy >= 0.38) {
                return CompositionChoice.SCENIC_PORTRAIT
            }
            // Headroom check
            if (box.top < 0.09) return CompositionChoice.MORE_HEADROOM
            if (box.top > 0.34 && h >= 0.35) return CompositionChoice.TIGHT_TOP
            // Place portrait on the third facing the main light / open space
            val preferLeft = when (lightPosition) {
                LightPosition.RIGHT_SIDE -> true   // person on left, light & breathing room on right
                LightPosition.LEFT_SIDE -> false   // person on right, light & breathing room on left
                else -> cx <= 0.50
            }
            val targetThird = if (preferLeft) CompositionChoice.LEFT_THIRD else CompositionChoice.RIGHT_THIRD
            val targetX = if (preferLeft) 0.36 else 0.64
            return if (abs(cx - targetX) <= 0.06 && box.top in 0.11..0.28) {
                CompositionChoice.KEEP
            } else {
                targetThird
            }
        }
        // Objects / architecture / scenery
        if (w >= 0.48 && abs(cx - 0.50) <= 0.14) {
            return if (abs(cx - 0.50) <= 0.05 && abs(cy - 0.50) <= 0.08) {
                CompositionChoice.KEEP
            } else {
                CompositionChoice.LEADING_FRAME
            }
        }
        if (cy > 0.58 && h < 0.42) {
            return CompositionChoice.LOWER_THIRD
        }
        val preferLeft = cx <= 0.50
        val targetX = if (preferLeft) 0.36 else 0.64
        return if (abs(cx - targetX) <= 0.06) {
            CompositionChoice.KEEP
        } else if (preferLeft) {
            CompositionChoice.LEFT_THIRD
        } else {
            CompositionChoice.RIGHT_THIRD
        }
    }

    private fun resolveZoomSuggestion(
        w: Double,
        h: Double,
        isPerson: Boolean,
        faceCount: Int,
        currentZoom: Float,
        zoomStops: List<Float>,
    ): Float? {
        if (zoomStops.size < 2 || currentZoom > 1.35f) return null
        if (isPerson && faceCount == 1 && w < 0.24 && h < 0.38) {
            return zoomStops.firstOrNull { it in 1.8f..2.6f }
        }
        if (!isPerson && w < 0.18 && h < 0.18) {
            return zoomStops.firstOrNull { it in 1.8f..2.6f }
        }
        return null
    }

    private fun resolveDirectorCue(
        isPerson: Boolean,
        faceCount: Int,
        rollDeg: Double,
        lightPosition: LightPosition?,
        choice: CompositionChoice,
        backgroundClutter: Double?,
    ): DirectorCue? {
        if (abs(rollDeg) > 4.0) {
            return DirectorCue(DirectorCategory.FRAMING, "调平水平线，建筑线条更笔直")
        }
        if (lightPosition == LightPosition.BACKLIT) {
            return DirectorCue(DirectorCategory.LIGHTING, "侧身迎光或轻提曝光保人脸")
        }
        if (lightPosition == LightPosition.TOP_HARSH && isPerson) {
            return DirectorCue(DirectorCategory.LIGHTING, "微仰下巴避开眼窝顶光阴影")
        }
        if (backgroundClutter != null && backgroundClutter > 0.65) {
            return DirectorCue(DirectorCategory.BACKGROUND, "稍微侧移半步避开杂乱背景")
        }
        if (faceCount >= 2) {
            return DirectorCue(DirectorCategory.POSE, "大家向中间轻靠，头肩齐平")
        }
        if (isPerson && (choice == CompositionChoice.LEFT_THIRD || choice == CompositionChoice.RIGHT_THIRD)) {
            return DirectorCue(
                DirectorCategory.POSE,
                if (choice == CompositionChoice.LEFT_THIRD) "身体微向右转，视线留白更自然" else "身体微向左转，视线留白更自然",
            )
        }
        return null
    }

    private fun buildReason(name: String, choice: CompositionChoice, faceCount: Int): String {
        val label = if (faceCount >= 2) "合影" else name
        return when (choice) {
            CompositionChoice.KEEP -> "$label·当前构图平衡"
            CompositionChoice.CENTER -> "$label·居中平衡"
            CompositionChoice.LEFT_THIRD -> "$label·左三分留白"
            CompositionChoice.RIGHT_THIRD -> "$label·右三分留白"
            CompositionChoice.TIGHT_TOP -> "$label·收紧顶部留白"
            CompositionChoice.MORE_HEADROOM -> "$label·上方留呼吸感"
            CompositionChoice.PULL_IN -> "$label·拉回安全区"
            CompositionChoice.LOWER_THIRD -> "$label·低三分显天空"
            CompositionChoice.GROUP_BALANCE -> "合影·重心均衡"
            CompositionChoice.SCENIC_PORTRAIT -> "$label·人景大留白"
            CompositionChoice.LEADING_FRAME -> "$label·线条对称聚焦"
        }
    }

    private fun expandFaceToBody(face: FaceBox, isGroup: Boolean): FaceBox {
        val fw = (face.right - face.left).coerceAtLeast(0.04)
        val fh = (face.bottom - face.top).coerceAtLeast(0.04)
        val padX = if (isGroup) fw * 0.16 else fw * 0.65
        val padTop = fh * 0.25
        val padBottom = if (isGroup) fh * 1.1 else fh * 1.8
        return FaceBox(
            left = (face.left - padX).coerceIn(0.02, 0.94),
            top = (face.top - padTop).coerceIn(0.02, 0.90),
            right = (face.right + padX).coerceIn(0.06, 0.98),
            bottom = (face.bottom + padBottom).coerceIn(0.10, 0.98),
        )
    }

    private fun boxOutline(b: FaceBox): List<Uv> = listOf(
        Uv(b.left, b.top),
        Uv(b.right, b.top),
        Uv(b.right, b.bottom),
        Uv(b.left, b.bottom),
    )

    // Lightweight 6x8 grid contrast-gradient saliency estimator (< 1ms on 128-256px frame).
    internal fun estimateSalientBox(pixels: SubjectPixels): FaceBox? {
        val w = pixels.width
        val h = pixels.height
        if (w < 12 || h < 12) return null
        val cols = 6
        val rows = 8
        val cellW = w / cols
        val cellH = h / rows
        var bestEnergy = 0.0
        var bestCol = 2
        var bestRow = 3
        var totalEnergy = 0.0

        for (r in 0 until rows) {
            for (c in 0 until cols) {
                var energy = 0.0
                val x0 = c * cellW + 1
                val y0 = r * cellH + 1
                val x1 = ((c + 1) * cellW - 1).coerceAtMost(w - 2)
                val y1 = ((r + 1) * cellH - 1).coerceAtMost(h - 2)
                var samples = 0
                var y = y0
                while (y < y1) {
                    var x = x0
                    while (x < x1) {
                        val c0 = pixels.lumaAt(x, y)
                        val cx = pixels.lumaAt(x + 1, y)
                        val cy = pixels.lumaAt(x, y + 1)
                        energy += abs(c0 - cx) + abs(c0 - cy)
                        samples++
                        x += 2
                    }
                    y += 2
                }
                val avg = if (samples > 0) energy / samples else 0.0
                // Slight center-prior weight so random bright corners don't steal focus
                val dx = (c + 0.5) / cols - 0.5
                val dy = (r + 0.5) / rows - 0.5
                val weighted = avg * (1.0 - 0.35 * (dx * dx + dy * dy))
                totalEnergy += weighted
                if (weighted > bestEnergy) {
                    bestEnergy = weighted
                    bestCol = c
                    bestRow = r
                }
            }
        }
        val meanEnergy = totalEnergy / (cols * rows)
        if (bestEnergy < 8.0 || bestEnergy < meanEnergy * 1.15) return null
        val cx = (bestCol + 0.5) / cols
        val cy = (bestRow + 0.5) / rows
        return FaceBox(
            left = (cx - 0.18).coerceIn(0.06, 0.64),
            top = (cy - 0.18).coerceIn(0.06, 0.64),
            right = (cx + 0.18).coerceIn(0.36, 0.94),
            bottom = (cy + 0.18).coerceIn(0.36, 0.94),
        )
    }

    private fun SubjectPixels.lumaAt(x: Int, y: Int): Int {
        val uvx = (x + 0.5) / width
        val uvy = (y + 0.5) / height
        val yuv = at(uvx, uvy) ?: return 128
        return (yuv ushr 16) and 0xFF
    }
}
