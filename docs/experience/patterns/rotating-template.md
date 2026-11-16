# 缓慢旋转时，模板变化可能落后于实际图像

M1 的确定性纹理测试里，每帧围绕点选中心转 1°。匹配置信度高时若只给新样本 25% 权重，模板更新滞后，标记在第 4° 消失。保持置信度和候选唯一性门槛不变，在强且唯一的匹配上给新样本 75% 权重后，同一测试连续转至 45°，选中中心保持不变。遮挡和重复纹理的拒绝用例仍通过。

排查时先固定点选中心，分别回放平移、亮度变化和旋转，再看模板的更新能否跟上；不要先放宽匹配门槛。该结论仅在合成序列验证一次，不说明真机复杂背景中不会漂移，也不保证快速旋转或遮挡后能自动恢复。

实现：[PatchTracker.kt](../../../app/src/main/java/com/lumi/camera/camera/PatchTracker.kt)。复现：[PatchTrackerTest.kt](../../../app/src/test/java/com/lumi/camera/camera/PatchTrackerTest.kt) 的 `gradualInPlaneRotationKeepsTheSelectedCenter`。

后续反例：强匹配时无条件学习也会在缓慢平移时漂移。现在仅在外观有变化且相关峰居中时沿用这一更新权重；原旋转回归仍通过。见[慢移时区分弱匹配和模板漂移](subpixel-template.md)。
