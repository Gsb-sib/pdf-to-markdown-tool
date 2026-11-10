"""用固定公式生成本地评估 PDF；生成文件不提交到仓库。"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image


INITIAL = [
    r'E=mc^2',
    r'x^2+y^2=z^2',
    r'f(x)=\frac{1}{\sigma\sqrt{2\pi}}e^{-\frac{(x-\mu)^2}{2\sigma^2}}',
    r'\int_0^1 x^2\,dx=\frac{1}{3}',
    r'\sum_{k=1}^n k=\frac{n(n+1)}{2}',
    r'x=\frac{-b\pm\sqrt{b^2-4ac}}{2a}',
]
HOLDOUT = [
    r'a^2+b^2=c^2',
    r'\frac{d}{dx}x^3=3x^2',
    r'\int_0^{\pi}\sin x\,dx=2',
    r'\sum_{j=0}^{m}2^j=2^{m+1}-1',
    r'\lim_{h\to0}\frac{\sin h}{h}=1',
    r'\hat{\theta}=\frac{1}{N}\sum_{i=1}^{N}x_i',
    r'\frac{\partial^2 u}{\partial t^2}=c^2\frac{\partial^2 u}{\partial x^2}',
    r'\sqrt{\frac{a+b}{c-d}}=r',
]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=Path('benchmarks/data'))
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    for name, formulas, font, tag in [('math-layout', INITIAL, 'dejavusans', 'F'),
                                      ('math-holdout', HOLDOUT, 'stix', 'H')]:
        # 答案先固化，后续不得根据识别输出改写。
        reference = args.output / f'{name}-reference.json'
        if reference.exists():
            if json.loads(reference.read_text(encoding='utf-8')) != formulas:
                raise ValueError(f'已有答案不同，拒绝覆盖：{reference}')
        else:
            reference.write_text(json.dumps(formulas, indent=2), encoding='utf-8')
        fig = plt.figure(figsize=(8.27, 11.69))
        fig.text(.06, .96, 'Independent formula audit', fontsize=14)
        for index, formula in enumerate(formulas):
            y = .87 - index * (.105 if name == 'math-holdout' else .14)
            fig.text(.06, y, f'{tag}{index+1}', fontsize=9)
            fig.text(.18, y, '$' + formula + '$', fontsize=21, math_fontfamily=font)
        fig.savefig(args.output / f'{name}.pdf')
        fig.savefig(args.output / f'{name}.png', dpi=200)
        plt.close(fig)
        with Image.open(args.output / f'{name}.png') as image:
            image.convert('RGB').save(args.output / f'{name}-scan.pdf', resolution=200)
    print(f'样本已生成至 {args.output}。不包含自动判定公式语义的分数。')


if __name__ == '__main__':
    main()
