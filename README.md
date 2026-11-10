# PDF 转 Markdown 网页工具

本地上传 PDF、预览 Markdown 源码，下载 UTF-8 `.md` 或含图片的 ZIP。独立运行，不需要数据库、云端 API 或知识库服务。

支持文字层提取、逐页扫描件 OCR、常见双栏、完整直线边框表格、嵌入位图导出。可选公式增强使用本地 PaddleOCR 数学模型，将检测到的公式转换为 LaTeX，并保留每个公式的原图供核验。未接入 Docling。

**识别结果需要核对，尤其是公式、低清扫描件和复杂排版。** 文字字符一致率与公式语义正确率采用不同评估方式；详见 [验证记录](docs/formula-evaluation.md)。本工具不承诺任意 PDF、任意公式达到 99%。

## 使用前先看：转换需要多久？

> **公式增强属于实验功能，CPU 转换等待时间较长。** 本机四份单页样本的完整请求耗时约 **75–273 秒（1 分 15 秒至 4 分 33 秒）**，并非瞬间完成。当前代码固定使用 CPU 4 线程，即使电脑有 NVIDIA 显卡也不会自动启用 GPU；尚未实现或验证 GPU 加速。

以下为模型已缓存后的真实 HTTP 实测，含每份 PDF 的模型加载、页面渲染、检测、识别与输出。测试设备为 Windows x64、Intel Core Ultra 9 185H、约 32 GB 内存；RTX 4060 Laptop（约 8 GB 显存）未参与推理。

| 单页样本 | 检测区域数 | 公式增强完整请求耗时 |
| --- | --- | --- |
| 合成公式，文字 PDF | 6 | 75.442 秒 |
| 合成公式，扫描 PDF | 8 | 92.505 秒 |
| 教材 A，文字 PDF | 29 | 273.389 秒 |
| 教材 B，文字 PDF | 23 | 246.226 秒 |

**这些是单页文件整份转换的耗时，不是固定的每页速度。** 多页文档内部复用模型，不能直接将表中时间乘以页数；区域数也不等于正确识别的公式数。测试时其他 CPU 任务、电源模式和温度会影响结果，详见 [实测记录](docs/release-validation.json)。

- 普通文字 PDF 不开公式增强，通常可先按几秒至几十秒预期；扫描 OCR 可先按几十秒至几分钟预期。这两项仅是粗略参考，**没有固定 4 MB 文件的实测基准**。
- **4 MB 不能换算成固定等待时间**：页数、扫描分辨率、公式数量、版式及硬件通常比文件体积更关键。
- 首次安装依赖和下载约 900 MB 公式权重的时间另算，取决于网络；首次转换可能超过默认每页 600 秒超时。缓存后每份 PDF 仍会重新加载模型。
- 当前转换采用同步请求，网页显示等待计时，不提供真实处理进度或后台任务恢复。公式增强同时只允许一个任务。

## 1. 构建与启动

需要 JDK 17、Maven 3.9+。首次构建需要联网下载 Maven 依赖。普通文字 PDF 不需要 Python 或 OCR 模型。

```powershell
git clone https://github.com/Gsb-sib/pdf-to-markdown-tool.git
cd pdf-to-markdown-tool
mvn package
cd pdf-markdown-webapp
.\Start.cmd
```

使用 GitHub 的 Download ZIP 时，解压后进入包含 `pom.xml` 的目录，再从 `mvn package` 开始执行。

打开 <http://localhost:8083/>。`Start.cmd` 自动寻找 Java、启动服务并打开浏览器；`Stop.cmd` 停止该脚本启动的服务。也可在模块目录直接运行：

```powershell
java -jar target/pdf-markdown-webapp-0.0.1-SNAPSHOT.jar
```

Linux/macOS 可使用同样的 Maven 和 `java -jar` 命令，环境变量使用对应 shell 的 `export` 语法；本轮实际验证平台为 Windows x64，Java 17。服务默认仅监听 `127.0.0.1`，无内置登录机制，适合个人本机使用。

