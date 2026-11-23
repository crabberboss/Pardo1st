// Shared special-tag schema matches the desktop novel-memory-archive v4 fields.
let mobileSpecialTags = [];
let mobileSpecialHits = [];
let mobileSpecialSuggestions = [];
let selectedFilterTags = new Set();
let selectedTemplateTags = new Set();
let chapterPromptTimer = null;
let pendingChapterPrompt = null;

function safeTagColor(value) {
  return /^#[0-9a-fA-F]{6}$/.test(String(value || '')) ? value : '#a45e4f';
}
function specialChip(tag, selected, handler) {
  return `<button type="button" class="special-choice ${selected ? 'selected' : ''}" style="--tag-color:${safeTagColor(tag.color)}" onclick="${handler}(${Number(tag.id)})" aria-pressed="${selected}">${esc(tag.name)}</button>`;
}
function flushChapterPrompt() {
  if (chapterPromptTimer) clearTimeout(chapterPromptTimer);
  chapterPromptTimer = null;
  if (pendingChapterPrompt) {
    req('saveChapterPrompt', pendingChapterPrompt);
    pendingChapterPrompt = null;
  }
}
function queueChapterPrompt() {
  if (!archiveId || !chapterId) return;
  pendingChapterPrompt = {archive_id: archiveId, chapter_id: chapterId,
    prompt: $('chapterPrompt').value, extra_instruction: $('extraInstruction').value};
  if (chapterPromptTimer) clearTimeout(chapterPromptTimer);
  chapterPromptTimer = setTimeout(flushChapterPrompt, 450);
}

