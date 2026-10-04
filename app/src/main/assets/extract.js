// Skrip grab URL video WebExtractActivity (sumber readable & legal).
// Sengaja TIDAK di-inline di dex dan TIDAK di-minify satu baris:
// blob skrip terpaket memicu heuristik antivirus (Wacatac) + Play Protect.
// Dievaluasi via evaluateJavascript; wajib ekspresi yang mengembalikan
// string JSON {src,title,hasVideo,hasBlob,episodes}.
(function(){try{
var docs=[document];
try{for(var fi=0;fi<window.frames.length;fi++){
try{var fd=window.frames[fi].document;if(fd)docs.push(fd);}catch(fe){}}}catch(ie){}
function abs(u){try{if(u.indexOf('//')===0)return location.protocol+u;
return new URL(u,location.href).href;}catch(e){return u;}}
var mp4=[],hls=[],hasVideo=false;
window.__hhBlob=false;
function collect(u){u=(u||'').trim();if(!u)return;
if(u.indexOf('blob:')===0){window.__hhBlob=true;return;}
if(u.indexOf('//')===0)u=location.protocol+u;
if(u.indexOf('http')!==0)return;
u=abs(u);
if(/\.m3u8/i.test(u)){if(hls.indexOf(u)<0)hls.push(u);}
else{if(mp4.indexOf(u)<0)mp4.push(u);}}
docs.forEach(function(doc){try{
var vs=doc.getElementsByTagName('video');
for(var i=0;i<vs.length;i++){hasVideo=true;
var v=vs[i];
if(v.currentSrc&&v.currentSrc.indexOf('blob:')!==0)collect(v.currentSrc);
else if(v.currentSrc)window.__hhBlob=true;
collect(v.src);collect(v.getAttribute('data-src'));
collect(v.getAttribute('data-video'));}
var ss=doc.getElementsByTagName('source');
for(var j=0;j<ss.length;j++){collect(ss[j].src);
collect(ss[j].getAttribute('data-src'));}
var m=doc.querySelector('meta[property="og:video"]');
if(m)collect(m.content);
var t=doc.querySelector('meta[name="twitter:player:stream"],meta[property="twitter:player:stream"]');
if(t)collect(t.content);
var ls=doc.getElementsByTagName('link');
for(var k=0;k<ls.length;k++){var hr=ls[k].getAttribute('href')||'';
if(/\.mp4|\.m3u8/.test(hr))collect(hr);}
}catch(e){}});
var mp42=[],hls2=[];
function bucket2(u){u=abs((u||'').trim());if(u.indexOf('http')!==0)return;
if(/\.m3u8/i.test(u)){if(hls2.indexOf(u)<0)hls2.push(u);}else{if(mp42.indexOf(u)<0)mp42.push(u);}}
var h='';
try{h=document.documentElement.innerHTML.slice(0,1000000).replace(/\\\//g,'/');}catch(e){}
var fm=h.match(/(?:file|src|source)\s*:\s*["'](https?:[^"']+?\.(?:mp4|m3u8)[^"']*)["']/gi)||[];
for(var f=0;f<fm.length;f++){var fu=fm[f].replace(/^[^"']*["']/, '').replace(/["'].*$/, '');
bucket2(fu);}
var a=h.match(/https?:\/\/[^\s"'<>]+\.mp4[^\s"'<>]*/gi)||[];
for(var p=0;p<a.length;p++)bucket2(a[p]);
var b=h.match(/https?:\/\/[^\s"'<>]+\.m3u8[^\s"'<>]*/gi)||[];
for(var q=0;q<b.length;q++)bucket2(b[q]);
mp4=mp4.concat(mp42);hls=hls.concat(hls2);
var src=mp4.length>0?mp4[0]:(hls.length>0?hls[0]:'');
var eps=[],seenEps={};
docs.forEach(function(doc){try{
var links=doc.getElementsByTagName('a');
for(var n=0;n<links.length&&eps.length<50;n++){
var a=links[n];var href=a.getAttribute('href')||'';if(!href)continue;
var u=abs(href.trim());if(u.indexOf('http')!==0)continue;
var host='';try{host=new URL(u).hostname;}catch(e){continue;}
if(host!==location.hostname)continue;
if(u===location.href)continue;
if(u.indexOf('/watch/')<0)continue;
if(seenEps[u])continue;seenEps[u]=1;
var t=((a.textContent||'').trim().replace(/\s+/g,' ').slice(0,80))||u.split('/').filter(Boolean).pop();
eps.push({url:u,title:t});}
}catch(e){}});
return JSON.stringify({src:src,title:document.title||'',
hasVideo:(hasVideo||window.__hhBlob===true),hasBlob:(window.__hhBlob===true),episodes:eps});
}catch(e){return JSON.stringify({src:'',title:'',hasVideo:false,hasBlob:false,episodes:[]});}})()