## 2. 扫描 PDF：准备 OCR 模型

扫描件需要以下三个配套文件。运行包可直接放入模块目录的 `models/`；源码仓库不包含模型。

```text
pdf-markdown-webapp/models/
  PP-OCRv6_det_small.onnx
  PP-OCRv6_rec_small.onnx
  ppocr_keys_v1.txt
```

有 Python 3 时，可从仓库根目录运行下载脚本。脚本从 PyPI 获取 RapidOCR 3.9.0 官方 wheel，核对 SHA-256，取出两个模型并从识别模型的元数据导出匹配字典，不安装 Python OCR 运行环境。

```powershell
py scripts/download_ocr_models.py
```

也可指定自己已有的配套模型。检测模型、识别模型与字典需匹配，不可混用不同类别数的字典。

```powershell
$env:PDF_OCR_DETECTION_MODEL='D:/models/PP-OCRv6_det_small.onnx'
$env:PDF_OCR_RECOGNITION_MODEL='D:/models/PP-OCRv6_rec_small.onnx'
$env:PDF_OCR_DICTIONARY='D:/models/ppocr_keys_v1.txt'
cd pdf-markdown-webapp
.\Start.cmd
```

普通扫描 OCR 在 Java 内通过 ONNX Runtime 执行。缺少模型时，文字型 PDF 仍可转换；扫描页可能保留失败标记与原图，必须查看提示，不能将转换成功视为文字已正确识别。

## 3. 公式增强：安装与配置

此项为可选功能。默认不勾选，只有启用后才加载数学模型。模型检测公式区域，输出 `$$ ... $$` LaTeX 块；文字页中与正文同一行的公式输出 `$ ... $`。每个区域均保留 `[公式原图](assets/...)` 链接，文字页原图链接汇总于页尾，避免打断正文。正文继续使用原文字层或 OCR。网页显示源码；数学排版需使用支持 LaTeX 的 Markdown 阅读器。

已验证 Python 3.12、PaddlePaddle 3.2.2、PaddleOCR 3.6.0、PaddleX 3.6.1。建议独立虚拟环境，从仓库根目录执行：

若 `py -3.12` 不存在，请先安装 Python 3.12，或将该命令替换为已有 Python 3.12 可执行文件的绝对路径；不要直接使用不受这些固定依赖支持的 Python 版本。

```powershell
py -3.12 -m venv .venv-formula
.\.venv-formula\Scripts\python.exe -m pip install --upgrade pip
.\.venv-formula\Scripts\python.exe -m pip install -r requirements-formula.txt
$env:PDF_FORMULA_PYTHON=(Resolve-Path '.venv-formula/Scripts/python.exe').Path
$env:PDF_FORMULA_CACHE_DIR=(Join-Path (Get-Location) '.venv-formula/model-cache')
cd pdf-markdown-webapp
.\Start.cmd
```

如服务已经启动，请先 `Stop.cmd` 再用设置了环境变量的终端启动。然后上传 PDF，勾选“公式增强”。扫描正文同时需要第 2 节的 ONNX 模型。

默认使用 `PP-DocLayout_plus-L` 检测器和 `PP-FormulaNet_plus-L` 公式模型，CPU 4 线程。首次转换会下载模型，两个权重合计约 900 MB，还需 Python 依赖及缓存空间，建议预留数 GB。下载速度、安装时间和内存占用取决于设备与网络，不能保证固定部署时长。模型已缓存后仍需每份 PDF 加载一次，文档内逐页复用；任务结束释放进程。

允许通过 `PDF_FORMULA_MODEL=PP-FormulaNet_plus-M` 使用较小模型，速度与准确性需重新评估。`PADDLE_PDX_MODEL_SOURCE` 可选择 PaddleX 支持的模型源，默认 `modelscope`。首次下载需联网，缓存完整后可离线推理；不能把单台电脑上的虚拟环境直接复制后当作通用安装包。