function renderSpecialTags() {
  const list = $('mobileSpecialTagList');
  if (list) list.innerHTML = mobileSpecialTags.map(t => `<div class="special-tag-row">
    <span class="scene-pill" style="border-color:${safeTagColor(t.color)};color:${safeTagColor(t.color)}">${esc(t.name)}</span>
    <span class="muted" style="display:-webkit-box;-webkit-box-orient:vertical;-webkit-line-clamp:2;line-clamp:2;overflow:hidden;overflow-wrap:anywhere;white-space:normal">${esc(t.recognition_prompt || '未填写识别提示词')}</span>
    <button class="secondary" onclick="editMobileSpecialTag(${Number(t.id)})">编辑</button>
    <button class="danger" onclick="deleteMobileSpecialTag(${Number(t.id)})">删除</button>
  </div>`).join('') || '<p class="muted">本知识库还没有特殊关键词。新建后可挂靠到章节，也可跨端导出。</p>';
  const filter = $('specialFilterChoices');
  if (filter) filter.innerHTML = mobileSpecialTags.map(t => specialChip(t, selectedFilterTags.has(t.name), 'toggleSpecialFilter')).join('') || '<p class="muted">请先在知识库页添加特殊关键词。</p>';
  const template = $('specialTemplateChoices');
  if (template) template.innerHTML = mobileSpecialTags.map(t => specialChip(t, selectedTemplateTags.has(t.name), 'toggleTemplateTag')).join('') || '<p class="muted">尚无可选特殊关键词。</p>';
  const hitSelect = $('specialHitTag');
  if (hitSelect) hitSelect.innerHTML = mobileSpecialTags.map(t => `<option value="${Number(t.id)}">${esc(t.name)}</option>`).join('');
}
function tagById(id) { return mobileSpecialTags.find(t => Number(t.id) === Number(id)); }
function toggleSpecialFilter(id) {
  const tag = tagById(id); if (!tag) return;
  selectedFilterTags.has(tag.name) ? selectedFilterTags.delete(tag.name) : selectedFilterTags.add(tag.name);
  if (selectedFilterTags.size) $('rewriteScope').value = 'special';
  renderSpecialTags();
}
function toggleTemplateTag(id) {
  const tag = tagById(id); if (!tag) return;
  selectedTemplateTags.has(tag.name) ? selectedTemplateTags.delete(tag.name) : selectedTemplateTags.add(tag.name);
  $('templateTags').value = [...selectedTemplateTags].join('，');
  renderSpecialTags();
}
function editMobileSpecialTag(id) {
  const tag = tagById(id); if (!tag) return;
  $('specialTagId').value = String(id);
  $('specialTagName').value = tag.name;
  $('specialTagColor').value = safeTagColor(tag.color);
  $('specialTagPrompt').value = tag.recognition_prompt || '';
}
function clearMobileSpecialTag() {
  $('specialTagId').value = '';
  $('specialTagName').value = '';
  $('specialTagColor').value = '#a45e4f';
  $('specialTagPrompt').value = '';
}
function suggestMobileSpecialTags(){if(!needArchive())return;const name=$('specialTagName').value.trim();if(!name)return notice('请先填写标签名称');$('mobileSpecialSuggestions').textContent='正在扩写识别提示词…';req('expandSpecialTagPrompt',{archive_id:archiveId,name,original:$('specialTagPrompt').value.trim()});}
function saveMobileExpandSettings(){req('specialExpandSettingsSave',{prompt:$('mobileSpecialExpandSystem').value.trim()});notice('自动扩写提示词已保存');}
function saveMobileRewriteExpandSettings(){req('rewriteExpandSettingsSave',{prompt:$('mobileRewriteExpandSystem').value.trim()});notice('章节改写扩写提示词已保存');}
function generateMobileRewritePrompt(){if(!needArchive()||!chapterId||!activeChapter)return notice('请先选择章节');req('expandRewritePrompt',{archive_id:archiveId,chapter_id:chapterId,title:activeChapter.title,original:$('chapterPrompt').value.trim(),extra:$('extraInstruction').value.trim()});notice('正在扩写本章改写提示词…');}
function chooseMobileSpecialSuggestion(index){const tag=mobileSpecialSuggestions[index];if(!tag)return;clearMobileSpecialTag();$('specialTagName').value=tag.name;$('specialTagPrompt').value=tag.recognition_prompt;$('specialTagColor').value=safeTagColor(tag.color);notice('候选已填入；检查后点击保存标签');}
function saveMobileSpecialTag() {
  if (!needArchive()) return;
  req('saveSpecialTag', {archive_id: archiveId, id: Number($('specialTagId').value || 0),
    name: $('specialTagName').value.trim(), color: $('specialTagColor').value,
    recognition_prompt: $('specialTagPrompt').value.trim()});
  clearMobileSpecialTag();
}
function deleteMobileSpecialTag(id) {
  if (!confirm('删除此特殊关键词及所有章节挂靠？此操作不会删除章节原文。')) return;
  req('deleteSpecialTag', {archive_id: archiveId, id});
}
function renderChapterSpecials(chapter) {
  const hits = Array.isArray(chapter?.special_hits) ? chapter.special_hits : [];
  let grid = $('chapterDetail')?.querySelector('.memory-grid');
  if (!grid && chapter) {
    $('chapterDetail').insertAdjacentHTML('afterbegin', '<div class="memory-grid"></div>');
    grid = $('chapterDetail').querySelector('.memory-grid');
  }
  if (grid) {
    grid.querySelector('.special-tags')?.remove();
    grid.insertAdjacentHTML('afterbegin', `<div class="memory-field special-tags"><strong>特殊关键词</strong>${hits.length ? hits.map(h => `<div class="special-hit"><span class="scene-pill" style="border-color:${safeTagColor(h.color)};color:${safeTagColor(h.color)}">${esc(h.name)}</span><span>${esc(h.description || '')}</span></div>`).join('') : '<div class="muted">本章尚未挂靠特殊关键词。</div>'}</div>`);
  }
  const editor = $('chapterSpecialEditor');
  if (!editor) return;
  editor.innerHTML = `<details class="topspace"><summary>本章特殊关键词 · ${hits.length} 个</summary>
    <p class="muted">从本库词库点选并写一句不超过 30 字的情节说明；随知识库导出，桌面端可继续编辑。</p>
    <label>选择标签</label><select id="specialHitTag">${mobileSpecialTags.map(t => `<option value="${Number(t.id)}">${esc(t.name)}</option>`).join('')}</select>
    <label>本章情节说明（最多 30 字）</label><input id="specialHitDescription" maxlength="30" placeholder="例如：两人在密室讨论逃离路线">
    <label>原文依据（可选）</label><input id="specialHitEvidence" placeholder="原文片段或线索">
    <button class="topspace" onclick="saveMobileSpecialHit()">挂靠 / 更新标签</button>
    <div class="topspace">${hits.map(h => `<div class="special-hit"><span class="scene-pill" style="border-color:${safeTagColor(h.color)};color:${safeTagColor(h.color)}">${esc(h.name)}</span><span>${esc(h.description || '')}</span><button class="secondary" onclick="editMobileSpecialHit(${Number(h.tag_id)})">修改</button><button class="danger" onclick="removeMobileSpecialHit(${Number(h.tag_id)})">移除</button></div>`).join('')}</div>
  </details>`;
}
function editMobileSpecialHit(tagId) {
  const hit = (activeChapter?.special_hits || []).find(h => Number(h.tag_id) === Number(tagId));
  if (!hit) return;
  $('specialHitTag').value = String(tagId);
  $('specialHitDescription').value = hit.description || '';
  $('specialHitEvidence').value = hit.evidence || '';
}
function saveMobileSpecialHit() {
  if (!archiveId || !chapterId) return notice('请先选择章节');
  req('setSpecialHit', {archive_id: archiveId, chapter_id: chapterId,
    tag_id: Number($('specialHitTag').value), description: $('specialHitDescription').value,
    evidence: $('specialHitEvidence').value});
}
function removeMobileSpecialHit(tagId) {
  if (!archiveId || !chapterId) return;
  req('setSpecialHit', {archive_id: archiveId, chapter_id: chapterId, tag_id: tagId, remove: true});
}

