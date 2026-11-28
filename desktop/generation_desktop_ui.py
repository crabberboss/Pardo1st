"""Small desktop chapter-revision workspace; visual redesign can replace this shell."""

CSS = r"""
#generationView{max-width:1500px;margin:0 auto;padding:24px 26px 60px;line-height:1.6}
#generationView>.card{margin-bottom:20px;padding:26px 30px;border-radius:16px}
#generationView h2,#generationView h3{margin:0 0 10px;line-height:1.35}
#generationView .generation-lead{max-width:900px;margin:0 0 24px;color:var(--muted);font-size:14px}
#generationView .generation-create{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr) auto;gap:14px;align-items:end;padding:20px;border:1px solid var(--line);border-radius:14px;background:var(--surface)}
#generationView .generation-create label{display:block;min-width:0;font-size:13px;font-weight:700;color:var(--text)}
#generationView .generation-create input{display:block;width:100%;height:46px;margin-top:7px;padding:10px 14px;min-width:0}
#generationView .generation-create button{height:46px;min-width:142px;padding:10px 20px;white-space:nowrap;flex:none}
#generationView .generation-actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:18px}
#generationView .generation-actions button{min-width:130px;white-space:nowrap}
#generationView .generation-status{min-height:24px;margin:14px 0 0;color:var(--muted);font-size:13px}
#generationView .generation-layout{display:grid;grid-template-columns:minmax(260px,320px) minmax(0,1fr);gap:20px;align-items:start}
#generationView .generation-layout>.card{min-height:330px;padding:24px;border-radius:16px}
#generationView .generation-sidebar-section+.generation-sidebar-section{margin-top:24px;padding-top:20px;border-top:1px solid var(--line)}
#generationView .generation-list{max-height:46vh;overflow:auto;display:flex;flex-direction:column;gap:10px;padding:2px}
#generationView .generation-list button{text-align:left;width:100%;white-space:normal;line-height:1.5;padding:12px 14px;border:1px solid var(--line);background:var(--surface);color:var(--text);border-radius:10px}
#generationView .generation-list button:hover,#generationView .generation-list button:focus-visible{border-color:var(--accent);outline:2px solid transparent;box-shadow:0 0 0 2px color-mix(in srgb,var(--accent) 20%,transparent)}
#generationView .generation-empty{padding:22px 16px;border:1px dashed var(--line);border-radius:12px;background:var(--surface);color:var(--muted);line-height:1.65;font-size:14px}
#generationView .generation-editor{min-width:0}
#generationView .generation-editor label{display:block;margin-top:18px;margin-bottom:7px;font-weight:600}
#generationView .generation-editor textarea{width:100%;min-height:130px;box-sizing:border-box;line-height:1.65}
#generationView #deskGenContent{min-height:48vh;line-height:1.75}
#generationView .generation-editor input{width:100%;box-sizing:border-box}
#generationView .generation-editor .row{gap:10px;flex-wrap:wrap;margin-top:18px}
#generationView .generation-editor .row button{white-space:nowrap}
#generationView .generation-warning{color:var(--accent-2);font-weight:600}
#generationView #deskGenProjectControls label{display:block;margin-top:12px}
#generationView #deskGenProjectControls textarea{display:block;width:100%;min-height:100px;box-sizing:border-box}
#generationView #deskGenProjectControls input{min-width:160px;box-sizing:border-box}
#generationView #deskGenProjectControls details{margin:16px 0;padding:16px;border-radius:12px}
#generationView #deskGenProjectControls details>summary{padding:4px 0}
#generationView #deskGenProjectControls>.row{gap:12px;flex-wrap:wrap;margin-top:18px}
@media(max-width:960px){#generationView .generation-layout{grid-template-columns:minmax(220px,280px) minmax(0,1fr)}#generationView .generation-create{grid-template-columns:minmax(0,1fr) minmax(0,1fr)}#generationView .generation-create button{grid-column:1/-1;justify-self:start}}
@media(max-width:680px){#generationView{padding:16px 14px 48px}#generationView>.card{padding:20px 18px}#generationView .generation-create{grid-template-columns:1fr;padding:16px}#generationView .generation-create button{grid-column:auto;width:100%}#generationView .generation-layout{grid-template-columns:1fr}#generationView .generation-layout>.card{min-height:0}#generationView .generation-list{max-height:230px}}
#generationView{max-width:none;padding:20px clamp(16px,2vw,32px) 48px;line-height:1.55}
#generationView .generation-hero{display:flex;justify-content:space-between;align-items:flex-end;gap:24px;padding:18px 24px 22px;margin-bottom:16px;border:1px solid var(--line);border-radius:16px;background:var(--surface)}
#generationView .generation-hero h2{font-size:clamp(25px,2.3vw,34px);margin:2px 0 5px}
#generationView .generation-hero p{margin:0;color:var(--muted);font-size:13px}
#generationView .generation-eyebrow{font-size:11px;font-weight:800;letter-spacing:.16em;color:var(--accent)}
#generationView .generation-hero-actions{display:flex;gap:10px;flex-wrap:wrap;justify-content:flex-end}
#generationView .generation-layout{grid-template-columns:minmax(238px,280px) minmax(0,1fr);gap:16px;align-items:stretch}
#generationView .generation-rail{min-height:calc(100vh - 190px);padding:18px;border-radius:16px}
#generationView .generation-rail-head{display:flex;flex-direction:column;gap:4px}
#generationView .generation-rail-head strong{font-size:17px}
#generationView .generation-rail-head small{color:var(--muted)}
#generationView .generation-sidebar-section+.generation-sidebar-section{margin-top:22px;padding-top:18px}
#generationView .generation-sidebar-section h3{font-size:12px;text-transform:none;color:var(--muted);margin-bottom:9px}
#generationView .generation-list{max-height:calc((100vh - 320px)/2);min-height:100px;gap:6px}
#generationView .generation-list button{padding:10px 12px;border-radius:10px;font-weight:500}
#generationView .generation-list button.is-active{border-color:var(--accent);background:var(--accent-soft);font-weight:700}
#generationView .generation-workspace{min-width:0}
#generationView .generation-stages{display:flex;gap:5px;overflow-x:auto;max-width:100%;padding:5px;margin:0 0 12px;border:1px solid var(--line);border-radius:13px;background:var(--surface)}
#generationView .generation-stages button{flex:1;min-width:112px;padding:10px 9px;border:0;border-radius:9px;background:transparent;color:var(--muted);font-size:13px;white-space:nowrap}
#generationView .generation-stages button.active{background:var(--accent);color:white;font-weight:700}
#generationView .generation-stages button:focus-visible{outline:2px solid var(--accent);outline-offset:2px}
#generationView .generation-status{min-height:42px;padding:9px 14px;margin:0 0 12px;border:1px solid var(--line);border-radius:10px;background:var(--surface);color:var(--text)}
#generationView .generation-stage{display:none}
#generationView .generation-stage.active{display:block}
#generationView .generation-pane{padding:25px clamp(20px,2.5vw,38px);margin-bottom:14px;border-radius:16px;min-height:0}
#generationView .generation-section-heading{display:flex;align-items:start;justify-content:space-between;gap:18px;padding-bottom:16px;margin-bottom:16px;border-bottom:1px solid var(--line)}
#generationView .generation-section-heading h3{font-size:21px;margin:3px 0 4px}
#generationView .generation-section-heading p{margin:0;color:var(--muted);font-size:13px}
#generationView .generation-create{grid-template-columns:minmax(0,1fr) minmax(0,1fr) auto;margin:0;padding:0;border:0;background:transparent}
#generationView .generation-fields label,#generationView .generation-stage label{display:block;margin:12px 0 0;font-size:13px;font-weight:700;color:var(--text)}
#generationView .generation-stage label input,#generationView .generation-stage label textarea{display:block;width:100%;box-sizing:border-box;margin-top:7px}
#generationView .generation-field-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}
#generationView .generation-number-grid{max-width:560px}
#generationView .generation-fields textarea{min-height:118px}
#generationView #deskGenBookOutline,#generationView #deskGenPrompt,#generationView #deskGenPlanOutline{min-height:155px}
#generationView .generation-fields>button,#generationView #deskGenPlanPanel>button{margin-top:18px}
#generationView .generation-run-actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:18px}
#generationView .generation-progress{padding:14px 16px;border:1px solid var(--line);border-radius:10px;background:var(--surface);white-space:pre-wrap}
#generationView .generation-badge{flex:none;padding:5px 10px;border-radius:999px;background:var(--accent-soft);color:var(--accent);font-size:12px;font-weight:700}
#generationView .generation-memory-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px}
#generationView .generation-memory-card{min-height:150px;padding:16px;border:1px solid var(--line);border-radius:12px;background:var(--surface)}
#generationView .generation-memory-card h4{margin:0 0 10px;font-size:15px}
#generationView .generation-memory-card ul{margin:0;padding-left:19px}
#generationView .generation-memory-card li{margin:8px 0;overflow-wrap:anywhere}
#generationView .generation-memory-card small{display:block;color:var(--muted)}
#generationView .generation-timeline{display:flex;gap:14px;overflow-x:auto;padding:12px 4px 18px;scroll-snap-type:x proximity}
#generationView .generation-timeline-item{flex:0 0 220px;padding:13px;border:1px solid var(--line);border-top:3px solid var(--accent);border-radius:10px;background:var(--surface);scroll-snap-align:start}
#generationView .generation-timeline-item strong{display:block;margin-bottom:6px}
#generationView .generation-timeline-item small{color:var(--muted)}
#generationView .generation-timeline-item p{font-size:12px;margin:7px 0 0}
#generationView .generation-prompt-results textarea{min-height:150px;width:100%;box-sizing:border-box}
#generationView .generation-prompt-results[hidden]{display:none}
#generationView .generation-prompt-profile{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 12px;border:1px solid var(--line);border-radius:10px;background:var(--surface)}
#generationView .generation-prompt-profile+.generation-prompt-profile{margin-top:8px}
#generationView .generation-prompt-profile .row{display:flex;gap:8px;flex-wrap:wrap}
#generationView .generation-prompt-profile button{white-space:nowrap}
#generationView #deskGenContent{min-height:54vh}
/* Flat writing workspace: one quiet rail, clear section spacing, no stacked card frames. */
#generationView .generation-hero{padding:8px 8px 20px;margin-bottom:8px;border:0;border-radius:0;background:transparent}
#generationView .generation-hero-actions button{border-radius:9px}
#generationView .generation-layout{gap:20px}
#generationView .generation-rail.card{min-height:calc(100vh - 185px);padding:18px 14px;border:0;box-shadow:none;border-radius:13px;background:var(--surface)}
#generationView .generation-rail-head{padding:0 9px 12px}
#generationView .generation-sidebar-section+.generation-sidebar-section{border-top:1px solid color-mix(in srgb,var(--line) 65%,transparent)}
#generationView .generation-list{gap:3px;padding:0}
#generationView .generation-list button{border:0;box-shadow:none;border-radius:8px;background:transparent;padding:9px 11px}
#generationView .generation-list button:hover,#generationView .generation-list button:focus-visible{outline:0;box-shadow:none;background:var(--accent-soft)}
#generationView .generation-list button.is-active{border:0;border-left:3px solid var(--accent);border-radius:3px 8px 8px 3px;background:var(--accent-soft);padding-left:8px}
#generationView .generation-stages{gap:4px;padding:0 2px 0;margin:0 0 12px;border:0;border-bottom:1px solid var(--line);border-radius:0;background:transparent}
#generationView .generation-stages button{border-radius:0;padding:11px 8px 10px;border-bottom:2px solid transparent}
#generationView .generation-stages button.active{border-bottom-color:var(--accent);background:transparent;color:var(--accent)}
#generationView .generation-status{min-height:0;margin:0 0 5px;padding:8px 10px;border:0;border-radius:7px;background:var(--accent-soft);font-size:12px}
#generationView .generation-pane.card{min-height:0;margin:0;padding:24px 8px 28px;border:0;border-radius:0;box-shadow:none;background:transparent}
#generationView .generation-pane.card+.generation-pane.card{border-top:1px solid var(--line)}
#generationView .generation-section-heading{padding-bottom:12px;margin-bottom:13px;border-bottom:0}
#generationView .generation-section-heading h3{font-size:20px}
#generationView .generation-empty{padding:15px 0;border:0;border-radius:0;background:transparent}
#generationView .generation-progress{padding:12px 14px;border:0;border-left:3px solid var(--accent);border-radius:4px;background:var(--accent-soft)}
#generationView .generation-memory-grid{gap:10px}
#generationView .generation-memory-card{min-height:0;padding:14px;border:0;border-radius:9px;background:var(--surface)}
#generationView .generation-timeline-item{border:0;border-top:2px solid var(--accent);border-radius:8px;background:var(--surface)}
#generationView .generation-prompt-profile{padding:9px 0;border:0;border-bottom:1px solid var(--line);border-radius:0;background:transparent}
#generationView .generation-prompt-profile+.generation-prompt-profile{margin-top:0}
@media(max-width:920px){#generationView .generation-layout{grid-template-columns:minmax(190px,230px) minmax(0,1fr)}#generationView .generation-create{grid-template-columns:1fr 1fr}#generationView .generation-create button{grid-column:1/-1;justify-self:start}}
@media(max-width:680px){#generationView .generation-layout{grid-template-columns:1fr}#generationView .generation-rail{min-height:0}#generationView .generation-list{max-height:190px}#generationView .generation-hero{align-items:start;flex-direction:column}#generationView .generation-create,#generationView .generation-field-grid{grid-template-columns:1fr}#generationView .generation-create button{width:100%}}
"""

