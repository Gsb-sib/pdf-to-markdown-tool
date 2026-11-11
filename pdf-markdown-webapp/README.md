# PDF 转 Markdown 网页模块

完整的构建、OCR 模型准备、可选公式增强安装、环境变量、API、输出文件和故障排查见[根目录使用说明](../README.md)。

从仓库根目录运行 `mvn package`，进入本目录运行 `Start.cmd`，默认地址为 http://localhost:8083/ 。使用 `Stop.cmd` 停止脚本启动的服务。也可运行 `java -jar target/pdf-markdown-webapp-0.0.1-SNAPSHOT.jar`。

本模块复用 starter 接口，包含独立逐页提取器、ONNX 解码、公式模型进程和静态网页。普通文字提取不需要 Python，扫描 OCR 需要匹配的 ONNX 模型，可选公式增强需要独立 Python 数学模型环境。

本轮两个模块的 47 项 Java 自动测试通过；模型实测另见[验证记录](../docs/formula-evaluation.md)与[本轮优化记录](../docs/performance-update.md)，不能用自动测试通过率代替识别准确率。公式原图现在默认不导出，连续任务复用模型。
