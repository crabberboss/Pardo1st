"""Unified desktop file transfer page; existing import/export controls remain available."""

CSS = r"""
#transferView{width:min(100%,1240px);margin:0 auto;padding:24px 26px 70px}
#transferView .transfer-intro{padding:22px 24px}
#transferView .transfer-intro h2{margin-bottom:8px}
#transferView .transfer-intro p{margin:0;line-height:1.55}
#transferView .transfer-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px;align-items:start;margin-top:16px}
#transferView .transfer-column{display:flex;flex-direction:column;min-width:0;gap:16px}
#transferView .transfer-card{min-width:0;padding:20px 22px;border-radius:15px}
#transferView .transfer-card h3{margin:0 0 8px}
#transferView .transfer-card p{line-height:1.6;color:var(--muted);margin:0 0 16px}
#transferView .transfer-card>label:not(.transfer-check){display:block;margin:0 0 6px}
#transferView .transfer-card select{display:block;width:100%;margin:0 0 16px}
#transferView .transfer-actions{display:flex;flex-wrap:wrap;align-items:center;gap:9px;margin:0 0 14px}
#transferView .transfer-actions button{white-space:nowrap;min-height:42px}
#transferView .transfer-card .transfer-note{font-size:13px;color:var(--muted);margin:0}
#transferView .transfer-card .transfer-warning{border-left:3px solid var(--accent-2);padding:10px 12px;background:var(--surface);margin:0}
#transferView .transfer-check{display:flex;align-items:center;gap:10px;min-height:32px;margin:0 0 8px;font-size:13px;color:var(--text);cursor:pointer}
#transferView .transfer-check input[type="checkbox"]{display:block;flex:0 0 18px;width:18px;height:18px;min-width:18px;margin:0;padding:0;cursor:pointer;accent-color:var(--accent)}
#transferView .transfer-check span{line-height:1.35}
#transferView .transfer-status{margin:14px 0 0;padding:10px 14px;border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:9px;background:var(--surface);color:var(--text);font-size:13px;line-height:1.5}
#transferView .transfer-status[hidden]{display:none}
@media(max-width:800px){#transferView{padding:16px}#transferView .transfer-grid{display:flex;flex-direction:column}#transferView .transfer-column{display:contents}#transferView .transfer-card[data-transfer-order="0"]{order:0}#transferView .transfer-card[data-transfer-order="1"]{order:1}#transferView .transfer-card[data-transfer-order="2"]{order:2}#transferView .transfer-card[data-transfer-order="3"]{order:3}#transferView .transfer-card[data-transfer-order="4"]{order:4}}
"""

