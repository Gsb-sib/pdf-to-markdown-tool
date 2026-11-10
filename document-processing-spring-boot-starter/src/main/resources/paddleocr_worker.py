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


def env_bool(name, default=False):
    value = os.environ.get(name)
    if value is None:
        return default
    return value.lower() in ("1", "true", "yes", "on")


def build_ocr():
    from paddleocr import PaddleOCR

    language = os.environ.get("DOCUMENT_OCR_PADDLE_LANGUAGE", "ch")
    version = os.environ.get("DOCUMENT_OCR_PADDLE_VERSION") or None
    kwargs = {
        "lang": language,
        "use_doc_orientation_classify": False,
        "use_doc_unwarping": False,
        "use_textline_orientation": False,
    }
    if version:
        kwargs["ocr_version"] = version
    return PaddleOCR(**kwargs)


def extract_text(result):
    texts = []
    scores = []
    for page in result or []:
        if isinstance(page, dict):
            texts.extend(str(text) for text in page.get("rec_texts") or [] if text)
            for score in page.get("rec_scores") or []:
                try:
                    scores.append(float(score))
                except (TypeError, ValueError):
                    pass
        elif hasattr(page, "json"):
            data = json.loads(page.json)
            texts.extend(str(text) for text in data.get("rec_texts") or [] if text)
    avg_score = sum(scores) / len(scores) if scores else 0.0
    return "\n".join(texts), avg_score, len(texts)


def is_expected_punctuation(ch):
    code = ord(ch)
    return (
        ch in ",.;:!?()[]{}<>/-_+=%#@&*'\"`~|\\"
        or 0x3000 <= code <= 0x303F
        or 0xFF00 <= code <= 0xFFEF
        or 0x2000 <= code <= 0x206F
    )


def text_quality(text, score):
    compact = "".join(str(text or "").split())
    if not compact:
        return 0.0, "empty-result"
    useful = 0
    suspicious = 0
    question_marks = 0
    for ch in compact:
        code = ord(ch)
        if ch == "?" or ch == "\uff1f":
            question_marks += 1
        if ch.isalnum() or 0x4E00 <= code <= 0x9FFF:
            useful += 1
        elif not is_expected_punctuation(ch):
            suspicious += 1
    useful_ratio = useful / max(1, len(compact))
    suspicious_ratio = suspicious / max(1, len(compact))
    question_ratio = question_marks / max(1, len(compact))
    quality = useful_ratio * 0.55 + float(score or 0.0) * 0.35 - suspicious_ratio * 0.20 - question_ratio * 0.45
    if question_ratio > 0.25:
        return quality, "garbled-question-mark-heavy-result"
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
    result = ocr.predict(image_path)
    text, score, line_count = extract_text(result)
    quality, reason = text_quality(text, score)
    return {
        "text": text,
        "score": score,
        "lineCount": line_count,
        "quality": quality,
        "reason": reason,
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
    rotation_retry = env_bool("DOCUMENT_OCR_ENABLE_ROTATION_RETRY", False)
    should_retry = rotation_retry and (best["score"] < 0.68 or best["reason"] != "ok" or best["lineCount"] == 0)
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
        write_json({"status": "error", "message": f"Failed to initialize PaddleOCR: {exc}"})
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
