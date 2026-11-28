const fs=require('fs');
const vm=require('vm');
const assert=require('assert');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const inline=html.match(/<script>([\s\S]*?)<\/script>/)[1];
const chat=fs.readFileSync('app/src/main/assets/chat.js','utf8');
const special=fs.readFileSync('app/src/main/assets/special_tags.js','utf8');
const reader=fs.readFileSync('app/src/main/assets/reader.js','utf8');
assert(reader.includes("readerCatalog.id='readerCatalogBackdrop'"));
assert(reader.includes('readerCatalogGroups'));
assert(reader.includes('id="readBrightness"'));
assert(reader.includes('id="readFont"'));
assert(reader.includes('class="reader-chapter-current" onclick="readerOpenCatalog()"'));
const elements=new Map(),calls=[];
function element(id){
  if(!elements.has(id)){
    const classes=new Set();
    const node={innerHTML:'',textContent:'',value:'',checked:false,disabled:false,style:{},scrollTop:0,scrollHeight:100,
      classList:{add:name=>classes.add(name),remove:name=>classes.delete(name),contains:name=>classes.has(name),toggle:(name,force)=>{if(force)classes.add(name);else classes.delete(name);}},
      previousElementSibling:{textContent:''},nextElementSibling:{textContent:''},before(){},appendChild(){},addEventListener(){},closest(){return {insertAdjacentHTML(){}}},
      insertAdjacentHTML(){},insertBefore(){},querySelector(){return {insertAdjacentHTML(){},querySelector(){return null}}}};
    node.parentElement={insertAdjacentHTML(){}};node.parentNode={insertBefore(){}};elements.set(id,node);
  }return elements.get(id);
}
const saved=new Map();const context=vm.createContext({document:{getElementById:element,querySelectorAll:()=>[],querySelector:()=>null,createElement:()=>({style:{},parentNode:{insertBefore(){}},insertBefore(){}}),head:{appendChild(){}}},window:{},
  Native:{request:(name,payload)=>calls.push({name,data:JSON.parse(payload)})},
  localStorage:{getItem:key=>saved.get(key)||'',setItem:(key,value)=>saved.set(key,value),removeItem:key=>saved.delete(key)},confirm:()=>true,setTimeout:()=>1,clearTimeout(){}});
vm.runInContext(inline,context);vm.runInContext(chat,context);vm.runInContext(special,context);
assert(html.includes('src="special_tags.js"'));
assert.equal(element('globalPrompt').previousElementSibling.textContent,'知识库统一提示词');
assert.equal(element('chapterPrompt').previousElementSibling.textContent,'本章提示词');
assert(context.window.nativeEvent);
assert(element('chatMenu').classList);
assert(html.includes('onclick="toggleChatSessions(true)" aria-label="选择对话存档"'));
assert(html.includes('id="chatImageInput"'));
assert(!html.includes('id="chapterSearch"'));
assert(html.includes('id="rewriteOriginal"'));
const parsed=vm.runInContext('parseSummary("关键词：甲、密室\\n剧情概览：甲进入密室。\\n人物与关系：甲与乙合作。")',context);
assert.equal(parsed.keywords,'甲、密室');assert.equal(parsed.plot,'甲进入密室。');assert.equal(parsed.characters,'甲与乙合作。');
vm.runInContext('archives=[{id:1,name:"测试书",count:2}];archiveId=1;',context);
vm.runInContext('chatImage={name:"test.png",url:"data:image/png;base64,iVBORw0KGgo="};chatMode="qa";chatBusy=false',context);
element('question').value='描述图片';vm.runInContext('sendChat()',context);
assert(calls.some(c=>c.name==='chatSend'&&c.data.image_url==='data:image/png;base64,iVBORw0KGgo='));
vm.runInContext('chatBusy=false;chatImage=null',context);
vm.runInContext('chapterTotal=97;chapterOffset=40;chapterRows=[{id:2,title:"第二页",chars:100,summary:"",error:""}];renderChapters()',context);
assert.equal(element('pageText').textContent,'2/3 页');
assert(!element('chapterList').innerHTML.includes('未改写'));
element('chapterPage').value='3';vm.runInContext('goToPage()',context);
assert(calls.some(c=>c.name==='chapters'&&c.data.offset===80));
const desktopTemplate={format:'novel-memory-rewrite-template',version:1,name:'测试模板',tags:[],filter:{mode:'all',tags:['密室']},rewrite:{global_prompt:'改写',chapter_prompt:'本章',rules:'不重复'}};
context.window.nativeEvent('rewriteTemplate',{archive_id:1,template:desktopTemplate});
assert.equal(element('templateRules').value,'不重复');
element('globalPrompt').value='此书独立统一提示词';
context.window.nativeEvent('rewriteTemplate',{archive_id:1,template:desktopTemplate,apply_prompt:false});
assert.equal(element('globalPrompt').value,'此书独立统一提示词');
vm.runInContext('selectArchive(2)',context);
assert(calls.some(c=>c.name==='rewritePrompt'&&c.data.archive_id===2));
context.window.nativeEvent('rewritePrompt',{archive_id:2,value:'乙书专属',request_id:1});
assert.equal(element('globalPrompt').value,'乙书专属');
vm.runInContext('selectArchive(1)',context);
element('globalPrompt').value='甲书专属';vm.runInContext('savePromptLocal();selectArchive(2)',context);
assert(calls.some(c=>c.name==='saveRewritePrompt'&&c.data.archive_id===1&&c.data.value==='甲书专属'));
vm.runInContext('selectArchive(1)',context);
context.window.nativeEvent('rewriteTemplate',{archive_id:1,template:desktopTemplate,apply_prompt:false});
vm.runInContext('estimateRewrite()',context);
assert(calls.some(c=>c.name==='estimate'&&c.data.template_filter.tags[0]==='密室'));
vm.runInContext('chapterId=12;activeChapter={id:12,title:"开端",rewrite:""};renderSource(activeChapter,"开端原文",0,4)',context);
element('sourceEditText').value='修改后的原文';vm.runInContext('saveChapterSource()',context);
assert(calls.some(c=>c.name==='saveChapterSource'&&c.data.chapter_id===12&&c.data.expected==='开端原文'));
vm.runInContext('deleteSelectedChapter()',context);
assert(calls.some(c=>c.name==='deleteChapter'&&c.data.chapter_id===12));
context.window.chatEvent('chatState',{archive_id:1,mode:'qa',profile_id:0,
  profiles:[],sessions:[{id:8,name:'存档一'}],session:{id:8,name:'存档一',cutoff:0},
  messages:[{id:10,role:'user',content:'你好'},{id:11,role:'assistant',content:'你好！'}],
  preset:{use_archive_summary:true,use_chapter_summary:true,use_source_text:true},globals:{}});