JS = r"""
let transferArchives=[],transferProjects=[],transferTemplates=[],transferProfiles=[],transferRewriteProjects=[];
function transferEnsure(){
  if(document.getElementById('transferView'))return;
  document.getElementById('navSettings').insertAdjacentHTML('beforebegin',`<button id="navTransfer" onclick="showView('transfer')">导入与导出</button>`);
  document.querySelector('#homeView .entry-grid').insertAdjacentHTML('beforeend',`<button class="entry" onclick="showView('transfer')"><strong>导入与导出</strong><span>集中管理知识库、小说生成项目、改写稿和配置文件。</span></button>`);
  document.getElementById('settingsView').insertAdjacentHTML('beforebegin',`<section id="transferView" class="view">
    <div class="card transfer-intro"><h2>导入与导出</h2><p>按文件类型选择对应操作。每种文件独立保存，不会把 API 配置混进知识库或小说正文。</p></div>
    <div id="transferStatus" class="transfer-status" role="status" hidden></div>
    <div class="transfer-grid">
      <div class="card transfer-card"><h3>知识库与原文</h3><p>知识库 JSON 可跨端导入；TXT、Markdown 与 EPUB 原文可导入指定知识库。</p><label>目标知识库</label><select id="transferArchive" onchange="transferArchiveChanged()"></select><label>TXT / Markdown 编码</label><select id="transferTextEncoding"><option value="auto">自动识别 UTF-8 / GB18030 / UTF-16</option><option value="utf-8">UTF-8</option><option value="gb18030">GB18030（含 GBK）</option><option value="utf-16le">UTF-16 LE</option><option value="utf-16be">UTF-16 BE</option></select>
        <div class="transfer-actions"><button onclick="transferExportKb()">导出所选知识库</button><button class="secondary" onclick="transferImportKb()">导入知识库 JSON</button><button class="secondary" onclick="transferImportNovel()">导入小说原文</button></div>
        <p class="transfer-note">导入知识库会建立新存档；导入小说原文会写入选中的知识库。</p></div>
      <div class="card transfer-card"><h3>小说生成项目</h3><p>完整保存项目设定、章纲、正文、角色与事件记忆；与知识库分离。</p><label>生成项目</label><select id="transferProject"></select>
        <div class="transfer-actions"><button onclick="transferExportGeneration()">导出所选项目</button><button class="secondary" onclick="transferImportGeneration()">导入项目 JSON</button></div></div>
      <div class="card transfer-card"><h3>改写稿</h3><p>只包含所选改写工程的稿件；导入时校验与所选知识库原文是否匹配。</p><label>改写工程</label><select id="transferRewriteProject"></select>
        <div class="transfer-actions"><button onclick="transferExportRewrites()">导出所选知识库改写稿</button><button class="secondary" onclick="transferImportRewrites()">导入改写稿</button></div></div>
      <div class="card transfer-card"><h3>改写模板与角色卡</h3><p>模板与角色卡分别导出，导入到所选知识库，不改动原文。</p><label>改写模板</label><select id="transferTemplate"></select>
        <div class="transfer-actions"><button onclick="transferExportTemplate()">导出模板</button><button class="secondary" onclick="transferImportTemplate()">导入模板</button></div>
        <label>角色卡</label><select id="transferProfile"></select><div class="transfer-actions"><button onclick="transferExportCard()">导出角色卡</button><button class="secondary" onclick="transferImportCard()">导入角色卡 JSON</button></div></div>
      <div class="card transfer-card"><h3>应用设置与 API 渠道</h3><p>导出全局提示词、界面选项和各渠道地址／模型配置。默认不包含 API Key。</p>
        <label class="transfer-check"><input id="transferIncludeKeys" type="checkbox"><span>包含 API Key 与搜索密钥（敏感）</span></label>
        <label class="transfer-check"><input id="transferAllowKeys" type="checkbox"><span>导入时允许写入文件中的密钥</span></label>
        <div class="transfer-actions"><button onclick="transferExportSettings()">导出设置包</button><button class="secondary" onclick="transferImportSettings()">导入设置包</button></div>
        <p class="transfer-note">知识库专属对话预设不属于全局设置包，可单独迁移：</p><div class="transfer-actions"><button class="secondary" onclick="transferExportChatPreset()">导出所选知识库对话预设</button><button class="secondary" onclick="transferImportChatPreset()">导入对话预设</button></div>
        <p class="transfer-warning">含密钥文件只适合私下备份，切勿上传或分享；导入会覆盖同名渠道和全局设置。</p></div>
    </div></section>`);
  const grid=document.querySelector('#transferView .transfer-grid');
  const cards=Array.from(grid.children);
  const left=document.createElement('div'),right=document.createElement('div');
  left.className='transfer-column';right.className='transfer-column';
  cards.forEach((card,index)=>{card.dataset.transferOrder=String(index);(index%2===0?left:right).appendChild(card)});
  grid.append(left,right);
}
function transferNote(message){const status=document.getElementById('transferStatus');status.textContent=message;status.hidden=!message}
function transferId(){const value=Number(document.getElementById('transferArchive').value);if(!value)throw Error('请先选择知识库');return value}
function transferProjectId(){const value=document.getElementById('transferProject').value;if(!value)throw Error('请先选择小说生成项目');return value}
function transferDownload(data,name){const safe=String(name).replace(/[\\/:*?"<>|]/g,'_');const url=URL.createObjectURL(new Blob([JSON.stringify(data,null,2)],{type:'application/json;charset=utf-8'}));const link=document.createElement('a');link.href=url;link.download=safe;document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000)}
function transferPick(accept){return new Promise(resolve=>{const input=document.createElement('input');input.type='file';input.accept=accept;input.onchange=()=>resolve(input.files?.[0]||null);input.oncancel=()=>resolve(null);input.click()})}
async function decodeNovelText(file,encoding='auto'){
  const bytes=new Uint8Array(await file.arrayBuffer());
  let chosen=encoding;
  if(chosen==='auto'){
    if(bytes.length>=2&&bytes[0]===0xff&&bytes[1]===0xfe)chosen='utf-16le';
    else if(bytes.length>=2&&bytes[0]===0xfe&&bytes[1]===0xff)chosen='utf-16be';
    else {try{return new TextDecoder('utf-8',{fatal:true}).decode(bytes).replace(/^\uFEFF/,'')}catch{chosen='gb18030'}}
  }
  try{return new TextDecoder(chosen,{fatal:true}).decode(bytes).replace(/^\uFEFF/,'')}
  catch(error){throw Error('无法按 '+chosen+' 解码；请在导入与导出页手动选择正确编码：'+error.message)}
}
async function transferRun(action){try{await action()}catch(error){transferNote('操作失败：'+error.message);toast(error.message)}}
async function transferRefresh(){
  transferEnsure();const [stateData,projectData]=await Promise.all([api('/api/state'),api('/api/generation/projects')]);
  transferArchives=stateData.archives||[];transferProjects=projectData.projects||[];
  const archive=document.getElementById('transferArchive'),keepArchive=archive.value||String(currentArchiveId||'');
  archive.innerHTML='<option value="">选择知识库</option>'+transferArchives.map(a=>`<option value="${Number(a.id)}">${escapeHtml(a.name)}</option>`).join('');
  archive.value=keepArchive;const project=document.getElementById('transferProject'),keepProject=project.value||deskGenProject?.id||'';
  project.innerHTML='<option value="">选择生成项目</option>'+transferProjects.map(p=>`<option value="${p.id}">${escapeHtml(p.name)} · ${p.chapters} 章</option>`).join('');project.value=keepProject;
  await transferArchiveChanged();
}
async function transferArchiveChanged(){
  const id=Number(document.getElementById('transferArchive').value);
  transferTemplates=[];transferProfiles=[];transferRewriteProjects=[];
  if(id){const [options,rewriteData]=await Promise.all([api('/api/transfer/options/'+id),api('/api/rewrite/projects/'+id)]);
    if(Number(document.getElementById('transferArchive').value)!==id)return;
    transferTemplates=options.templates||[];transferProfiles=options.profiles||[];transferRewriteProjects=rewriteData.projects||[]}
  const rewriteSelect=document.getElementById('transferRewriteProject');
  rewriteSelect.innerHTML='<option value="">默认改写工程</option>'+transferRewriteProjects.map(p=>`<option value="${escapeHtml(p.id)}">${escapeHtml(p.name)}</option>`).join('');
  document.getElementById('transferTemplate').innerHTML='<option value="">选择改写模板</option>'+transferTemplates.map(t=>`<option value="${t.id}">${escapeHtml(t.name)}</option>`).join('');
  document.getElementById('transferProfile').innerHTML='<option value="">选择角色卡</option>'+transferProfiles.map(c=>`<option value="${c.id}">${escapeHtml(c.name)}</option>`).join('');
}
function transferExportKb(){transferRun(async()=>{const id=transferId(),data=await api('/api/export_archive',{method:'POST',body:JSON.stringify({archive_id:id})});transferDownload(data,(data.archive?.name||'知识库')+'.novel-memory.json');transferNote('知识库已导出')})}
function transferImportKb(){transferRun(async()=>{const file=await transferPick('.json,.novel-memory.json');if(!file)return;const data=await api('/api/import_archive_json',{method:'POST',body:JSON.stringify({package:JSON.parse(await file.text())})});await transferRefresh();document.getElementById('transferArchive').value=String(data.id);await transferArchiveChanged();transferNote('知识库已作为新存档导入')})}
function transferImportNovel(){transferRun(async()=>{const id=transferId(),file=await transferPick('.txt,.md,.epub');if(!file)return;let payload={archive_id:id,filename:file.name};if(file.name.toLowerCase().endsWith('.epub')){const bytes=new Uint8Array(await file.arrayBuffer()),parts=[];for(let i=0;i<bytes.length;i+=8192)parts.push(String.fromCharCode(...bytes.subarray(i,i+8192)));payload.content_base64=btoa(parts.join(''))}else payload.text=await decodeNovelText(file,document.getElementById('transferTextEncoding').value);await api('/api/import',{method:'POST',body:JSON.stringify(payload)});transferNote('原文已导入所选知识库；切分和记忆可在知识库页面继续')})}
function transferExportGeneration(){transferRun(async()=>{const id=transferProjectId(),data=await api('/api/generation/export',{method:'POST',body:JSON.stringify({id})});transferDownload(data.project,(data.project.name||'生成项目')+'.pardo-generation.json');transferNote('小说生成项目已导出')})}
function transferImportGeneration(){transferRun(async()=>{const file=await transferPick('.json,.pardo-generation.json');if(!file)return;const bytes=new Uint8Array(await file.arrayBuffer()),parts=[];for(let i=0;i<bytes.length;i+=8192)parts.push(String.fromCharCode(...bytes.subarray(i,i+8192)));const data=await api('/api/generation/import',{method:'POST',body:JSON.stringify({content_base64:btoa(parts.join(''))})});await transferRefresh();document.getElementById('transferProject').value=data.project.id;transferNote('小说生成项目已导入为独立存档')})}
function transferExportRewrites(){transferRun(async()=>{const id=transferId(),project_id=document.getElementById('transferRewriteProject').value,bundle=await api('/api/rewrite/bundle/export',{method:'POST',body:JSON.stringify({archive_id:id,project_id})});transferDownload(bundle,'novel-memory-'+id+(project_id?'-'+project_id:'')+'.novel-memory-rewrites.json');transferNote('所选改写工程稿件已导出')})}
function transferImportRewrites(){transferRun(async()=>{const id=transferId(),project_id=document.getElementById('transferRewriteProject').value,file=await transferPick('.json,.novel-memory-rewrites.json');if(!file)return;const bundle=JSON.parse(await file.text());if(bundle.format!=='novel-memory-rewrites')throw Error('不是改写稿文件');if(!confirm('导入会覆盖所选改写工程中同章的改写草稿。继续吗？'))return;const data=await api('/api/rewrite/bundle/import',{method:'POST',body:JSON.stringify({archive_id:id,project_id,bundle})});transferNote('已导入 '+data.imported+' 章改写稿')})}
function transferExportTemplate(){transferRun(async()=>{transferId();const id=Number(document.getElementById('transferTemplate').value),row=transferTemplates.find(t=>Number(t.id)===id);if(!row)throw Error('请先选择改写模板');transferDownload(row.config,(row.name||'改写模板')+'.json');transferNote('改写模板已导出')})}
function transferImportTemplate(){transferRun(async()=>{const id=transferId(),file=await transferPick('.json');if(!file)return;const config=JSON.parse(await file.text());if(config.format!=='novel-memory-rewrite-template')throw Error('不是跨端改写模板');await api('/api/rewrite_template',{method:'POST',body:JSON.stringify({archive_id:id,config,apply_tags:false})});await transferArchiveChanged();transferNote('改写模板已导入；原知识库标签未改动')})}
function transferExportCard(){transferRun(async()=>{transferId();const id=Number(document.getElementById('transferProfile').value),row=transferProfiles.find(c=>Number(c.id)===id);if(!row)throw Error('请先选择角色卡');const card={...row};delete card.id;delete card.archive_id;delete card.updated_at;delete card.message_count;transferDownload(card,(card.name||'角色卡')+'.json');transferNote('角色卡已导出')})}
function transferImportCard(){transferRun(async()=>{const id=transferId(),file=await transferPick('.json');if(!file)return;const card=JSON.parse(await file.text());delete card.id;if(card.data&&typeof card.data==='object')delete card.data.id;await api('/api/roleplay_profile',{method:'POST',body:JSON.stringify({archive_id:id,profile:card})});await transferArchiveChanged();transferNote('角色卡已作为新卡导入所选知识库')})}
function transferExportSettings(){transferRun(async()=>{const include_keys=document.getElementById('transferIncludeKeys').checked;if(include_keys&&!confirm('设置包将包含 API Key 与搜索密钥。只保存到可信位置，切勿分享。继续吗？'))return;const data=await api('/api/transfer/settings/export',{method:'POST',body:JSON.stringify({include_keys})});try{data.bundle.appearance={...(data.bundle.appearance||{}),reader:JSON.parse(localStorage.getItem('reader-prefs')||'{}')}}catch{}transferDownload(data.bundle,'帕朵一号-设置'+(include_keys?'-含密钥':'')+'.pardo-settings.json');transferNote('应用设置与阅读偏好已导出'+(include_keys?'（含敏感密钥）':'（不含密钥）'))})}
function transferImportSettings(){transferRun(async()=>{const file=await transferPick('.json,.pardo-settings.json');if(!file)return;const bundle=JSON.parse(await file.text()),allow_keys=document.getElementById('transferAllowKeys').checked;if(bundle.format!=='pardo-settings-bundle')throw Error('不是帕朵一号设置包');if(!confirm('导入会覆盖同名 API 渠道和全局设置。继续吗？'))return;if(bundle.contains_secrets&&!allow_keys)throw Error('此文件含密钥；如确需导入，请勾选“导入时允许写入密钥”后重试');await api('/api/transfer/settings/import',{method:'POST',body:JSON.stringify({bundle,allow_keys})});if(bundle.appearance?.reader&&typeof bundle.appearance.reader==='object'){localStorage.setItem('reader-prefs',JSON.stringify(bundle.appearance.reader));transferNote('应用设置和阅读偏好已导入；重新打开设置页查看')}else transferNote('应用设置已导入；重新打开设置页查看')})}
function transferExportChatPreset(){transferRun(async()=>{const id=transferId(),data=await api('/api/chat_preset/'+id);transferDownload({format:'pardo-chat-preset',version:1,preset:data.preset},'帕朵一号-对话预设-'+id+'.json');transferNote('所选知识库对话预设已单独导出')})}
function transferImportChatPreset(){transferRun(async()=>{const id=transferId(),file=await transferPick('.json');if(!file)return;const bundle=JSON.parse(await file.text());if(bundle.format!=='pardo-chat-preset'||bundle.version!==1||!bundle.preset||typeof bundle.preset!=='object')throw Error('不是帕朵一号对话预设文件');if(!confirm('将覆盖所选知识库的对话预设，其他知识库不受影响。继续吗？'))return;await api('/api/chat_preset',{method:'POST',body:JSON.stringify({archive_id:id,preset:bundle.preset})});transferNote('对话预设已导入所选知识库')})}
const _showViewBeforeTransfer=showView;
showView=function(name){if(name!=='transfer')return _showViewBeforeTransfer(name);transferEnsure();_showViewBeforeTransfer('home');for(const view of document.querySelectorAll('.view,.home'))view.classList.remove('active');for(const button of document.querySelectorAll('.nav button'))button.classList.remove('active');document.getElementById('transferView').classList.add('active');document.getElementById('navTransfer').classList.add('active');transferRefresh().catch(error=>transferNote('载入列表失败：'+error.message))};
transferEnsure();
"""
