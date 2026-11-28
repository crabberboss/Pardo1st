const fs=require('fs'),vm=require('vm'),assert=require('assert');
const source=fs.readFileSync('app/src/main/assets/generation.js','utf8');
assert(source.includes('#view-generation .card{border:0;box-shadow:none'));
assert(source.includes('#view-settings .settings-group{border:0;border-bottom:1px'));
const elements=new Map(),calls=[];
function element(id){
  if(!elements.has(id)){
    const classes=new Set();elements.set(id,{id,innerHTML:'',textContent:'',value:'',checked:false,hidden:false,dataset:{},
      style:{setProperty(){}},classList:{add:x=>classes.add(x),remove:x=>classes.delete(x),contains:x=>classes.has(x),toggle:(x,force)=>{if(force)classes.add(x);else classes.delete(x)}},
      insertAdjacentHTML(){},addEventListener(){},querySelector(){return {insertAdjacentHTML(){},style:{setProperty(){}}}},closest(){return element('card-'+id)},focus(){}});
  }
  return elements.get(id);
}
const context=vm.createContext({
  document:{createElement:()=>({textContent:''}),head:{appendChild(){}},querySelector:()=>({insertAdjacentHTML(){}}),getElementById:element},
  window:{nativeEvent(){}},$:element,req:(op,data)=>calls.push({op,data}),tab(){},notice(){},esc:x=>String(x),
  confirm:()=>true,prompt:()=>null
});
vm.runInContext(source,context);
assert(source.includes(".home-grid .home-entry:last-child")&&source.includes("'beforebegin'"));
context.window.nativeEvent('generationList',[{id:'a',name:'测试书',status:'ready',chapters:0}]);
assert(element('genList').innerHTML.includes('测试书'));
assert(source.includes('文章生成 · 统一提示词'));
assert(source.includes('id="genPromptProject"'));
element('genPromptProject').value='a';context.window.genPromptChoose();
assert(calls.some(x=>x.op==='generationPromptGet'&&x.data.id==='a'));
context.window.nativeEvent('generationUnifiedPrompt',{id:'a',name:'测试书',prompt:'每章保持连贯'});
assert.equal(element('genEditPrompt').value,'每章保持连贯');
element('genEditPrompt').value='每章对白自然';context.window.genSaveUnifiedPrompt();
assert(calls.some(x=>x.op==='generationUpdate'&&x.data.source==='unifiedPrompt'&&x.data.changes.generation_prompt==='每章对白自然'));
context.window.nativeEvent('generationUnifiedPromptSaved',{id:'a',saved_at:Date.now()});
assert(element('genPromptStatus').textContent.includes('已保存'));
assert(source.includes('文章生成 · 参考书提示词提炼'));
context.window.nativeEvent('generationWritingSynthesized',{style_prompt:'文风规则',unified_prompt:'章节规则',source_names:['样书.txt'],sample_chars:2400});
assert.equal(element('genWritingStyle').value,'文风规则');
context.window.genSaveWritingProfile();
assert(calls.some(x=>x.op==='generationWritingProfileSave'&&x.data.profile.format==='pardo-writing-prompt'&&x.data.profile.unified_prompt==='章节规则'));
context.window.nativeEvent('generationWritingProfiles',[{id:'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',name:'样书',style_prompt:'文风规则',unified_prompt:'章节规则',source_names:['样书.txt']}]);
assert(element('genWritingProfiles').innerHTML.includes('选取并应用'));
context.window.genApplyWritingProfile('bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb');
assert(calls.some(x=>x.op==='generationWritingProfileApply'&&x.data.project_id==='a'));
element('genNewName').value='新故事';element('genNewProtagonistName').value='甲';
context.window.genCreate();
assert(calls.some(x=>x.op==='generationCreate'&&x.data.name==='新故事'&&x.data.protagonist_name==='甲'&&!x.data.outline));
context.window.nativeEvent('generationSelected',{id:'a',name:'测试书',status:'waiting_cast',stage:'write',
  outline:'主角追查谜案',protagonist_name:'甲',chapters:[],characters:[],threads:[],world_state:[],
  pending:{number:1,title:'开端',outline:'主角抵达城镇',cast:['甲']}});
