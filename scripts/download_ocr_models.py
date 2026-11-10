"""从固定版本的 RapidOCR wheel 下载配套资源，核对哈希后仅提取模型。"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import sys
import tempfile
import urllib.request
import zipfile


def varint(stream: io.BufferedIOBase) -> int | None:
    value = 0
    for shift in range(0, 64, 7):
        raw = stream.read(1)
        if not raw:
            if shift == 0:
                return None
            raise ValueError('ONNX 整数被截断')
        value |= (raw[0] & 127) << shift
        if not raw[0] & 128:
            return value
    raise ValueError('ONNX 整数溢出')


def fields(data: bytes):
    """仅读取 protobuf 字段，不依赖 ONNX Python 推理库。"""
    stream = io.BytesIO(data)
    while (tag := varint(stream)) is not None:
        wire = tag & 7
        if wire == 0:
            if varint(stream) is None:
                raise ValueError('ONNX 字段被截断')
        elif wire in (1, 2, 5):
            length = varint(stream) if wire == 2 else (8 if wire == 1 else 4)
            if length is None or length > len(data) - stream.tell():
                raise ValueError('ONNX 字段长度无效')
            if tag >> 3 in (1, 2, 14):
                yield tag >> 3, stream.read(length)
            else:
                stream.seek(length, 1)
        else:
            raise ValueError('不支持的 ONNX protobuf 字段')


def dictionary(model: Path) -> str:
    for number, entry in fields(model.read_bytes()):
        if number == 14:
            values = dict(fields(entry))
            if values.get(1) == b'character':
                return values[2].decode('utf-8')
    raise ValueError('识别模型未嵌入字典')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path,
                        default=Path(__file__).resolve().parents[1] / 'pdf-markdown-webapp' / 'models')
    args = parser.parse_args()
    names = ('PP-OCRv6_det_small.onnx', 'PP-OCRv6_rec_small.onnx', 'ppocr_keys_v1.txt')
    try:
        with urllib.request.urlopen('https://pypi.org/pypi/rapidocr/3.9.0/json', timeout=60) as response:
            metadata = json.load(response)
        wheel = next(item for item in metadata['urls'] if item['filename'].endswith('.whl'))
        if not wheel['url'].startswith('https://files.pythonhosted.org/'):
            raise ValueError('模型资源地址不属于 PyPI 文件域名')
        with tempfile.TemporaryDirectory(prefix='pdf-ocr-models-') as temporary:
            archive_path = Path(temporary) / 'rapidocr.whl'
            print('正在下载 RapidOCR 3.9.0 模型资源…', flush=True)
            with urllib.request.urlopen(wheel['url'], timeout=180) as response, archive_path.open('wb') as output:
                shutil.copyfileobj(response, output)
            actual = hashlib.sha256(archive_path.read_bytes()).hexdigest()
            if actual != wheel['digests']['sha256']:
                raise ValueError('下载文件 SHA-256 不符，未提取模型')
            with zipfile.ZipFile(archive_path) as archive:
                members = {name: f'rapidocr/models/{name}' for name in names[:2]}
                for member in members.values():
                    archive.getinfo(member)
                args.output.mkdir(parents=True, exist_ok=True)
                for name, member in members.items():
                    target = args.output / name
                    staged = Path(temporary) / name
                    with archive.open(member) as source, staged.open('wb') as output:
                        shutil.copyfileobj(source, output)
                    shutil.copyfile(staged, target)
                    print(f'已保存 {target} ({target.stat().st_size:,} bytes)', flush=True)
                # 字典来自该识别模型的元数据，避免下载不匹配的旧版字典。
                target = args.output / names[2]
                target.write_text(dictionary(args.output / names[1]), encoding='utf-8')
                print(f'已导出匹配字典 {target}', flush=True)
        print('资源就绪；请遵守 RapidOCR 及模型的各自许可。')
        return 0
    except (OSError, ValueError, KeyError, StopIteration, zipfile.BadZipFile) as exc:
        print(f'下载失败：{exc}', file=sys.stderr)
        return 1


if __name__ == '__main__':
    raise SystemExit(main())