JS = r"""
// Android-compatible generation projects. Controlled-planning interface is reserved, not active.
let deskGenProject=null,deskGenChapter=null,deskGenNumber=0,deskGenBusy=false,deskGenPoll=null;
let deskGenWritingProfiles=[],deskGenSourceNames=[];
function deskGenSampleText(raw){
  const value=String(raw||'').replace(/[ \t]+/g,' ').trim();
  if(value.length<300)throw new Error('参考书正文过短，至少需要 300 字');
  if(value.length<=2400)return value;
  const span=780;return [.08,.48,.88].map(r=>value.slice(Math.max(0,Math.min(value.length-span,Math.floor(value.length*r))),Math.max(0,Math.min(value.length-span,Math.floor(value.length*r)))+span)).join('\n\n【抽样片段】\n');
}
function deskGenPromptNote(message){document.getElementById('deskGenPromptStatus').textContent=message}
async function deskGenSynthesize(){
  const files=[...document.getElementById('deskGenPromptBooks').files];
  if(files.length<1||files.length>5)return deskGenPromptNote('请选择 1–5 本 TXT、Markdown 或 EPUB 参考书');
  const button=document.getElementById('deskGenSynthesizeBtn');button.disabled=true;
  try{
    const books=[];let uploaded=0;
    for(const file of files){
      const lower=file.name.toLowerCase();if(!/\.(txt|md|epub)$/.test(lower))throw new Error('不支持的格式：'+file.name);
      if(lower.endsWith('.epub')){
        if(file.size>25000000)throw new Error('单本 EPUB 不能超过 25 MB');
        const bytes=new Uint8Array(await file.arrayBuffer()),parts=[];
        for(let i=0;i<bytes.length;i+=32768)parts.push(String.fromCharCode(...bytes.subarray(i,i+32768)));
        const content_base64=btoa(parts.join(''));uploaded+=content_base64.length;books.push({name:file.name,content_base64});
      }else{const sample=deskGenSampleText(await decodeNovelText(file));uploaded+=sample.length;books.push({name:file.name,sample})}
      if(uploaded>30000000)throw new Error('本次参考书上传量过大，请减少 EPUB 文件');
    }
    deskGenPromptNote(`正在分析 ${books.length} 本书；正文仅取分散短样本，预计一次模型调用…`);
    const data=await api('/api/generation/synthesize_writing_prompt',{method:'POST',body:JSON.stringify({books})});
    deskGenSourceNames=data.result.source_names||files.map(f=>f.name);
    document.getElementById('deskGenSynthesisStyle').value=data.result.style_prompt||'';
    document.getElementById('deskGenSynthesisUnified').value=data.result.unified_prompt||'';
    document.getElementById('deskGenProfileName').value=files.length===1?files[0].name.replace(/\.(txt|md|epub)$/i,'')+'·文风':'多书融合·写作提示词';
    document.getElementById('deskGenPromptResults').hidden=false;
    deskGenPromptNote(`提炼完成：${books.length} 本，约 ${data.result.sample_chars} 字样本。请检查并保存配置。`);
  }catch(error){deskGenPromptNote('提炼失败：'+error.message)}finally{button.disabled=false}
}
async function deskGenLoadWritingProfiles(){
  const result=await api('/api/generation/writing_profiles');deskGenWritingProfiles=result.profiles||[];
  document.getElementById('deskGenProfiles').innerHTML=deskGenWritingProfiles.map(p=>`<div class="generation-prompt-profile"><div><strong>${escapeHtml(p.name)}</strong><small>${escapeHtml((p.source_names||[]).join('、')||'手动配置')}</small></div><div class="row"><button onclick="deskGenApplyWritingProfile('${p.id}')">选取并应用</button><button class="secondary" onclick="deskGenExportWritingProfile('${p.id}')">导出</button></div></div>`).join('')||'<div class="generation-empty">暂无配置。提炼后保存，或导入双端通用配置。</div>';
}
async function deskGenSaveWritingProfile(){
  const profile={format:'pardo-writing-prompt',version:1,name:document.getElementById('deskGenProfileName').value.trim(),style_prompt:document.getElementById('deskGenSynthesisStyle').value.trim(),unified_prompt:document.getElementById('deskGenSynthesisUnified').value.trim(),source_names:deskGenSourceNames};
  try{await api('/api/generation/writing_profile_save',{method:'POST',body:JSON.stringify({profile})});await deskGenLoadWritingProfiles();deskGenPromptNote('配置已保存；可点击“选取并应用”，或导出供手机端使用。')}
  catch(error){deskGenPromptNote('配置保存失败：'+error.message)}
}
function deskGenImportWritingProfile(){
  const input=document.createElement('input');input.type='file';input.accept='.json,application/json';
  input.onchange=async()=>{const file=input.files?.[0];if(!file)return;
    try{if(file.size>40000)throw new Error('提示词配置文件超过 40 KB');const profile=JSON.parse(await file.text());
      await api('/api/generation/writing_profile_save',{method:'POST',body:JSON.stringify({profile})});await deskGenLoadWritingProfiles();deskGenPromptNote('配置已导入，可点击应用。')}
    catch(error){deskGenPromptNote('导入失败：'+error.message)}};input.click();
}
function deskGenExportWritingProfile(id){
  const profile=deskGenWritingProfiles.find(p=>p.id===id);if(!profile)return;
  const portable={format:profile.format,version:profile.version,name:profile.name,style_prompt:profile.style_prompt,unified_prompt:profile.unified_prompt,source_names:profile.source_names||[]};
  const url=URL.createObjectURL(new Blob([JSON.stringify(portable,null,2)],{type:'application/json;charset=utf-8'}));
  const link=document.createElement('a');link.href=url;link.download=profile.name.replace(/[\\/:*?"<>|]/g,'_')+'.pardo-writing-prompt.json';document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
  deskGenPromptNote('配置已导出；仅包含书名和两份提示词，不含书籍正文。');
}
async function deskGenApplyWritingProfile(id){
  if(!deskGenProject)return deskGenPromptNote('请先选择要应用的小说生成项目');
  if(deskGenProject.status==='running')return deskGenPromptNote('请先暂停当前项目再应用配置');
  if(!confirm('将用此配置覆盖当前项目的文风与统一提示词。原项目其他设定、章节和知识库不会改变。继续吗？'))return;
  try{const data=await api('/api/generation/writing_profile_apply',{method:'POST',body:JSON.stringify({profile_id:id,project_id:deskGenProject.id})});
    deskGenProject=data.project;deskGenFillSettings();deskGenRenderState();deskGenPromptNote('已应用到“'+deskGenProject.name+'”，下次生成章节生效。')}
  catch(error){deskGenPromptNote('应用失败：'+error.message)}
}
function ensureDesktopGeneration(){
  if(document.getElementById('generationView'))return;
  document.getElementById('navSettings').insertAdjacentHTML('beforebegin',
    '<button id="navGeneration" onclick="showView(\'generation\')">文章生成</button>');
  document.querySelector('#homeView .entry-grid').insertAdjacentHTML('beforeend',
    '<button class="entry" onclick="showView(\'generation\')"><strong>文章生成</strong><span>新建小说项目，按章规划与生成，或导入安卓端项目继续修订。</span></button>');
  document.getElementById('settingsView').insertAdjacentHTML('beforebegin',`<section id="generationView" class="view">
    <header class="generation-hero"><div><span class="generation-eyebrow">PARDO · WRITING STUDIO</span><h2>文章生成</h2><p>独立项目，逐章创作。设定、章纲、正文与结构化记忆各自保存，不改动知识库。</p></div>
      <div class="generation-hero-actions"><button class="secondary" onclick="deskGenImport()">导入项目</button><button class="secondary" onclick="deskGenExport()" id="deskGenExportBtn" disabled>导出当前项目</button></div></header>
    <div class="generation-layout"><aside class="generation-rail card"><div class="generation-rail-head"><strong>作品与章节</strong><small id="deskGenRailCount">尚未选择项目</small></div>
      <div class="generation-sidebar-section"><h3>我的作品</h3><div id="deskGenProjects" class="generation-list"></div></div>
      <div class="generation-sidebar-section"><h3>已完成章节</h3><div id="deskGenChapters" class="generation-list"><div class="generation-empty">选择项目后显示章节。</div></div></div></aside>
      <div class="generation-workspace"><nav class="generation-stages" aria-label="文章生成步骤">
        <button type="button" data-stage="setup" class="active" onclick="deskGenStage('setup')">① 项目设定</button>
        <button type="button" data-stage="plan" onclick="deskGenStage('plan')">② 章节细纲</button>
        <button type="button" data-stage="run" onclick="deskGenStage('run')">③ 生成进度</button>
        <button type="button" data-stage="memory" onclick="deskGenStage('memory')">④ 角色与事件</button>
        <button type="button" data-stage="draft" onclick="deskGenStage('draft')">⑤ 成稿正文</button></nav>
      <p id="deskGenStatus" class="generation-status" role="status">新建作品，或从左侧打开已有项目。</p>
      <section id="deskGenStageSetup" class="generation-stage active"><div class="card generation-pane"><div class="generation-section-heading"><div><span class="generation-eyebrow">STEP 01</span><h3>建立作品</h3><p>只需小说名与主角名。其他设定可逐步补充。</p></div></div>
        <div class="generation-create"><label for="deskGenNewName">小说名称<input id="deskGenNewName" placeholder="例如：我的长篇小说" autocomplete="off"></label>
          <label for="deskGenNewProtagonist">主角名字<input id="deskGenNewProtagonist" placeholder="例如：林舟" autocomplete="off"></label><button onclick="deskGenCreate()">新建项目</button></div></div>
        <div class="card generation-pane" id="deskGenProjectControls" hidden><div class="generation-section-heading"><div><span class="generation-eyebrow">BOOK SETTINGS</span><h3 id="deskGenProjectName">当前项目</h3><p>全书方向只在规划章纲时作为参考，正文生成不反复发送整份总纲。</p></div></div>
          <div id="deskGenSettings" class="generation-fields"><label>全书方向参考（弱提示）<textarea id="deskGenBookOutline"></textarea></label>
            <div class="generation-field-grid"><label>世界观<textarea id="deskGenWorld"></textarea></label><label>系统与规则<textarea id="deskGenSystem"></textarea></label></div>
            <div class="generation-field-grid"><label>主角设定<textarea id="deskGenProtagonist"></textarea></label><label>文风<textarea id="deskGenStyle"></textarea></label></div>
            <label>每章生成总要求<textarea id="deskGenPrompt"></textarea></label>
            <div class="generation-field-grid generation-number-grid"><label>目标字数<input id="deskGenChars" type="number" min="600" max="5000"></label><label>输出 Token 上限<input id="deskGenTokens" type="number" min="1024" max="16384"></label></div>
            <button onclick="deskGenSaveSettings()">保存设定，进入章纲 →</button></div></div>
        <div class="card generation-pane"><div class="generation-section-heading"><div><span class="generation-eyebrow">REFERENCE BOOKS</span><h3>从参考书提炼写作提示词</h3><p>支持 1–5 本 TXT、Markdown、EPUB。每本分散抽样，合计输入片段受限；原文不保存到配置。</p></div></div>
          <input id="deskGenPromptBooks" type="file" accept=".txt,.md,.epub" multiple><div class="generation-run-actions"><button id="deskGenSynthesizeBtn" onclick="deskGenSynthesize()">提炼文风与统一提示词</button><button class="secondary" onclick="deskGenImportWritingProfile()">导入提示词配置</button></div>
          <p id="deskGenPromptStatus" class="meta" role="status">先选择参考书；调用当前设置的模型，可能产生 API 费用。</p>
          <div id="deskGenPromptResults" class="generation-prompt-results" hidden><label>文风提示词<textarea id="deskGenSynthesisStyle"></textarea></label><label>小说统一提示词<textarea id="deskGenSynthesisUnified"></textarea></label>
            <div class="generation-field-grid"><label>配置名称<input id="deskGenProfileName" placeholder="例如：群像冒险文风"></label></div><button onclick="deskGenSaveWritingProfile()">保存为配置文件</button></div>
          <h4>已保存配置</h4><div id="deskGenProfiles" class="generation-list"><div class="generation-empty">暂无配置。提炼后保存，或导入双端通用配置。</div></div></div></section>
      <section id="deskGenStagePlan" class="generation-stage"><div class="card generation-pane"><div class="generation-section-heading"><div><span class="generation-eyebrow">STEP 02</span><h3>规划下一章</h3><p>可以手写细纲与出场角色，也可留空让模型自动规划。剧情规划接口已预留，暂不生效。</p></div></div>
        <div id="deskGenPlanPanel" hidden><div class="generation-field-grid"><label>章节标题<input id="deskGenPlanTitle"></label><label>视角角色<input id="deskGenPlanViewpoint"></label></div>
          <label>本章章纲<textarea id="deskGenPlanOutline"></textarea></label><label>出场角色（用逗号分隔）<input id="deskGenPlanCast"></label>
          <button class="secondary" onclick="deskGenSavePlan()">保存本章章纲</button></div><div id="deskGenPlanEmpty" class="generation-empty">请先新建或选择项目。</div></div></section>
      <section id="deskGenStageRun" class="generation-stage"><div class="card generation-pane"><div class="generation-section-heading"><div><span class="generation-eyebrow">STEP 03</span><h3>生成与续写</h3><p>每次只生成一章；可暂停并保存，失败后保留已有内容。</p></div><span id="deskGenRunBadge" class="generation-badge">未启动</span></div>
        <div id="deskGenRunControls" hidden><p id="deskGenProgress" class="generation-progress"></p><div class="generation-run-actions"><button id="deskGenStartBtn" onclick="deskGenStart()">生成下一章</button>
          <button class="secondary" id="deskGenPauseBtn" onclick="deskGenPause()" disabled>暂停并保存</button></div></div><div id="deskGenRunEmpty" class="generation-empty">请先新建或选择项目。</div></div></section>
      <section id="deskGenStageMemory" class="generation-stage"><div class="card generation-pane"><div class="generation-section-heading"><div><span class="generation-eyebrow">STEP 04</span><h3>角色与事件记忆</h3><p>从已生成章节提取，按当前章节状态显示；这里仅供复核，不会改写知识库。</p></div></div>
        <h4>章节事件轴</h4><div id="deskGenTimeline" class="generation-timeline"><div class="generation-empty">生成章节后显示事件节点。</div></div>
        <div id="deskGenMemory" class="generation-memory-grid"><div class="generation-empty">选择项目后显示角色、事件和世界状态。</div></div></div></section>
      <section id="deskGenStageDraft" class="generation-stage"><div class="card generation-pane generation-editor"><div class="generation-section-heading"><div><span class="generation-eyebrow">STEP 05</span><h3 id="deskGenHeading">选择已完成章节</h3><p>修改后保存；重新生成不会直接丢弃旧稿。</p></div></div>
        <p id="deskGenWarning" class="generation-warning"></p><div id="deskGenEmpty" class="generation-empty">从左侧选择章节，查看和修改细纲、梗概与正文。</div>
        <div id="deskGenEditor" hidden><label for="deskGenTitle">章节标题</label><input id="deskGenTitle"><label for="deskGenOutline">本章章纲</label><textarea id="deskGenOutline"></textarea>
          <label for="deskGenSummary">章节梗概</label><textarea id="deskGenSummary"></textarea><label for="deskGenContent">章节正文</label><textarea id="deskGenContent"></textarea>
          <div class="generation-run-actions"><button id="deskGenSaveBtn" onclick="deskGenSave()">保存修改</button><button class="secondary" id="deskGenRegenerateBtn" onclick="deskGenRegenerate()">按章纲重新生成</button>
            <button class="secondary" id="deskGenRestoreBtn" onclick="deskGenRestore()">恢复上一版</button></div>
          <p class="meta">重新生成会调用当前模型并产生费用；失败时旧稿不会被覆盖。</p></div></div></section>
      </div></div></section>`);
}
function deskGenNote(message){document.getElementById('deskGenStatus').textContent=message}
function deskGenStage(stage){
  for(const button of document.querySelectorAll('#generationView .generation-stages button'))button.classList.toggle('active',button.dataset.stage===stage);
  for(const panel of document.querySelectorAll('#generationView .generation-stage'))panel.classList.toggle('active',panel.id==='deskGenStage'+stage[0].toUpperCase()+stage.slice(1));
}
function deskGenRenderMemory(){
  const target=document.getElementById('deskGenMemory'),p=deskGenProject;
  if(!p){target.innerHTML='<div class="generation-empty">选择项目后显示角色、事件和世界状态。</div>';return}
  document.getElementById('deskGenTimeline').innerHTML=(p.timeline||[]).map(c=>'<div class="generation-timeline-item"><strong>第 '+c.number+' 章 · '+escapeHtml(c.title)+'</strong><small>'+escapeHtml((c.cast||[]).join('、')||'暂无出场角色记录')+'</small><p>'+escapeHtml(c.summary||'暂无章节梗概')+'</p></div>').join('')||'<div class="generation-empty">生成章节后显示事件节点。</div>';
  const characters=(p.characters||[]).slice(0,80),threads=(p.threads||[]).slice(0,80),world=(p.world_state||[]).slice(0,80);
  const list=(items,make)=>items.length?'<ul>'+items.map(make).join('')+'</ul>':'<p class="meta">尚无记录。生成章节后自动更新。</p>';
  target.innerHTML='<article class="generation-memory-card"><h4>角色池 · '+(p.characters||[]).length+'</h4>'+list(characters,c=>'<li><strong>'+escapeHtml(c.name)+'</strong> · '+(c.status==='dead'?'已死亡归档':'热度 '+c.heat)+'<small>'+escapeHtml(c.state||'暂无状态')+'</small></li>')+'</article>'+
    '<article class="generation-memory-card"><h4>事件线 · '+threads.length+'</h4>'+list(threads,t=>'<li><strong>'+escapeHtml(t.name)+'</strong> · '+escapeHtml(t.status)+(t.visibility==='hidden'?' · 暗线':'')+'<small>第 '+t.source_chapter+' 章 · '+escapeHtml(t.state||'暂无进展')+'</small></li>')+'</article>'+
    '<article class="generation-memory-card"><h4>世界状态 · '+world.length+'</h4>'+list(world,w=>'<li><strong>'+escapeHtml(w.name)+'</strong><small>'+escapeHtml(w.state||'暂无记录')+'</small></li>')+'</article>';
}
function deskGenDirty(){return !!deskGenChapter&&['title','outline','summary','content'].some(key=>
  document.getElementById('deskGen'+key[0].toUpperCase()+key.slice(1)).value!==String(deskGenChapter[key]||''))}
function deskGenCanLeave(){return !deskGenDirty()||confirm('本章有尚未保存的修改，放弃这些修改吗？')}
function deskGenSetBusy(busy){deskGenBusy=busy;for(const id of ['deskGenSaveBtn','deskGenRegenerateBtn','deskGenRestoreBtn'])document.getElementById(id).disabled=busy}
function deskGenFillSettings(){
  if(!deskGenProject)return;
  const map={BookOutline:'outline',World:'world',System:'system',Protagonist:'protagonist',Style:'style',Prompt:'generation_prompt',Chars:'chapter_chars',Tokens:'output_tokens'};
  for(const [id,key] of Object.entries(map))document.getElementById('deskGen'+id).value=deskGenProject[key]||'';
}
function deskGenFillPlan(){
  const plan=deskGenProject?.pending||{};
  document.getElementById('deskGenPlanTitle').value=plan.title||'';
  document.getElementById('deskGenPlanOutline').value=plan.outline||'';
  document.getElementById('deskGenPlanCast').value=(plan.cast||[]).join('，');
  document.getElementById('deskGenPlanViewpoint').value=plan.viewpoint||'';
}
function deskGenRenderState(){
  const p=deskGenProject;if(!p)return;
  document.getElementById('deskGenProjectControls').hidden=false;
  document.getElementById('deskGenPlanPanel').hidden=false;
  document.getElementById('deskGenPlanEmpty').hidden=true;
  document.getElementById('deskGenRunControls').hidden=false;
  document.getElementById('deskGenRunEmpty').hidden=true;
  document.getElementById('deskGenProjectName').textContent=p.name;
  document.getElementById('deskGenRailCount').textContent=`${p.name} · ${p.chapters.length} 章`;
  document.getElementById('deskGenRunBadge').textContent=({ready:'待开始',running:'生成中',paused:'已暂停',interrupted:'已中断',completed:'已完结',failed:'生成失败'})[p.status]||p.status||'待开始';
  deskGenRenderMemory();
  document.getElementById('deskGenProgress').textContent=`${p.status||'ready'} · ${p.detail||''}${p.error?' · 错误：'+p.error:''}${p.memory_stale_from?' · 第 '+p.memory_stale_from+' 章起结构化记忆待复核':''}`;
  document.getElementById('deskGenStartBtn').disabled=p.status==='running'||p.status==='completed';
  document.getElementById('deskGenPauseBtn').disabled=p.status!=='running';
  if(p.status==='running'&&!deskGenPoll){deskGenPoll=setInterval(()=>deskGenRefreshProject().catch(error=>deskGenNote('刷新失败：'+error.message)),1600)}
  if(p.status!=='running'&&deskGenPoll){clearInterval(deskGenPoll);deskGenPoll=null}
}
async function deskGenCreate(){
  const name=document.getElementById('deskGenNewName').value.trim(),protagonist_name=document.getElementById('deskGenNewProtagonist').value.trim();
  if(!name||!protagonist_name)return deskGenNote('请填写小说名称与主角名字');
  if(deskGenBusy||!deskGenCanLeave())return;
  try{const data=await api('/api/generation/create',{method:'POST',body:JSON.stringify({name,protagonist_name})});
    await deskGenLoadProjects();await deskGenSelect(data.project.id);deskGenStage('setup');
    deskGenNote('项目已新建。可先填写全书设定，或直接填写本章章纲并生成。');
  }catch(error){deskGenNote('新建失败：'+error.message)}
}
async function deskGenSaveSettings(){
  if(!deskGenProject||deskGenBusy)return;
  const map={BookOutline:'outline',World:'world',System:'system',Protagonist:'protagonist',Style:'style',Prompt:'generation_prompt',Chars:'chapter_chars',Tokens:'output_tokens'};
  const changes={};for(const [id,key] of Object.entries(map))changes[key]=document.getElementById('deskGen'+id).value.trim();
  try{const data=await api('/api/generation/update',{method:'POST',body:JSON.stringify({id:deskGenProject.id,changes})});
    deskGenProject=data.project;deskGenRenderState();deskGenStage('plan');deskGenNote('全书设定已保存，可规划下一章');
  }catch(error){deskGenNote('设定保存失败：'+error.message)}
}
async function deskGenSavePlan(){
  if(!deskGenProject||deskGenBusy)return false;
  const title=document.getElementById('deskGenPlanTitle').value.trim();
  const outline=document.getElementById('deskGenPlanOutline').value.trim();
  if(!title||!outline){deskGenNote('请填写章节标题和章纲；若希望模型自动规划，请清空章纲并直接点击生成');return false}
  const cast=document.getElementById('deskGenPlanCast').value.split(/[,，、]/).map(x=>x.trim()).filter(Boolean);
  const viewpoint=document.getElementById('deskGenPlanViewpoint').value.trim();
  try{const data=await api('/api/generation/plan',{method:'POST',body:JSON.stringify({id:deskGenProject.id,plan:{title,outline,cast,viewpoint}})});
    deskGenProject=data.project;deskGenRenderState();deskGenStage('run');deskGenNote('本章章纲已保存，可生成正文');return true;
  }catch(error){deskGenNote('章纲保存失败：'+error.message);return false}
}
async function deskGenStart(){
  if(!deskGenProject||deskGenBusy||deskGenProject.status==='running')return;
  const p=deskGenProject;
  if(['BookOutline','World','System','Protagonist','Style','Prompt','Chars','Tokens'].some(id=>{
    const key={BookOutline:'outline',World:'world',System:'system',Protagonist:'protagonist',Style:'style',Prompt:'generation_prompt',Chars:'chapter_chars',Tokens:'output_tokens'}[id];
    return document.getElementById('deskGen'+id).value.trim()!==String(p[key]||'')}))return deskGenNote('全书设定有未保存内容，请先点击“保存全书设定”');
  const typedOutline=document.getElementById('deskGenPlanOutline').value.trim();
  if(typedOutline){
    const typedCast=document.getElementById('deskGenPlanCast').value.split(/[,，、]/).map(x=>x.trim()).filter(Boolean);
    if(typedOutline!==String(p.pending?.outline||'')||document.getElementById('deskGenPlanTitle').value.trim()!==String(p.pending?.title||'')
      ||typedCast.join('|')!==(p.pending?.cast||[]).join('|')||document.getElementById('deskGenPlanViewpoint').value.trim()!==String(p.pending?.viewpoint||''))
      if(!await deskGenSavePlan())return;
  }
  try{const data=await api('/api/generation/start',{method:'POST',body:JSON.stringify({id:deskGenProject.id})});
    deskGenProject=data.project;deskGenRenderState();deskGenStage('run');deskGenNote('已启动单章生成；进度会自动刷新');
    await deskGenRefreshProject();
  }catch(error){deskGenNote('启动失败：'+error.message)}
}
async function deskGenPause(){
  if(!deskGenProject)return;
  try{await api('/api/generation/pause',{method:'POST',body:JSON.stringify({id:deskGenProject.id})});
    deskGenNote('已请求暂停；当前 API 请求完成后保存检查点');
  }catch(error){deskGenNote('暂停失败：'+error.message)}
}
async function deskGenLoadProjects(){
  ensureDesktopGeneration();const data=await api('/api/generation/projects');
  document.getElementById('deskGenProjects').innerHTML=(data.projects||[]).map(p=>
    `<button class="secondary ${deskGenProject?.id===p.id?'is-active':''}" onclick="deskGenSelect('${p.id}')">${escapeHtml(p.name)} · ${p.chapters} 章</button>`).join('')||'<div class="generation-empty">还没有项目。可在“项目设定”中新建，或导入项目 JSON。</div>';
}
async function deskGenSelect(id){
  if(deskGenBusy||!deskGenCanLeave())return;
  try{const data=await api('/api/generation/project/'+id);deskGenProject=data.project;deskGenChapter=null;deskGenNumber=0;
    document.getElementById('deskGenEditor').hidden=true;document.getElementById('deskGenHeading').textContent=data.project.name;
    document.getElementById('deskGenEmpty').hidden=false;
    document.getElementById('deskGenEmpty').textContent=data.project.chapters.length?'选择左侧章节，查看或修订本章。':'这本书还没有已完成章节。先保存设定与章纲，再点击“生成下一章”。';
    document.getElementById('deskGenExportBtn').disabled=false;
    deskGenFillSettings();deskGenFillPlan();deskGenRenderState();
    document.getElementById('deskGenChapters').innerHTML=data.project.chapters.map(c=>
      `<button class="secondary" onclick="deskGenOpen(${c.number})">第 ${c.number} 章 · ${escapeHtml(c.title||'未命名')} · ${c.chars} 字${c.memory_stale?' · 记忆待复核':''}</button>`).join('')||'<div class="generation-empty">本项目还没有已完成章节。</div>';
    await deskGenLoadProjects();
    deskGenNote(`已打开“${data.project.name}”，共 ${data.project.chapters.length} 章`);
  }catch(error){deskGenNote('打开项目失败：'+error.message)}}
function deskGenPaint(chapter){
  deskGenChapter=chapter;deskGenNumber=chapter.number;
  for(const key of ['title','outline','summary','content'])
    document.getElementById('deskGen'+key[0].toUpperCase()+key.slice(1)).value=chapter[key]||'';
  document.getElementById('deskGenHeading').textContent=`第 ${chapter.number} 章 · ${chapter.title}`;
  document.getElementById('deskGenWarning').textContent=chapter.memory_stale?'本章正文已修改，结构化角色、事件和世界记忆尚未重算；继续生成前请复核。':'';
  document.getElementById('deskGenRestoreBtn').hidden=!(chapter.revision_history||[]).length;
  document.getElementById('deskGenEmpty').hidden=true;
  document.getElementById('deskGenEditor').hidden=false;
}
async function deskGenOpen(number){
  if(!deskGenProject||deskGenBusy||!deskGenCanLeave())return;
  try{deskGenNote(`正在加载第 ${number} 章…`);
    const result=await api(`/api/generation/chapter/${deskGenProject.id}/${number}`);
    deskGenPaint(result.chapter);deskGenStage('draft');deskGenNote(`第 ${number} 章已载入`);
  }catch(error){deskGenNote('加载失败：'+error.message)}
}
async function deskGenSave(){
  if(!deskGenProject||!deskGenChapter||deskGenBusy)return;
  const changes={};for(const key of ['title','outline','summary','content'])
    changes[key]=document.getElementById('deskGen'+key[0].toUpperCase()+key.slice(1)).value.trim();
  if(!changes.title||!changes.outline||!changes.content)return deskGenNote('标题、章纲和正文不能为空');
  deskGenSetBusy(true);deskGenNote('正在保存本章…');
  try{const data=await api('/api/generation/save_chapter',{method:'POST',body:JSON.stringify({id:deskGenProject.id,number:deskGenNumber,
    expected_updated_at:deskGenChapter.updated_at||deskGenChapter.accepted_at||0,changes})});
    deskGenPaint(data.chapter);deskGenNote('本章修改已保存');
    await deskGenRefreshProject();
  }catch(error){deskGenNote('保存失败：'+error.message)}finally{deskGenSetBusy(false)}
}
async function deskGenRegenerate(){
  if(!deskGenProject||!deskGenChapter||deskGenBusy)return;
  if(deskGenDirty())return deskGenNote('请先保存本章修改，再按新章纲重生成');
  if(!confirm('将按已保存的章纲调用当前模型重生成本章，可能产生 API 费用。成功后保留旧稿，后续章节与结构化记忆不会自动改动。继续吗？'))return;
  deskGenSetBusy(true);deskGenNote(`正在重新生成第 ${deskGenNumber} 章；原稿暂不覆盖…`);
  try{const data=await api('/api/generation/regenerate_chapter',{method:'POST',body:JSON.stringify({id:deskGenProject.id,
    number:deskGenNumber,expected_updated_at:deskGenChapter.updated_at||deskGenChapter.accepted_at||0})});
    deskGenPaint(data.chapter);deskGenNote('本章已重新生成；请核对梗概、记忆和后续剧情');await deskGenRefreshProject();
  }catch(error){deskGenNote('重生成失败，旧稿未覆盖：'+error.message)}finally{deskGenSetBusy(false)}
}
async function deskGenRestore(){
  if(!deskGenProject||!deskGenChapter||deskGenBusy)return;
  if(deskGenDirty())return deskGenNote('请先保存或放弃未保存的修改');
  if(!confirm('恢复本章上一版章纲与正文？当前稿将保留为可恢复版本。'))return;
  deskGenSetBusy(true);
  try{const data=await api('/api/generation/restore_chapter',{method:'POST',body:JSON.stringify({id:deskGenProject.id,
    number:deskGenNumber,expected_updated_at:deskGenChapter.updated_at||deskGenChapter.accepted_at||0})});
    deskGenPaint(data.chapter);deskGenNote('已恢复上一版；结构化记忆仍需复核');await deskGenRefreshProject();
  }catch(error){deskGenNote('恢复失败：'+error.message)}finally{deskGenSetBusy(false)}
}
async function deskGenRefreshProject(){
  const id=deskGenProject.id,oldCount=deskGenProject.chapters.length;const data=await api('/api/generation/project/'+id);
  if(deskGenProject?.id!==id)return;
  const previousPending=deskGenProject.pending;
  deskGenProject=data.project;deskGenRenderState();
  if(!previousPending&&data.project.pending&&!document.getElementById('deskGenPlanOutline').value.trim())deskGenFillPlan();
  document.getElementById('deskGenChapters').innerHTML=data.project.chapters.map(c=>
    `<button class="secondary" onclick="deskGenOpen(${c.number})">第 ${c.number} 章 · ${escapeHtml(c.title||'未命名')} · ${c.chars} 字${c.memory_stale?' · 记忆待复核':''}</button>`).join('')||'<div class="generation-empty">本项目还没有已完成章节。</div>';
  if(data.project.chapters.length>oldCount){deskGenFillPlan();if(!deskGenDirty())deskGenOpen(data.project.chapters.length)}
}
function deskGenImport(){
  if(deskGenBusy||!deskGenCanLeave())return;
  const input=document.createElement('input');input.type='file';input.accept='.json,application/json';
  input.onchange=async()=>{const file=input.files?.[0];if(!file)return;if(file.size>40000000)return deskGenNote('文件超过 40 MB');
    try{const reader=new FileReader();const dataUrl=await new Promise((resolve,reject)=>{reader.onload=()=>resolve(reader.result);reader.onerror=()=>reject(reader.error);reader.readAsDataURL(file)});
      const result=await api('/api/generation/import',{method:'POST',body:JSON.stringify({content_base64:String(dataUrl).split(',')[1]})});
      await deskGenLoadProjects();await deskGenSelect(result.project.id);deskGenNote('生成项目已导入；独立存档已建立，原文件未修改');
    }catch(error){deskGenNote('导入失败：'+error.message)}};input.click();
}
async function deskGenExport(){
  if(!deskGenProject||deskGenBusy)return;
  try{const result=await api('/api/generation/export',{method:'POST',body:JSON.stringify({id:deskGenProject.id})});
    const blob=new Blob([JSON.stringify(result.project)],{type:'application/json;charset=utf-8'}),url=URL.createObjectURL(blob);
    const link=document.createElement('a');link.href=url;link.download=(deskGenProject.name||'小说生成项目').replace(/[\\/:*?"<>|]/g,'_')+'.pardo-generation.json';
    document.body.appendChild(link);link.click();link.remove();setTimeout(()=>URL.revokeObjectURL(url),60000);
    deskGenNote('已导出项目 JSON；浏览器会显示保存位置或保存在默认下载目录');
  }catch(error){deskGenNote('导出失败：'+error.message)}
}
const _showViewBeforeGeneration=showView;
showView=function(name){
  if(name!=='generation'){if(deskGenProject&&deskGenDirty()&&!deskGenCanLeave())return;return _showViewBeforeGeneration(name)}
  ensureDesktopGeneration();_showViewBeforeGeneration('home');
  for(const view of document.querySelectorAll('.view,.home'))view.classList.remove('active');
  for(const button of document.querySelectorAll('.nav button'))button.classList.remove('active');
  document.getElementById('generationView').classList.add('active');document.getElementById('navGeneration').classList.add('active');
  deskGenLoadProjects().catch(error=>deskGenNote('载入项目失败：'+error.message));
  deskGenLoadWritingProfiles().catch(error=>deskGenPromptNote('配置加载失败：'+error.message));
};
ensureDesktopGeneration();
"""