// Put the archive-level prompt directly above the chapter-level prompt.
const globalPromptBox = $('globalPrompt');
const globalPromptLabel = globalPromptBox.previousElementSibling;
const globalPromptHint = globalPromptBox.nextElementSibling;
const chapterPromptLabel = $('chapterPrompt').previousElementSibling;
globalPromptLabel.textContent = '知识库统一提示词';
globalPromptHint.textContent = '随当前知识库自动保存；切换章节不变，切换知识库才更新。';
chapterPromptLabel.textContent = '本章提示词';
$('chapterPrompt').before(globalPromptLabel, globalPromptBox, globalPromptHint);
$('chapterPrompt').placeholder = '仅作用于当前章节；自动保存，生成成功后与最新改写正文一起保留';
$('chapterPrompt').addEventListener('input', queueChapterPrompt);
$('extraInstruction').addEventListener('input', queueChapterPrompt);

const scope = $('rewriteScope');
scope.insertAdjacentHTML('beforeend', '<option value="special">按特殊关键词库筛选</option>');
scope.insertAdjacentHTML('afterend', `<label>特殊关键词库 · 点击添加筛选</label><div id="specialFilterChoices" class="special-choices"></div><label>组合方式</label><select id="specialFilterMode"><option value="all">同时包含全部</option><option value="any">包含任意一个</option></select>`);
$('rewriteKeyword').placeholder = '普通关键词补充搜索（可选）';
$('templateTags').type = 'hidden';
$('templateTags').insertAdjacentHTML('beforebegin', '<div id="specialTemplateChoices" class="special-choices"></div>');
$('chapterList').closest('.card').insertAdjacentHTML('beforebegin', `<div class="card" id="mobileSpecialTagCard"><h2>特殊关键词库</h2><p class="muted">与桌面端共用标签名称、颜色、识别提示词与章节情节说明。可在章节浏览中挂靠标签。</p><input id="specialTagId" type="hidden"><label>标签名称</label><input id="specialTagName" placeholder="例如：密室双人"><label>标签颜色</label><input id="specialTagColor" type="color" value="#a45e4f"><label>模型识别提示词</label><textarea id="specialTagPrompt" placeholder="描述哪些情节符合本标签"></textarea><div class="row wrap topspace"><button onclick="saveMobileSpecialTag()">保存标签</button><button class="secondary" onclick="clearMobileSpecialTag()">新建</button><button class="secondary" onclick="suggestMobileSpecialTags()">自动生成</button></div><div id="mobileSpecialSuggestions" class="topspace"></div><div id="mobileSpecialTagList" class="topspace"></div></div>`);
const mobilePromptSettingsCard=$('chatGlobalPrompt').closest('.card');
if(mobilePromptSettingsCard.querySelector){const heading=mobilePromptSettingsCard.querySelector('h2');if(heading)heading.textContent='全局提示词与用户设置';}
mobilePromptSettingsCard.insertAdjacentHTML('beforeend','<hr><h3>提示词自动扩写</h3><label>特殊场景标签识别提示词的扩写规则</label><textarea id="mobileSpecialExpandSystem"></textarea><button onclick="saveMobileExpandSettings()">保存标签扩写规则</button><label>章节改写提示词的扩写规则</label><textarea id="mobileRewriteExpandSystem"></textarea><button onclick="saveMobileRewriteExpandSettings()">保存改写扩写规则</button>');
$('chapterPrompt').insertAdjacentHTML('afterend','<button class="secondary topspace" onclick="generateMobileRewritePrompt()">自动生成章节改写提示词</button>');
req('specialExpandSettingsGet',{});
req('rewriteExpandSettingsGet',{});

