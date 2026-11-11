'use strict';

const byId = (id) => document.getElementById(id);
const input = byId('pdf-file');
const dropzone = byId('dropzone');
const button = byId('convert-button');
const status = byId('status');
let selectedFile = null;
let busy = false;
let markdown = '';
let markdownFilename = 'document.md';
let markdownUrl = null;
let originalMarkdown = '';
let downloadedMarkdown = '';

function refreshMarkdownDownload() {
  if (markdownUrl) URL.revokeObjectURL(markdownUrl);
  markdownUrl = URL.createObjectURL(new Blob([markdown], { type: 'text/markdown;charset=utf-8' }));
  byId('md-download').href = markdownUrl;
  byId('md-download').download = markdownFilename;
  byId('download-hint').textContent = markdown === originalMarkdown ? markdownFilename
    : `${markdownFilename}（.md 下载包含修改；ZIP 为原始结果）`;
}

function updateMode() {
  const mode = byId('formula-mode').value;
  byId('formula-images').disabled = busy || mode !== 'enhanced';
  if (mode !== 'enhanced') byId('formula-images').checked = false;
  byId('mode-hint').textContent = mode === 'blank'
    ? '仅给明显的独立公式编号留空；行内公式和无法判断的内容保留，请对照 PDF 补全。无需公式模型。'
    : mode === 'enhanced' ? '尝试生成 LaTeX，仍需核对。公式密集页可能等待数分钟。'
    : '先提取文字，保留原始数学符号；转换后可直接编辑并补写公式。';
}

function message(text, kind = '') {
  status.textContent = text;
  status.className = `status ${kind}`;
}

function resetResult() {
  markdown = '';
  originalMarkdown = '';
  downloadedMarkdown = '';
  if (markdownUrl) URL.revokeObjectURL(markdownUrl);
  markdownUrl = null;
  byId('empty-state').hidden = false;
  byId('result').hidden = true;
  byId('markdown-preview').value = '';
  byId('warnings').hidden = true;
  byId('warnings').textContent = '';
  byId('copy-button').disabled = true;
  byId('download-hint').textContent = '转换后即可下载文件';
  for (const id of ['md-download', 'zip-download']) {
    const link = byId(id);
    link.removeAttribute('href');
    link.classList.add('disabled');
    link.setAttribute('aria-disabled', 'true');
  }
}

function selectFile(file) {
  if (busy) return;
  if (markdown !== downloadedMarkdown && !window.confirm('当前编辑尚未下载，切换文件会清空编辑内容。继续吗？')) return;
  resetResult();
  selectedFile = null;
  button.disabled = true;
  byId('selected-file').hidden = true;
  if (!file) {
    message('选择一个 PDF，开始转换。');
    return;
  }
  if (!file.name.toLowerCase().endsWith('.pdf')) {
    message('请选择 PDF 文件。', 'error');
    return;
  }
  if (!file.size) {
    message('文件为空，请重新选择。', 'error');
    return;
  }
  selectedFile = file;
  byId('file-name').textContent = file.name;
  byId('file-size').textContent = file.size >= 1024 * 1024
    ? `${(file.size / (1024 * 1024)).toFixed(1)} MB` : `${Math.max(1, Math.ceil(file.size / 1024))} KB`;
  byId('selected-file').hidden = false;
  button.disabled = false;
  message('文件已就绪，点击按钮开始转换。');
}

input.addEventListener('change', () => selectFile(input.files[0]));
byId('formula-mode').addEventListener('change', updateMode);
dropzone.addEventListener('keydown', (event) => {
  if (!busy && (event.key === 'Enter' || event.key === ' ')) {
    event.preventDefault();
    input.click();
  }
});
for (const type of ['dragenter', 'dragover']) {
  dropzone.addEventListener(type, (event) => {
    event.preventDefault();
    if (!busy) dropzone.classList.add('dragover');
  });
}
dropzone.addEventListener('dragleave', () => dropzone.classList.remove('dragover'));
dropzone.addEventListener('drop', (event) => {
  event.preventDefault();
  dropzone.classList.remove('dragover');
  if (!busy) {
    input.value = '';
    if (event.dataTransfer.files.length > 1) {
      selectFile(null);
      message('请每次选择一个 PDF 文件。', 'error');
    } else {
      selectFile(event.dataTransfer.files[0]);
    }
  }
});
byId('remove-file').addEventListener('click', () => {
  input.value = '';
  selectFile(null);
});

