"""Offline comparison of the visible bounds of a still photo and two previews."""
import argparse
import json

import cv2
import numpy as np


SIDES = ("左", "右", "上", "下")


def unknown(reason):
    return {"状态": "无法判断", "原因": reason}


def check_still(before, after, mask):
    """Check local patches at their original positions, never align the two previews."""
    h, w = before.shape
    support = []
    # A large tile can let stationary background hide a moving detail near a border.
    rows = np.array_split(np.arange(4, h - 4), (h + 23) // 32)
    cols = np.array_split(np.arange(4, w - 4), (w + 23) // 32)
    for row in rows:
        for col in cols:
            y, yy = row[0], row[-1] + 1
            x, xx = col[0], col[-1] + 1
            if not mask[y-4:yy+4, x-4:xx+4].all():
                continue
            a, b = before[y:yy, x:xx], after[y:yy, x:xx]
            if max(a.std(), b.std()) < 7:
                continue
            if min(a.std(), b.std()) < 3:
                return "快门前后局部画面改变"
            scores = cv2.matchTemplate(after[y-4:yy+4, x-4:xx+4], a, cv2.TM_CCOEFF_NORMED)
            best = scores.max()
            if best < 0.91 or best - scores[4, 4] > 0.012:
                return "快门前后画面移动或局部内容不一致"
            support.append(((x + xx) / (2 * w), (y + yy) / (2 * h)))
    if len(support) < 6 or np.any(np.ptp(support, axis=0) < 0.45):
        return "快门前后缺少分布足够广的稳定参照"
    return None


def matched_points(before, photo, mask):
    sift = cv2.SIFT_create(nfeatures=4000)
    a, da = sift.detectAndCompute(before, mask)
    b, db = sift.detectAndCompute(photo, None)
    if len(a) < 2 or len(b) < 2:
        return np.empty((0, 2)), np.empty((0, 2))

    def unique(src, dst):
        return {m.queryIdx: m.trainIdx for m, n in cv2.BFMatcher().knnMatch(src, dst, k=2)
                if m.distance < 0.72 * n.distance}

    back = unique(db, da)
    pairs = [(a[i].pt, b[j].pt) for i, j in unique(da, db).items() if back.get(j) == i]
    # One vote per small region, even when SIFT finds the same point at several scales.
    cells = {}
    for p, q in pairs:
        cells.setdefault((int(p[0] // 12), int(p[1] // 12)), (p, q))
    pts = np.array(list(cells.values()), dtype=np.float32).reshape(-1, 2, 2) + 0.5
    return pts[:, 0].copy(), pts[:, 1].copy()


def measure(before, after, photo, ignored=()):
    if before.ndim != 2 or before.shape != after.shape:
        raise ValueError("前后预览须为同尺寸的灰度取景画面")
    h, w = before.shape
    if min(h, w, *photo.shape[:2]) < 128:
        raise ValueError("预览与成片每边至少需要 128 像素")
    scale = min(1.0, 1024 / max(w, h))
    size = (round(w * scale), round(h * scale))
    if min(size) < 128:
        raise ValueError("画幅过窄，无法可靠测量")
    mask = np.full_like(before, 255)
    for x, y, width, height in ignored:
        check_rect((x, y, width, height), before)
        mask[y:y+height, x:x+width] = 0
    mask = cv2.resize(mask, size, interpolation=cv2.INTER_NEAREST)
    mask = cv2.erode(mask, np.ones((9, 9), np.uint8))
    before, after, photo = [cv2.resize(im, size, interpolation=cv2.INTER_AREA)
                            for im in (before, after, photo)]
    report = {"原预览尺寸": [w, h], "测量尺寸": list(size)}
    def reject(reason):
        return {**report, "四边": {side: unknown(reason) for side in SIDES}}

    reason = check_still(before, after, mask)
    if reason:
        return reject(reason)
    p, q = matched_points(before, photo, mask)
    if len(p) < 8:
        return reject("成片与预览的唯一参照不足")
    # Map the photo BACK to preview coordinates. Do not warp the images to coincide.
    model, inliers = cv2.estimateAffine2D(q, p, ransacReprojThreshold=1.5)
    if model is None:
        return reject("无法建立可靠的画幅对应关系")
    good = inliers.ravel().astype(bool)
    if (good.sum() < 8 or good.mean() < 0.75 or
            np.any(np.ptp(q[good], axis=0) / size < 0.4)):
        return reject("对应点分散不足或不符合相同的裁切关系")
    if (model[0, 0] <= 0 or model[1, 1] <= 0 or
            max(abs(model[0, 1]) * size[1], abs(model[1, 0]) * size[0]) > 2):
        return reject("成片相对预览有旋转或剪切，不是单纯的画幅差")

    edges = {}
    for i, side in enumerate(SIDES):
        axis = i // 2
        length, cross = size[axis], size[1-axis]
        pd, qd = p[:, axis], q[:, axis]
        if i % 2:
            pd, qd = length - pd, length - qd
        near = (pd < 0.12 * length) & (qd < 0.12 * length)
        valid = near & good
        tangent = p[valid, 1-axis] / cross
        if (valid.sum() < 4 or valid.sum() < 0.75 * near.sum() or
                len(np.unique((tangent * 6).astype(int))) < 3 or np.ptp(tangent) < 0.4):
            edges[side] = unknown("本边缺少沿边分布的可靠参照")
            continue
        # distance in preview = inset at this edge + scale * distance in photo.
        offsets = pd[valid] - model[axis, axis] * qd[valid]
        if np.ptp(np.percentile(offsets, [10, 90])) > 2:
            edges[side] = unknown("本边参照给出的偏差不一致")
            continue
        fraction = float(np.median(offsets)) / length
        edges[side] = {"状态": "可测", "偏差百分比": round(100 * fraction, 3),
                       "原预览像素": round(fraction * (w, h)[axis], 2),
                       "参照数": int(valid.sum())}
    return {**report, "四边": edges}


def check_rect(rect, image):
    x, y, w, h = rect
    if min(x, y) < 0 or min(w, h) <= 0 or x+w > image.shape[1] or y+h > image.shape[0]:
        raise ValueError("矩形超出图像或宽高无效")


def main():
    ap = argparse.ArgumentParser(description="M1 离线四边比对；正偏差表示成片向内裁切")
    ap.add_argument("before", help="快门前预览")
    ap.add_argument("after", help="快门后预览")
    ap.add_argument("photo", help="保存的成片（读取时应用 EXIF 方向）")
    ap.add_argument("--preview-rect", type=int, nargs=4, metavar=("X", "Y", "W", "H"),
                    help="从两张截图取相同取景区域")
    ap.add_argument("--ignore", type=int, nargs=4, action="append", default=[],
                    metavar=("X", "Y", "W", "H"), help="排除调试文字或标记；相对取景区，可重复")
    args = ap.parse_args()
    try:
        imgs = []
        for path in (args.before, args.after, args.photo):
            im = cv2.imread(path, cv2.IMREAD_GRAYSCALE)
            if im is None:
                raise ValueError(f"无法读取图片：{path}")
            if len(imgs) < 2 and args.preview_rect:
                check_rect(args.preview_rect, im)
                x, y, w, h = args.preview_rect
                im = im[y:y+h, x:x+w]
            imgs.append(im)
        print(json.dumps(measure(*imgs, args.ignore), ensure_ascii=False, indent=2))
    except (ValueError, cv2.error) as exc:
        ap.error(str(exc))


if __name__ == "__main__":
    main()