assert(element('chatMessages').innerHTML.includes('你好！'));
vm.runInContext('toggleChatSessions(true)',context);
assert(!element('chatSessions').classList.contains('hidden'));
vm.runInContext('regenerateMessage(11)',context);
assert(calls.some(c=>c.name==='chatSend'&&c.data.assistant_id===11&&c.data.session_id===8));
context.window.chatError('测试错误');
vm.runInContext('openMessageEditor(10)',context);
element('chatEditText').value='修改后的问题';
vm.runInContext('saveAndRegenerate()',context);
assert(calls.some(c=>c.name==='chatSend'&&c.data.assistant_id===11&&c.data.text==='修改后的问题'));
context.window.nativeEvent('specialTagState',{archive_id:1,tags:[{id:5,name:'密室双人',color:'#a45e4f',recognition_prompt:'两人在密室'}],hits:[]});
vm.runInContext('toggleSpecialFilter(5)',context);
assert.equal(element('rewriteScope').value,'special');
assert.equal(vm.runInContext('templateFilter().tags[0]',context),'密室双人');
vm.runInContext('showRewrite({id:12,title:"开端",rewrite:"",chapter_prompt:"专属章提示",extra_instruction:"额外规则"})',context);
assert.equal(element('chapterPrompt').value,'专属章提示');
assert.equal(element('extraInstruction').value,'额外规则');
vm.runInContext('archiveId=1;chapterId=12;queueChapterPrompt();flushChapterPrompt()',context);
assert(calls.some(c=>c.name==='saveChapterPrompt'&&c.data.chapter_id===12&&c.data.prompt==='专属章提示'));
assert(html.includes('value="openrouter"'));
assert(html.includes('id="modelList"'));
assert(html.includes('onclick="loadModels()"'));
context.window.nativeEvent('config',{base_url:'https://openrouter.ai/api/v1',model:'deepseek/deepseek-v4-flash',has_key:true,default_model:'deepseek/deepseek-v4-flash'});
assert.equal(element('apiUrl').value,'https://openrouter.ai/api/v1');
context.window.nativeEvent('apiModels',{ok:true,model_count:2,models:['model-a','model-b']});
assert.equal(element('modelStatus').textContent.includes('已读取 2 个模型'),true);
context.window.nativeEvent('apiStatus',{ok:false,error_label:'认证失败',summary:'渠道：DeepSeek\nHTTP：401'});
assert.equal(element('apiStatus').textContent.includes('连接失败'),true);
console.log('Summary, chat, provider and card UI smoke tests passed');
