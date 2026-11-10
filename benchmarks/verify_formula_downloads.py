"""真实上传并核对 Markdown、ZIP 及原图；公式语义需按固定答案人工复核。"""
from __future__ import annotations

import argparse
import io
import json
from pathlib import Path
import re
import urllib.error
import urllib.request
import uuid
import zipfile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pdf', type=Path)
    parser.add_argument('--url', default='http://127.0.0.1:8083')
    parser.add_argument('--output', type=Path, default=Path('benchmarks/data/results'))
    args = parser.parse_args()
    boundary = uuid.uuid4().hex
    # 固定文件名，避免用户路径中的引号进入 multipart 头。
    data = (f'--{boundary}\r\nContent-Disposition: form-data; name="formulaEnhanced"\r\n\r\ntrue\r\n'
            f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="sample.pdf"\r\n'
            'Content-Type: application/pdf\r\n\r\n').encode()
    data += args.pdf.read_bytes() + f'\r\n--{boundary}--\r\n'.encode()
    base = args.url.rstrip('/')
    request = urllib.request.Request(base + '/api/conversions', data=data,
                                    headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
    try:
        with urllib.request.urlopen(request, timeout=900) as response:
            result = json.load(response)
    except urllib.error.HTTPError as exc:
        raise RuntimeError(exc.read().decode('utf-8', errors='replace')) from exc
    with urllib.request.urlopen(base + result['markdownUrl'], timeout=30) as response:
        markdown = response.read().decode('utf-8')
    assert result['markdown'] == markdown, 'Markdown 下载与 JSON 不一致'
    with urllib.request.urlopen(base + result['archiveUrl'], timeout=30) as response:
        archive_bytes = response.read()
    with zipfile.ZipFile(io.BytesIO(archive_bytes)) as archive:
        assert archive.testzip() is None, 'ZIP CRC 校验失败'
        assert archive.read(result['filename']).decode('utf-8') == markdown, 'ZIP Markdown 不一致'
        images = re.findall(r'\[公式原图\]\((assets/[^)]+)\)', markdown)
        assert len(images) == result['formulaCount'], '公式统计与原图数量不一致'
        for name in images:
            assert archive.read(name).startswith(b'\x89PNG\r\n\x1a\n'), f'原图缺失或格式错误：{name}'
    args.output.mkdir(parents=True, exist_ok=True)
    stem = args.pdf.stem
    (args.output / f'{stem}.response.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (args.output / f'{stem}.md').write_text(markdown, encoding='utf-8')
    (args.output / f'{stem}.zip').write_bytes(archive_bytes)
    print(json.dumps({k: v for k, v in result.items() if k != 'markdown'}, ensure_ascii=False, indent=2))
    print('文件一致性通过。请另外逐式核对标准答案；此检查不计算模型准确率。')


if __name__ == '__main__':
    main()
