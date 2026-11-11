"""本地公式识别进程：仅接收本机渲染图片路径，逐行返回公式区域及 LaTeX。"""
from __future__ import annotations

import contextlib
import hashlib
from collections import OrderedDict
import json
import os
import sys
from pathlib import Path

PREFIX = '__FORMULA_JSON__'


def main() -> None:
    # 第三方模型的日志写入 stderr，避免污染进程通信。
    with contextlib.redirect_stdout(sys.stderr):
        from paddleocr import FormulaRecognitionPipeline
        pipeline = FormulaRecognitionPipeline(
            use_doc_orientation_classify=False,
            use_doc_unwarping=False,
            formula_recognition_model_name=os.getenv('PDF_FORMULA_MODEL', 'PP-FormulaNet_plus-L'),
            formula_recognition_batch_size=4,
            device='cpu', cpu_threads=4, enable_mkldnn=True,
        )
    # 仅在进程内缓存区域和 LaTeX，不保留页面图片；重复页无需再次推理。
    cache: OrderedDict[str, dict] = OrderedDict()
    for line in sys.stdin:
        try:
            request = json.loads(line)
            image = Path(request['image']).resolve(strict=True)
            if not image.is_file():
                raise ValueError('公式识别输入不是文件')
            digest = hashlib.sha256(image.read_bytes()).hexdigest()
            if digest in cache:
                cache.move_to_end(digest)
                print(PREFIX + json.dumps(cache[digest], ensure_ascii=True), flush=True)
                continue
            with contextlib.redirect_stdout(sys.stderr):
                predictions = list(pipeline.predict(str(image)))
            if len(predictions) != 1:
                raise ValueError('公式识别返回的页面数不正确')
            regions = []
            for item in predictions[0]['formula_res_list']:
                latex = str(item['rec_formula']).strip()
                if not latex:
                    raise ValueError('公式模型返回空内容')
                regions.append({'latex': latex, 'box': [float(x) for x in item['dt_polys']]})
            response = {'regions': regions}
            cache[digest] = response
            if len(cache) > 32:
                cache.popitem(last=False)
        except Exception as exc:
            response = {'error': f'{type(exc).__name__}: {exc}'}
        print(PREFIX + json.dumps(response, ensure_ascii=True), flush=True)


if __name__ == '__main__':
    main()