assert(element('genPlanStatus').textContent.includes('等待确认'));
context.window.genPage('memory');
assert(element('card-genPlanOutline').classList.contains('hidden'));
assert(!element('card-genTimeline').classList.contains('hidden'));
context.window.nativeEvent('generationExpandSettings',{prompts:{outline:'扩写总纲',world:'扩写世界',system:'扩写系统',protagonist:'扩写主角',style:'扩写文风',chapter:'扩写本章'},limits:{outline:4096,world:8192,system:6144,protagonist:4096,style:3072,chapter:4096}});
assert.equal(element('genExpandPromptChapter').value,'扩写本章');
assert.equal(element('genExpandLimitWorld').value,8192);
context.window.genSaveExpandPrompts();
assert(calls.some(x=>x.op==='generationExpandSettingsSave'&&x.data.limits.world===8192));
element('genEditWorld').value='城镇设定';context.window.genExpand('world');
const expand=calls.findLast(x=>x.op==='generationExpand');assert.equal(expand.data.field,'world');
context.window.nativeEvent('generationExpanded',{field:'world',project_id:'a',request_id:expand.data.request_id,text:'扩写后的城镇设定'});
assert.equal(element('genEditWorld').value,'扩写后的城镇设定');
element('genPlanCast').value='甲，乙';context.window.genSavePlan();
assert(calls.some(x=>x.op==='generationUpdate'&&x.data.changes.pending?.cast.length===2));
context.window.genSaveSettings();
assert.equal(element('genSettingsStatus').textContent,'正在保存项目设定…');
context.window.nativeEvent('generationSettingsSaved',{id:'a',saved_at:Date.now()});
assert(element('genSettingsStatus').textContent.includes('已保存'));
context.window.nativeEvent('generationProject',{id:'a',name:'测试书',status:'paused',auto_continue:false,
  chapters:[],characters:[],threads:[],world_state:[]});
assert.equal(element('genAutoContinue').checked,false);
context.window.genStart();
assert.equal(calls.findLast(x=>x.op==='generationStart').data.auto_continue,false);
element('genAutoContinue').checked=true;context.window.genStart();
assert.equal(calls.findLast(x=>x.op==='generationStart').data.auto_continue,true);
context.window.nativeEvent('generationProject',{id:'a',name:'测试书',status:'paused',auto_continue:false,
  chapters:[{number:1,title:'开端',outline:'旧章纲',summary:'旧梗概',chars:3}],characters:[],threads:[],world_state:[]});
context.window.genOpenChapter(1);
assert.equal(calls.findLast(x=>x.op==='generationChapter').data.number,1);
context.window.nativeEvent('generationChapter',{project_id:'a',chapter:{number:1,title:'开端',outline:'旧章纲',summary:'旧梗概',content:'旧正文',accepted_at:123,revision_history:[]}});
assert.equal(element('genChapterEditOutline').value,'旧章纲');
assert.equal(element('genChapterEditContent').value,'旧正文');
element('genChapterEditOutline').value='新章纲';element('genChapterEditContent').value='新正文';
context.window.genSaveCompletedChapter();
const saved=calls.findLast(x=>x.op==='generationChapterSave');
assert.equal(saved.data.expected_updated_at,123);
assert.equal(saved.data.changes.outline,'新章纲');
assert.equal(saved.data.changes.content,'新正文');
context.window.nativeEvent('generationChapter',{project_id:'a',chapter:{number:1,title:'开端',outline:'新章纲',summary:'旧梗概',content:'新正文',updated_at:456,revision_history:[{content:'旧正文'}]}});
context.window.genRegenerateCompletedChapter();
assert.equal(calls.findLast(x=>x.op==='generationChapterRegenerate').data.expected_updated_at,456);
context.window.nativeEvent('generationChapter',{project_id:'a',chapter:{number:1,title:'开端',outline:'新章纲',summary:'',content:'重生正文',updated_at:789,revision_history:[{content:'新正文'}]}});
context.window.genRestoreCompletedChapter();
assert.equal(calls.findLast(x=>x.op==='generationChapterRestore').data.expected_updated_at,789);
console.log('Generation UI smoke tests passed');