公式任务同一时间最多 1 个，忙碌返回 HTTP 429；每页请求默认超时 600 秒（包括首次模型初始化），可设置 `PDF_MARKDOWN_FORMULA_TIMEOUT_SECONDS` 为更大的整数。若模型未配置或推理失败，增强模式返回 422，不生成貌似完整的降级结果；未检测到公式则保留普通提取内容并提示。漏检仍可能发生。

## 4. 网页使用与输出文件

1. 选择或拖入单个 PDF。
2. 按需勾选公式增强，点击转换，等待完成。
3. 在右侧查看、复制 Markdown 源码，检查警告。
4. 下载 `.md`；如需图片或公式原图，下载 ZIP 并完整解压。

生成目录默认为模块工作目录下 `data/conversions/<任务ID>/`：

| 文件 | 用途 |
| --- | --- |
| `document.md` | UTF-8 Markdown，下载名采用原 PDF 基本名称 |
| `document.zip` | 原名称 `.md` 加 `assets/`，图片使用相对路径 |
| `result.json` | 转换统计与下载信息，服务重启后仍可下载 |
| `assets/` | 嵌入位图、公式裁剪原图或 OCR 失败保留图 |
| `formula.log` | 启用公式增强时的模型诊断 |
| `formula-regions.json` | 最后一页的模型区域响应，用于本地诊断 |

上传 PDF 仅作临时文件，处理后删除。成功结果不会自动清理，需自行删除不再需要的任务目录；失败任务会清理，模型错误末尾会记录到服务日志。单独复制 `.md` 不会携带图片，请保留 ZIP 中的目录结构。

## 5. HTTP API

```text
POST /api/conversions                   multipart/form-data
GET  /api/conversions/{id}/markdown      下载 Markdown
GET  /api/conversions/{id}/archive       下载 ZIP
```

上传字段：`file` 为 PDF；`formulaEnhanced` 为可选布尔值，默认 `false`。同步请求完成后一次返回 JSON，当前无后台队列或进度查询接口。

```powershell
curl.exe -F "file=@D:/documents/example.pdf" -F "formulaEnhanced=true" http://localhost:8083/api/conversions
```

JSON 包含 `id`、`filename`、`pageCount`、`scannedPdf`（是否调用过 OCR）、`imageCount`、`durationMillis`、`markdown`、`warnings`、`markdownUrl`、`archiveUrl`、`formulaEnhanced`、`formulaCount`。`formulaCount` 是检测区域数，不能当作真实公式总数或正确数。下载 URL 相对于服务根地址。

主要错误：400 为无效、损坏、空白页数为 0 或需密码的 PDF；404 为不存在的下载；422 为公式配置/识别等无法处理的情况；429 为公式任务忙碌；500 为文件读写或其他转换异常。普通 OCR 失败也可能以 200 加正文诊断标记返回，务必同时检查 `warnings` 和内容。

## 6. 配置与性能

| 环境变量 | 默认 / 用途 |
| --- | --- |
| `PDF_MARKDOWN_PORT` | `8083` |
| `PDF_MARKDOWN_OUTPUT_DIR` | `./data/conversions`，相对于启动工作目录 |
| `PDF_OCR_DETECTION_MODEL` | `./models/PP-OCRv6_det_small.onnx` |
| `PDF_OCR_RECOGNITION_MODEL` | `./models/PP-OCRv6_rec_small.onnx` |
| `PDF_OCR_DICTIONARY` | `./models/ppocr_keys_v1.txt` |
| `PDF_FORMULA_PYTHON` | 空，公式增强需要设置 Python 可执行文件绝对路径 |
| `PDF_FORMULA_CACHE_DIR` | `./data/formula-cache` |
| `PDF_FORMULA_MODEL` | `PP-FormulaNet_plus-L` |
| `PDF_MARKDOWN_FORMULA_TIMEOUT_SECONDS` | `600`，每页模型调用超时 |

