(()=>{
let lastSelectedGenerationId='';
const style=document.createElement('style');style.textContent=`#view-transfer .transfer-grid{display:grid;gap:13px}#view-transfer .transfer-card{padding:18px;border:1px solid #e0d8d2;border-radius:16px;background:var(--app-panel,#fff)}#view-transfer .transfer-card h3{margin:0 0 7px;color:#403b38}#view-transfer .transfer-card p{margin:5px 0 12px;color:#746d69;font-size:13px;line-height:1.5}#view-transfer .transfer-actions{display:flex;flex-wrap:wrap;gap:8px}#view-transfer .transfer-actions button{min-height:42px}#view-transfer select{width:100%;margin:6px 0 11px}#view-transfer .transfer-status{white-space:pre-wrap;line-height:1.5}`;document.head.appendChild(style);
const home=$('view-home').querySelector('.home-grid');home.querySelector('.home-entry:last-child')?.insertAdjacentHTML('beforebegin','<button class="home-entry" onclick="openTransfer()"><strong>导入与导出</strong><span>统一管理知识库、生成项目与配置文件</span></button>');
document.querySelector('main').insertAdjacentHTML('beforeend',`<section id="view-transfer" class="view"><div class="card"><h2>导入与导出</h2><p class="muted">选择文件类型和目标。旧页面的导入导出入口仍可使用；每次只处理你选中的文件。</p><p id="transferStatus" class="transfer-status muted">请选择要处理的内容。</p></div><div class="transfer-grid">
<div class="transfer-card"><h3>知识库与小说原文</h3><p>知识库文件可在双端互通；TXT、MD、EPUB 导入后建立新知识库。</p><select id="transferArchive" onchange="transferRefresh()"></select><div class="transfer-actions"><button onclick="req('pickFile',{archive_id:0})">导入小说／知识库</button><button class="secondary" onclick="transferExportKb()">导出所选知识库</button></div></div>
<div class="transfer-card"><h3>小说生成项目</h3><p>导出完整生成项目 JSON，包括设定、章纲、正文和记忆。与知识库分开保存。</p><select id="transferProject"></select><div class="transfer-actions"><button onclick="req('generationPickImport',{})">导入生成项目</button><button class="secondary" onclick="transferExportProject()">导出所选项目</button></div></div>
<div class="transfer-card"><h3>改写稿</h3><p>导入时会写入所选知识库；导出不包含原文或 API 设置。</p><div class="transfer-actions"><button onclick="transferNeedArchive()&&req('pickFile',{archive_id:Number($('transferArchive').value)})">导入改写稿</button><button class="secondary" onclick="transferExportRewrites()">导出改写稿</button></div></div>
<div class="transfer-card"><h3>改写模板与角色卡</h3><p>模板与角色卡属于所选知识库，可分别导入或导出。</p><select id="transferProfile"></select><div class="transfer-actions"><button onclick="transferNeedArchive()&&req('pickTemplate',{archive_id:Number($('transferArchive').value)})">导入模板</button><button class="secondary" onclick="transferExportTemplate()">导出模板</button><button onclick="transferNeedArchive()&&req('pickCard',{archive_id:Number($('transferArchive').value)})">导入角色卡</button><button class="secondary" onclick="transferExportCard()">导出所选角色卡</button></div></div>
<div class="transfer-card"><h3>应用设置</h3><p>模型渠道与通用提示词配置；不包含知识库、生成项目和对话。默认不导出 API／搜索密钥。</p><label><input id="transferIncludeKeys" type="checkbox"> 导出时包含密钥（明文，请勿分享）</label><label><input id="transferAllowKeys" type="checkbox"> 导入时允许写入文件中的密钥</label><div class="transfer-actions"><button onclick="transferExportSettings()">导出设置</button><button class="secondary" onclick="transferImportSettings()">导入设置</button></div><p>知识库专属对话预设可单独迁移：</p><div class="transfer-actions"><button class="secondary" onclick="transferExportChatPreset()">导出对话预设</button><button class="secondary" onclick="transferImportChatPreset()">导入对话预设</button></div></div></div></section>`);
window.openTransfer=()=>{tab('transfer');transferRefresh()};
window.transferNeedArchive=()=>{if(Number($('transferArchive').value)>0)return true;notice('请先选择知识库');return false};
window.transferRefresh=()=>{req('transferOptions',{archive_id:Number($('transferArchive').value)||0})};
window.transferExportKb=()=>{if(transferNeedArchive())req('exportKb',{archive_id:Number($('transferArchive').value),name:$('transferArchive').selectedOptions[0]?.textContent||'知识库'})};
window.transferExportRewrites=()=>{if(transferNeedArchive())req('exportRewrites',{archive_id:Number($('transferArchive').value),name:$('transferArchive').selectedOptions[0]?.textContent||'改写稿'})};
window.transferExportProject=()=>{const id=$('transferProject').value;if(!id)return notice('请先选择生成项目');req('generationPickExport',{id,name:$('transferProject').selectedOptions[0]?.textContent||'小说生成项目'})};
window.transferExportTemplate=()=>{if(!transferNeedArchive())return;const name=$('transferArchive').selectedOptions[0]?.textContent||'改写模板';if(!$('transferArchive').dataset.hasTemplate)return notice('所选知识库尚未保存改写模板');req('exportTemplate',{archive_id:Number($('transferArchive').value),name})};
window.transferExportCard=()=>{if(!transferNeedArchive())return;const id=Number($('transferProfile').value);if(!id)return notice('请先选择角色卡');req('exportCard',{archive_id:Number($('transferArchive').value),profile_id:id,name:$('transferProfile').selectedOptions[0]?.textContent||'角色卡'})};
window.transferExportSettings=()=>{const include=$('transferIncludeKeys').checked;if(include&&!confirm('包含的 API／搜索密钥会以明文写入文件。请勿分享该文件。确定继续？'))return;let appearance={};try{appearance={mobile:JSON.parse(localStorage.getItem('mobile-appearance')||'{}'),reader:JSON.parse(localStorage.getItem('mobile-reader-style')||'{}')};const background=localStorage.getItem('mobile-background')||'';if(background.length&&background.length<1000000)appearance.background=background;else if(background.length)notice('自定义背景超过 1 MB，本次设置包不含背景图片')}catch{}req('transferSettingsExport',{include_keys:include,appearance})};
window.transferImportSettings=()=>req('transferSettingsImport',{allow_keys:$('transferAllowKeys').checked});
window.transferExportChatPreset=()=>{if(transferNeedArchive())req('transferChatPresetExport',{archive_id:Number($('transferArchive').value)})};
window.transferImportChatPreset=()=>{if(transferNeedArchive())req('transferChatPresetImport',{archive_id:Number($('transferArchive').value)})};
const previous=window.nativeEvent;window.nativeEvent=(type,data)=>{
 if(type==='transferOptions'){
  const archives=data.archives||[],projects=data.projects||[],archive=$('transferArchive'),project=$('transferProject');
  const chosen=archive.value,chosenProject=project.value;
  archive.innerHTML='<option value="">选择知识库</option>'+archives.map(a=>`<option value="${a.id}">${esc(a.name)}</option>`).join('');
  archive.value=chosen||String(archiveId||'');if(!archive.value&&archives.length)archive.value=String(archives[0].id);
  project.innerHTML='<option value="">选择生成项目</option>'+projects.map(p=>`<option value="${esc(p.id)}">${esc(p.name)} · ${p.chapters||0} 章</option>`).join('');project.value=chosenProject||lastSelectedGenerationId||'';
  const profiles=data.profiles||[];$('transferProfile').innerHTML='<option value="">选择角色卡</option>'+profiles.map(p=>`<option value="${p.id}">${esc(p.name)}</option>`).join('');
  if(data.template)archive.dataset.hasTemplate='1';else delete archive.dataset.hasTemplate;
  if(!chosen&&archive.value)req('transferOptions',{archive_id:Number(archive.value)});
  return;
 }
 if(type==='transferSettingsImported'){
  const appearance=data.appearance||{};
  if(typeof appearance.background==='string'&&appearance.background.startsWith('data:image/')&&appearance.background.length<1000000)localStorage.setItem('mobile-background',appearance.background);
  if(appearance.mobile){localStorage.setItem('mobile-appearance',JSON.stringify(appearance.mobile));if(appearance.mobile.accent)$('mobileAccent').value=appearance.mobile.accent;if(appearance.mobile.opacity)$('mobileOpacity').value=appearance.mobile.opacity;if(appearance.mobile.blur!==undefined)$('mobileBlur').value=appearance.mobile.blur;}
  if(appearance.reader){localStorage.setItem('mobile-reader-style',JSON.stringify(appearance.reader));const ids={size:'readSize',bg:'readBg',fg:'readFg',font:'readFont',mode:'readMode',margin:'readPageMargin',spacing:'readLetterSpacing',align:'readAlign',brightness:'readBrightness'};for(const [key,id] of Object.entries(ids))if(appearance.reader[key]!==undefined&&$(id))$(id).value=appearance.reader[key];if(appearance.reader.leading!==undefined)$('readLineSpacing').value=Math.round(appearance.reader.leading*100);if(appearance.reader.autoBrightness!==undefined)$('readAutoBrightness').checked=appearance.reader.autoBrightness;if(typeof readerStyle==='function')readerStyle();}
  if(typeof mobileTheme==='function')mobileTheme();
  $('transferStatus').textContent=`已导入设置：${data.providers||0} 个模型渠道。知识库和生成项目未修改。`;
 }
 if(type==='exported')$('transferStatus').textContent=`已导出 ${data.filename||'文件'}\n${data.location||''}`;
 if(type==='generationSelected')lastSelectedGenerationId=data.id||'';
 if(type==='exportCancelled')$('transferStatus').textContent='已取消导出，未创建文件。';
 if(type==='error')$('transferStatus').textContent='操作失败：'+data;
 previous(type,data);
 if(type==='imported'||type==='generationList'||type==='generationSelected')if($('view-transfer').classList.contains('active'))transferRefresh();
};
})();
