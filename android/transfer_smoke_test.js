const fs=require('fs'),assert=require('assert'),vm=require('vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const js=fs.readFileSync('app/src/main/assets/transfer.js','utf8');
const java=fs.readFileSync('app/src/main/java/com/novelmemory/mobile/MainActivity.java','utf8');
new vm.Script(js);
assert(html.includes('<script src="transfer.js"></script>'));
for(const label of ['知识库与小说原文','小说生成项目','改写稿','改写模板与角色卡','应用设置'])assert(js.includes(label),label);
for(const operation of ['transferOptions','transferSettingsExport','transferSettingsImport','generationPickExport','exportKb','exportRewrites','exportTemplate','exportCard'])assert(js.includes(operation),operation);
assert(java.includes('IMPORT_SETTINGS=21,EXPORT_SETTINGS=22'));
assert(java.includes('api.exportProfiles(includeKeys)'));
assert(java.includes('allowKeys?row.optString("api_key"')===false); // keys are handled in Api, not copied unconditionally here
console.log('Unified transfer UI and native operation smoke tests passed');