上传大小、页数未设置业务上限，上传临时落盘；实际受内存、磁盘、CPU、同步连接超时等限制。4 MB 不能直接换算耗时：文字层 PDF 与数十页扫描件会有很大差异。公式增强每页还需检测与数学推理，实测耗时见验证记录。大文件请先小范围抽页验证并关注磁盘空间。

减少等待时，可优先关闭不需要的公式增强；先用少量代表页核对结果，再处理全文。提前完成模型下载并保留缓存、避免同时运行其他 CPU 密集任务，也能减少额外等待。增大超时只允许等待更久，并不会提高推理速度。较小的公式模型需要重新验证准确性；GPU、模型常驻和批量公式推理目前均未实现，不作为本版本的性能承诺。

## 7. 验证与已知边界

```powershell
mvn test
```

Java 自动测试验证提取、图片及下载、Unicode、输入错误、OCR 和公式区域替换等软件行为。数学模型准确性通过真实 HTTP 上传自制公式 PDF 后，按事先固定的答案人工核对数学结构，不用测试替身结果计算模型准确率。

本版本两模块共 45 项 Java 测试通过（starter 13、网页工具 32）。

- 21 份固定文字/扫描样本、7,702 参考字符：忽略空白的加权字符一致率约 99.74%，保留词间空白约 98.77%。低清两份低于 99%；这是有限样本结果。
- 公式分数不等于文字相似率，分母和实际结果单独记录于 [公式验证](docs/formula-evaluation.md)。
- 固定合成样本中，14 种公式各以文字和扫描表示，共 28 个实例人工结构核对通过；真实教材仍有上下标漏检及混入正文的检测框。公式增强按实验功能发布，不能保证教材公式达到 99%。
- 表格目前针对完整直线网格，复杂合并单元格、无框表格、三栏以上、手写和复杂数学排版需要人工整理。扫描公式先按区域切分；复杂多栏公式可能打乱阅读顺序。旋转文字页需先校正方向再启用公式增强。
- 位图能提取，不保证 PDF 中每个视觉图形都成为完整图片：矢量图、裁切、透明度、遮罩及多个对象拼图不等于单张嵌入位图。公式原图来自页面渲染裁剪。
- 有些 PDF 字体映射错误会产生 `�`，本工具显式标记并提示；置信度提示不能发现所有文字或公式错误。
- 检测框混入常见英文正文词时，文字页保留原文字层并附 `FORMULA_REVIEW_REQUIRED` 标记及原图，不自动套用数学模型的输出。这是保守检查，不能识别所有错误检测，也可能将含文字条件的公式标为待复核。

## 8. 常见问题

- **仍是旧界面**：检查访问端口，关闭旧进程后重新启动，浏览器强制刷新。`Start.cmd` 检测到该端口已有工具时会直接打开已有服务。
- **构建时 JAR 被占用**：Windows 下先 `Stop.cmd`，再 `mvn package`。
- **扫描文字为空/乱码**：检查三个 OCR 资源是否匹配、路径是否正确，查看 `data/app.stdout.log`、`data/app.stderr.log` 及正文诊断标记。
- **公式首次超时**：先确认网络和模型下载，增大超时后重启。每页超过时限会清理失败结果，服务日志保留进程诊断末尾。
- **公式和原图不一致**：以原 PDF 为准修改 LaTeX，不能仅凭成功下载或低置信度提示判断正确。
- **迁移到另一台电脑**：源码需要重新构建或复制可执行 JAR；扫描 OCR 配置模型；公式功能重新安装 Python 依赖并配置缓存。

项目由 `document-processing-spring-boot-starter` 和 `pdf-markdown-webapp` 两个 Maven 模块组成，核心提取、ONNX 解码及公式服务均在网页模块。仓库仅分发源码、测试代码、生成样本脚本和文档，不包含用户 PDF、转换结果、模型权重或个人配置。第三方框架、模型及词典应遵守各自许可；当前未额外授予项目代码的开源许可证。
