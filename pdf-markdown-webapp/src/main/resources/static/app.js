'use strict';

const byId = (id) => document.getElementById(id);
const input = byId('pdf-file');
const dropzone = byId('dropzone');
const button = byId('convert-button');
const status = byId('status');
let selectedFile = null;
let busy = false;
let markdown = '';

function message(text, kind = '') {
  status.textContent = text;
  status.className = `status ${kind}`;
}

function resetResult() {
  markdown = '';
  byId('empty-state').hidden = false;
  byId('result').hidden = true;
  byId('markdown-preview').textContent = '';
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
byId('formula-enhanced').addEventListener('change', () => {
  byId('formula-images').disabled = !byId('formula-enhanced').checked;
  if (!byId('formula-enhanced').checked) byId('formula-images').checked = false;
});
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
  busy = true;
  resetResult();
  button.disabled = true;
  input.disabled = true;
  byId('formula-enhanced').disabled = true;
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
    form.append('formulaEnhanced', String(byId('formula-enhanced').checked));
    form.append('exportFormulaImages', String(byId('formula-enhanced').checked && byId('formula-images').checked));
    const response = await fetch('/api/conversions', { method: 'POST', body: form });
    let data;
    try {
      data = await response.json();
    } catch {
      throw new Error('服务未返回有效结果，请检查服务状态后重试。');
    }
    if (!response.ok) throw new Error(data.message || '转换失败，请重试。');
    markdown = data.markdown;
    byId('markdown-preview').textContent = markdown;
    byId('result-meta').textContent = `${data.pageCount} 页 · ${data.scannedPdf ? '已对扫描页进行 OCR' : '文字提取'}${data.formulaEnhanced ? ` · ${data.formulaCount} 个公式区域` : ''} · ${data.imageCount} 张图片 · ${(data.durationMillis / 1000).toFixed(1)} 秒`;
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
    if (data.warnings?.length) {
      byId('warnings').textContent = data.warnings.join('\n');
      byId('warnings').hidden = false;
    }
    message('转换完成，Markdown 文件已生成。', 'success');
  } catch (error) {
    message(error instanceof TypeError ? '无法连接转换服务，请确认服务已启动后重试。' : error.message, 'error');
  } finally {
    window.clearInterval(timer);
    busy = false;
    button.disabled = !selectedFile;
    input.disabled = false;
    byId('formula-enhanced').disabled = false;
    byId('formula-images').disabled = !byId('formula-enhanced').checked;
    byId('remove-file').disabled = false;
    button.classList.remove('busy');
    dropzone.classList.remove('busy');
    byId('button-label').textContent = '转换为 Markdown';
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
