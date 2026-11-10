import contextlib
import json
import os
import sys
import time
import traceback

from PIL import Image


def write_json(payload):
    sys.stdout.write(json.dumps(payload, ensure_ascii=True) + "\n")
    sys.stdout.flush()


def build_ocr():
    from rapidocr_onnxruntime import RapidOCR

    return RapidOCR()


def extract(result):
    texts = []
    scores = []
    for item in result or []:
        if len(item) >= 2 and item[1]:
            texts.append(str(item[1]))
        if len(item) >= 3:
            try:
                scores.append(float(item[2]))
            except (TypeError, ValueError):
                pass
    text = "\n".join(texts)
    score = sum(scores) / len(scores) if scores else 0.0
    return text, score, len(texts)


def text_quality(text, score):
    compact = "".join(str(text or "").split())
    if not compact:
        return 0.0, "empty-result"
    useful = 0
    suspicious = 0
    for ch in compact:
        if ch.isalnum() or "\u4e00" <= ch <= "\u9fff":
            useful += 1
        elif ch not in ",.;:!?()[]{}<>/-_+=%#@&*'\"`~|\\，。；：！？（）【】《》、￥“”‘’":
            suspicious += 1
    useful_ratio = useful / max(1, len(compact))
    suspicious_ratio = suspicious / max(1, len(compact))
    quality = useful_ratio * 0.65 + float(score or 0.0) * 0.35 - suspicious_ratio * 0.25
    if len(compact) < 6:
        quality -= 0.15
    if suspicious_ratio > 0.30:
        return quality, "many-unexpected-symbols-possible-garbled-text"
    if useful_ratio < 0.35:
        return quality, "low-readable-character-ratio"
    if score and score < 0.55:
        return quality, "low-confidence"
    return quality, "ok"


def ocr_image(ocr, image_path):
    result, elapse = ocr(image_path)
    text, score, line_count = extract(result)
    quality, reason = text_quality(text, score)
    return {
        "text": text,
        "score": score,
        "lineCount": line_count,
        "quality": quality,
        "reason": reason,
        "engineElapsed": elapse,
        "rotation": 0,
    }


def rotate_image(source, degrees):
    target = f"{source}.rot{degrees}.png"
    with Image.open(source) as image:
        image.rotate(degrees, expand=True).save(target)
    return target


def best_effort_ocr(ocr, image_path):
    candidates = [ocr_image(ocr, image_path)]
    best = candidates[0]
    should_retry = best["score"] < 0.68 or best["reason"] != "ok" or best["lineCount"] == 0
    if should_retry:
        for degrees in (90, 180, 270):
            rotated = None
            try:
                rotated = rotate_image(image_path, degrees)
                candidate = ocr_image(ocr, rotated)
                candidate["rotation"] = degrees
                candidates.append(candidate)
                if candidate["quality"] > best["quality"]:
                    best = candidate
            finally:
                if rotated:
                    try:
                        os.remove(rotated)
                    except OSError:
                        pass
    best["candidates"] = [
        {
            "rotation": item["rotation"],
            "score": item["score"],
            "quality": item["quality"],
            "reason": item["reason"],
        }
        for item in candidates
    ]
    return best


def main():
    try:
        with contextlib.redirect_stdout(sys.stderr):
            ocr = build_ocr()
        write_json({"status": "ready"})
    except Exception as exc:
        write_json({"status": "error", "message": f"Failed to initialize ONNX OCR: {exc}"})
        traceback.print_exc(file=sys.stderr)
        return 2

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        if line == "__quit__":
            break
        started = time.perf_counter()
        try:
            request = json.loads(line)
            image_path = request["imagePath"]
            with contextlib.redirect_stdout(sys.stderr):
                best = best_effort_ocr(ocr, image_path)
            write_json({
                "status": "ok",
                "text": best["text"],
                "score": best["score"],
                "quality": best["quality"],
                "reason": best["reason"],
                "rotation": best["rotation"],
                "candidates": best["candidates"],
                "engineElapsed": best["engineElapsed"],
                "durationMillis": int((time.perf_counter() - started) * 1000),
            })
        except Exception as exc:
            write_json({
                "status": "error",
                "message": str(exc),
                "durationMillis": int((time.perf_counter() - started) * 1000),
            })
            traceback.print_exc(file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
