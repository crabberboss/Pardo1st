// One Android back action closes one layer: dialog/sheet, reader controls, subpage, then workspace.
(()=>{
const viewStack=['home'];
let goingBack=false;
const activeView=()=>document.querySelector('section.view.active')?.id.replace(/^view-/,'')||'home';
const originalTab=window.tab;
window.tab=function(name){
  const previous=activeView();
  originalTab(name);
  if(goingBack||activeView()===previous)return;
  if(name==='home'){viewStack.length=1;viewStack[0]='home';return;}
  if(viewStack[viewStack.length-1]!==name)viewStack.push(name);
  if(viewStack.length>24)viewStack.splice(1,viewStack.length-24);
};
let generationPages=['project'];
if(typeof window.genPage==='function'){
  const originalGenPage=window.genPage;
  window.genPage=function(page){
    originalGenPage(page);
    const active=document.querySelector('#view-generation .gen-flow button.active')?.id;
    if(active==='genNav'+page[0].toUpperCase()+page.slice(1)&&generationPages[generationPages.length-1]!==page){
      generationPages.push(page);
      if(generationPages.length>12)generationPages.shift();
    }
  };
}
function backTo(name,action){
  goingBack=true;
  try{if(action)action();else window.tab(name)}finally{goingBack=false;}
  if(viewStack.length>1)viewStack.pop();
  if(viewStack[viewStack.length-1]!==name)viewStack.push(name);
}
window.appHandleBack=()=>{
  const gallery=$('readerImageGallery');
  if(gallery&&gallery.style.display!=='none'){readerCloseImages();return true;}
  if($('chatEditPanel')&&!$('chatEditPanel').classList.contains('hidden')){closeMessageEditor();return true;}
  if($('chatMenu')&&!$('chatMenu').classList.contains('hidden')){toggleChatMenu(false);return true;}
  if($('chatSessions')&&!$('chatSessions').classList.contains('hidden')){toggleChatSessions(false);return true;}
  if(document.body.classList.contains('reader-catalog-open')){readerCloseCatalog();return true;}
  if(document.body.classList.contains('reader-settings-open')){document.body.classList.remove('reader-settings-open');return true;}
  if(document.body.classList.contains('reader-menu-open')){document.body.classList.remove('reader-menu-open');return true;}
  const view=activeView();
  if(view==='generation'&&generationPages.length>1){
    generationPages.pop();
    const previous=generationPages[generationPages.length-1];
    goingBack=true;try{window.genPage(previous)}finally{goingBack=false;}
    return true;
  }
  if(view==='rewrite'&&document.body.classList.contains('reader-single-rewrite')){backTo('reader',()=>readerReturnToBook());return true;}
  if(view==='reader'){backTo('reader-library',()=>mobileReader());return true;}
  if(view==='home')return false;
  const previous=viewStack.length>1?viewStack[viewStack.length-2]:'home';
  backTo(previous);return true;
};
window.appOpenWorkStatus=(target,projectId='')=>{
  if(target==='generation'){
    window.tab('generation');
    if(typeof window.genPage==='function')window.genPage('run');
    if(projectId&&typeof window.genOpenWorkStatus==='function')window.genOpenWorkStatus(projectId);
    else if(typeof window.genRefresh==='function')window.genRefresh();
  }else if(['library','rewrite','home'].includes(target))window.tab(target);
};
document.addEventListener('keydown',event=>{
  if(event.key!=='Escape'||event.defaultPrevented)return;
  if(window.appHandleBack()){event.preventDefault();event.stopPropagation();}
});
})();