byId('convert-form').addEventListener('submit', async (event) => {
  event.preventDefault();
  if (!selectedFile || busy) return;
  if (markdown !== downloadedMarkdown && !window.confirm('重新转换会覆盖尚未下载的修改。继续吗？')) return;
  busy = true;
  resetResult();
  button.disabled = true;
  input.disabled = true;
  byId('formula-mode').disabled = true;
  byId('include-images').disabled = true;
  byId('formula-images').disabled = true;
  byId('remove-file').disabled = true;
  button.classList.add('busy');
  dropzone.classList.add('busy');
  byId('button-label').textContent = '正在转换…';
  const started = Date.now();
  message('正在提取文档内容，扫描件识别可能需要更长时间。');
  const timer = window.setInterval(() => {
    message(`正在转换，已用时 ${Math.floor((Date.now() - started) / 1000)} 秒…`);
  }, 1000);
  try {
    const form = new FormData();
    form.append('file', selectedFile);
    const mode = byId('formula-mode').value;
    form.append('formulaEnhanced', String(mode === 'enhanced'));
    form.append('formulaPlaceholders', String(mode === 'blank'));
    form.append('includeImages', String(byId('include-images').checked));
    form.append('exportFormulaImages', String(mode === 'enhanced' && byId('formula-images').checked));
    const response = await fetch('/api/conversions', { method: 'POST', body: form });
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error('服务未返回有效结果，请检查服务状态后重试。');
    }
    if (!response.ok) throw new Error(data.message || '转换失败，请重试。');
    markdown = data.markdown;
    originalMarkdown = markdown;
    downloadedMarkdown = markdown;
    markdownFilename = data.filename;
    byId('markdown-preview').value = markdown;
    byId('result-meta').textContent = `${data.pageCount} 页 · ${data.scannedPdf ? '已对扫描页进行 OCR' : '文字提取'}${data.formulaEnhanced ? ` · ${data.formulaCount} 个公式区域` : data.formulaPlaceholders ? ` · ${data.formulaCount} 个公式占位` : ' · 已跳过公式模型'} · ${data.imageCount} 张图片 · ${(data.durationMillis / 1000).toFixed(1)} 秒`;
    byId('empty-state').hidden = true;
    byId('result').hidden = false;
    byId('copy-button').disabled = false;
    byId('download-hint').textContent = data.filename;
    for (const [id, url] of [['md-download', data.markdownUrl], ['zip-download', data.archiveUrl]]) {
      const link = byId(id);
      link.href = url;
      link.classList.remove('disabled');
      link.setAttribute('aria-disabled', 'false');
    }
    refreshMarkdownDownload();
    if (data.warnings?.length) {
      byId('warnings').textContent = data.warnings.join('\n');
      byId('warnings').hidden = false;
    }
    message('文字 Markdown 已生成，可直接编辑、补写公式并下载 .md。', 'success');
  } catch (error) {
    message(error instanceof TypeError ? '无法连接转换服务，请确认服务已启动后重试。' : error.message, 'error');
  } finally {
    window.clearInterval(timer);
    busy = false;
    button.disabled = !selectedFile;
    input.disabled = false;
    byId('formula-mode').disabled = false;
    byId('include-images').disabled = false;
    updateMode();
    byId('remove-file').disabled = false;
    button.classList.remove('busy');
    dropzone.classList.remove('busy');
    byId('button-label').textContent = '转换为 Markdown';
  }
});

byId('markdown-preview').addEventListener('input', () => {
  markdown = byId('markdown-preview').value;
  refreshMarkdownDownload();
});
byId('insert-formula').addEventListener('click', () => {
  const editor = byId('markdown-preview');
  editor.setRangeText('\n\n$$\n% 请在此填写 LaTeX 公式\n$$\n\n', editor.selectionStart, editor.selectionEnd, 'select');
  editor.dispatchEvent(new Event('input'));
  editor.focus();
});
byId('md-download').addEventListener('click', () => { downloadedMarkdown = markdown; });
window.addEventListener('beforeunload', (event) => {
  if (markdown !== downloadedMarkdown) {
    event.preventDefault();
    event.returnValue = '';
  }
});

byId('copy-button').addEventListener('click', async () => {
  if (!markdown) return;
  try {
    await navigator.clipboard.writeText(markdown);
    message('Markdown 内容已复制。', 'success');
  } catch {
    message('浏览器未允许复制，请选中预览内容复制，或下载 .md 文件。', 'error');
  }
});