const originalSelectArchive = selectArchive;
selectArchive = function(id) {
  flushChapterPrompt(); selectedFilterTags.clear(); selectedTemplateTags.clear();
  mobileSpecialTags = []; mobileSpecialHits = []; renderSpecialTags();
  originalSelectArchive(id);
  req('specialTagState', {archive_id: id});
};
const originalSelectChapter = selectChapter;
selectChapter = function(id) { flushChapterPrompt(); originalSelectChapter(id); };
const originalShowRewrite = showRewrite;
showRewrite = function(chapter) {
  originalShowRewrite(chapter);
  $('chapterPrompt').value = chapter?.chapter_prompt || '';
  $('extraInstruction').value = chapter?.extra_instruction || '';
};
const originalRenderSource = renderSource;
renderSource = function(chapter, text, offset, total) {
  originalRenderSource({...chapter, tags: ''}, text, offset, total);
  $('chapterDetail').insertAdjacentHTML('beforeend', '<div id="chapterSpecialEditor"></div>');
  renderChapterSpecials(chapter);
};
const originalTemplateFilter = templateFilter;
templateFilter = function() {
  if ($('rewriteScope').value === 'special') return {mode: $('specialFilterMode').value, tags: [...selectedFilterTags]};
  return originalTemplateFilter();
};
const originalReadTemplateForm = readTemplateForm;
readTemplateForm = function() {
  const data = originalReadTemplateForm();
  data.tags = mobileSpecialTags.filter(t => selectedTemplateTags.has(t.name)).map(t =>
    ({name: t.name, color: safeTagColor(t.color), recognition_prompt: t.recognition_prompt || ''}));
  data.filter.tags = [...selectedTemplateTags];
  return data;
};
const originalFillRewriteTemplate = fillRewriteTemplate;
fillRewriteTemplate = function(template, applyPrompt = false) {
  originalFillRewriteTemplate(template, applyPrompt);
  selectedTemplateTags = new Set(template?.filter?.tags || []);
  renderSpecialTags();
};
const originalClearTemplateForm = clearTemplateForm;
clearTemplateForm = function() { originalClearTemplateForm(); selectedTemplateTags.clear(); renderSpecialTags(); };
const originalEstimateRewrite = estimateRewrite;
estimateRewrite = function() {
  if ($('rewriteScope').value === 'special' && !selectedFilterTags.size) return notice('请从特殊关键词库点选至少一个标签');
  originalEstimateRewrite();
};
const originalStartBulkRewrite = startBulkRewrite;
startBulkRewrite = function() {
  if ($('rewriteScope').value === 'special' && !selectedFilterTags.size) return notice('请从特殊关键词库点选至少一个标签');
  originalStartBulkRewrite();
};
const originalStartSingleRewrite = startSingleRewrite;
startSingleRewrite = function() { flushChapterPrompt(); originalStartSingleRewrite(); };
const originalSaveRewrite = saveRewrite;
saveRewrite = function() { flushChapterPrompt(); originalSaveRewrite(); };
const originalNativeEvent = window.nativeEvent;
window.nativeEvent = function(type, data) {
  if(type==='rewriteExpandSettings'){$('mobileRewriteExpandSystem').value=data.prompt||'';return;}
  if(type==='rewritePromptExpanded'){if(data.archive_id!==archiveId||data.chapter_id!==chapterId||data.original!==$('chapterPrompt').value.trim()||data.extra!==$('extraInstruction').value.trim())return notice('输入已变化，请重新生成');$('chapterPrompt').value=(data.original?data.original+'\n──────────\n':'')+data.expanded;queueChapterPrompt();notice('已追加改写提示词，正在保存到本章');return;}
  if(type==='specialExpandSettings'){$('mobileSpecialExpandSystem').value=data.prompt||'';return;}
  if(type==='specialTagExpanded'){if(data.archive_id!==archiveId||data.name!==$('specialTagName').value.trim()||data.original!==$('specialTagPrompt').value.trim())return notice('输入已变化，请重新生成');$('specialTagPrompt').value=(data.original?data.original+'\n──────────\n':'')+data.expanded;$('mobileSpecialSuggestions').textContent='扩写已追加；检查后点击保存标签。';return;}
  if(type==='specialTagSuggestions'){if(data.archive_id!==archiveId)return;mobileSpecialSuggestions=data.tags||[];$('mobileSpecialSuggestions').innerHTML='<p class="muted">候选仅供确认，不会自动保存或扫描章节。</p>'+mobileSpecialSuggestions.map((t,i)=>`<button class="secondary" onclick="chooseMobileSpecialSuggestion(${i})">${esc(t.name)} · ${esc(t.recognition_prompt)}</button>`).join('');return;}
  if (type === 'specialTagState') {
    if (data.archive_id !== archiveId) return;
    mobileSpecialTags = data.tags || []; mobileSpecialHits = data.hits || [];
    selectedFilterTags = new Set([...selectedFilterTags].filter(name => mobileSpecialTags.some(t => t.name === name)));
    selectedTemplateTags = new Set([...selectedTemplateTags].filter(name => mobileSpecialTags.some(t => t.name === name)));
    renderSpecialTags();
    if (activeChapter) {
      activeChapter.special_hits = mobileSpecialHits
        .filter(h => Number(h.document_id) === Number(activeChapter.doc_id) && h.chapter_title === activeChapter.title)
        .map(h => { const tag = tagById(h.tag_id); return tag ? {...h, name: tag.name, color: tag.color} : null; })
        .filter(Boolean);
      renderChapterSpecials(activeChapter);
    }
    return;
  }
  if (type === 'specialHitSaved') {
    if (data.archive_id !== archiveId || data.id !== chapterId) return;
    activeChapter.special_hits = data.special_hits || [];
    renderChapterSpecials(activeChapter);
    loadChapters(chapterOffset);
    return;
  }
  if (type === 'chapterPromptSaved') return;
  originalNativeEvent(type, data);
};
