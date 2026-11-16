import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

import cv2
import numpy as np

from calibration import chart
from compare import measure, SIDES


def cut(im, left, right, top, bottom):
    h, w = im.shape
    return im[top:h-bottom, left:w-right]


class FramingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.scene = chart()

    def assert_offsets(self, report, expected):
        for side, truth in zip(SIDES, expected):
            edge = report["四边"][side]
            if truth is None:
                self.assertEqual("无法判断", edge["状态"], (side, edge))
                self.assertNotIn("偏差百分比", edge)
            else:
                self.assertEqual("可测", edge["状态"], (side, edge))
                self.assertAlmostEqual(truth, edge["偏差百分比"], delta=0.15, msg=(side, edge))
                px = truth / 100 * (600 if side in ("左", "右") else 800)
                self.assertAlmostEqual(px, edge["原预览像素"], delta=1.2, msg=(side, edge))

    def test_one_percent_each_side_and_individually(self):
        for pct in [(1, 1, 1, 1), *np.eye(4, dtype=int), (0, 0, 0, 0)]:
            with self.subTest(pct=list(pct)):
                photo = cut(self.scene, *(np.array(pct) * [6, 6, 8, 8]))
                self.assert_offsets(measure(self.scene, self.scene, photo), pct)

    def test_wider_photo_has_negative_offsets(self):
        pre = cut(self.scene, 6, 6, 8, 8)
        edges = measure(pre, pre, self.scene)["四边"]
        for side in SIDES:
            self.assertAlmostEqual(-100 / 98, edges[side]["偏差百分比"], delta=0.15)

    def test_motion_invalidates_every_edge_even_if_photo_matches_before(self):
        after = np.roll(self.scene, (0, 5), axis=(0, 1))
        local = self.scene.copy()
        local[95:143, 70:118] = after[95:143, 70:118]
        rotate = cv2.getRotationMatrix2D((300, 400), 1.3, 1)
        for changed in (after, local, np.roll(self.scene, 1, axis=0),
                        cv2.warpAffine(self.scene, rotate, (600, 800))):
            self.assert_offsets(measure(self.scene, changed, self.scene), [None] * 4)

    def test_recompressed_static_previews_with_changed_exposure_remain_measurable(self):
        brighter = cv2.convertScaleAbs(self.scene, alpha=0.85, beta=24)
        after = cv2.imdecode(cv2.imencode(".jpg", brighter)[1], cv2.IMREAD_GRAYSCALE)
        self.assert_offsets(measure(self.scene, after, cut(self.scene, 6, 6, 8, 8)), (1, 1, 1, 1))

    def test_missing_reference_in_the_photo_alone_invalidates_only_that_side(self):
        photo = cut(self.scene, 6, 6, 8, 8).copy()
        photo[-140:] = 160
        self.assert_offsets(measure(self.scene, self.scene, photo), (1, 1, 1, None))

    def test_one_cluster_at_a_corner_cannot_sign_off_an_entire_edge(self):
        scene = self.scene.copy()
        scene[120:, :110] = 205
        self.assert_offsets(measure(scene, scene, cut(scene, 6, 6, 8, 8)), (None, 1, 1, 1))

    def test_rotation_in_the_saved_photo_is_not_reported_as_a_crop(self):
        rotate = cv2.getRotationMatrix2D((300, 400), 2, 1)
        photo = cv2.warpAffine(self.scene, rotate, (600, 800))
        self.assert_offsets(measure(self.scene, self.scene, photo), [None] * 4)

    def test_blank_or_repeated_left_band_does_not_borrow_other_edges(self):
        for repeated in (False, True):
            scene = self.scene.copy()
            yy, xx = np.indices((800, 110))
            scene[:, :110] = 205 if not repeated else 20 + 180 * ((yy // 8 + xx // 8) % 2)
            self.assert_offsets(measure(scene, scene, cut(scene, 6, 6, 8, 8)), (None, 1, 1, 1))

    def test_unrelated_photo_and_textureless_previews_are_inconclusive(self):
        noise = np.random.default_rng(33).integers(0, 256, self.scene.shape, np.uint8)
        self.assert_offsets(measure(self.scene, self.scene, noise), [None] * 4)
        flat = np.full_like(self.scene, 125)
        self.assert_offsets(measure(flat, flat, flat), [None] * 4)

    def test_cli_with_preview_rect_mask_and_rotated_high_resolution_jpeg(self):
        # An EXIF APP1 segment with a single little-endian TIFF orientation entry: 6.
        exif = bytes.fromhex("ffe1002245786966000049492a0008000000010012010300010000000600000000000000")
        with tempfile.TemporaryDirectory() as tmp:
            d = Path(tmp)
            pre = cv2.copyMakeBorder(self.scene, 40, 40, 12, 12, cv2.BORDER_CONSTANT)
            post = pre.copy()
            post[50:82, 22:108] = 10
            cv2.imwrite(str(d / "pre.png"), pre)
            cv2.imwrite(str(d / "post.png"), post)
            photo = cv2.resize(cut(self.scene, 6, 6, 8, 8), (1200, 1600))
            jpeg = cv2.imencode(".jpg", cv2.rotate(photo, cv2.ROTATE_90_COUNTERCLOCKWISE))[1].tobytes()
            (d / "photo.jpg").write_bytes(jpeg[:2] + exif + jpeg[2:])
            result = subprocess.run([sys.executable, str(Path(__file__).with_name("compare.py")),
                                     str(d / "pre.png"), str(d / "post.png"), str(d / "photo.jpg"),
                                     "--preview-rect", "12", "40", "600", "800",
                                     "--ignore", "10", "10", "86", "32"],
                                    check=True, capture_output=True, text=True)
            self.assert_offsets(json.loads(result.stdout), (1, 1, 1, 1))


if __name__ == "__main__":
    unittest.main()
