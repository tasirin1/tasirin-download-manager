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
// Halaman dokumen Scribd: kumpulkan gambar dari CDN scribdassets yang
// ter-render (pratinjau gratis sesi anonim). Abaikan chrome situs
// (ikon/logo/avatar) dan non-gambar; urutkan menurut nomor halaman bila ada.
var docPages=[],seenDocPages={};
function docPageNum(u){var m=u.match(/pages?[_\/\-]?(\d{1,4})/i)||u.match(/[._-](\d{1,4})\.(?:jpe?g|png|webp|gif)(?:[?#]|$)/i);return m?parseInt(m[1],10):-1;}
function collectDocPage(u){u=(u||'').trim();if(!u||u.indexOf('blob:')===0||u.indexOf('data:')===0)return;
if(u.indexOf('//')===0)u=location.protocol+u;if(u.indexOf('http')!==0)return;u=abs(u);
var host='';try{host=new URL(u).hostname.toLowerCase();}catch(e){return;}
if(host.indexOf('scribdassets.com')<0||seenDocPages[u])return;
var low=u.toLowerCase();
if(/icon|logo|avatar|emoji|sprite|badge|button|spinner|placeholder|favicon|1x1|pixel|blank/.test(low))return;
if(low.indexOf('page')<0&&!/[._-]\d{1,4}\.(?:jpe?g|png|webp|gif)(?:[?#]|$)/.test(low))return;
seenDocPages[u]=1;docPages.push({u:u,n:docPageNum(u)});}
docs.forEach(function(doc){try{
var pimgs=doc.getElementsByTagName('img');
for(var g=0;g<pimgs.length&&docPages.length<300;g++){var pim=pimgs[g];
collectDocPage(pim.src);collectDocPage(pim.getAttribute('data-src'));
collectDocPage(pim.getAttribute('data-original'));collectDocPage(pim.getAttribute('data-lazy-src'));
var pset=pim.getAttribute('srcset');
if(pset){var pparts=pset.split(',');for(var s2=0;s2<pparts.length;s2++){collectDocPage(pparts[s2].trim().split(' ')[0]);}}}
}catch(e){}});
docPages.sort(function(a,b){if(a.n>=0&&b.n>=0)return a.n-b.n;if(a.n>=0)return -1;if(b.n>=0)return 1;return 0;});
var pageList=docPages.slice(0,300).map(function(dp,idx){return{url:dp.u,title:'Page '+(dp.n>=0?dp.n:(idx+1))};});
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
hasVideo:(hasVideo||window.__hhBlob===true),hasBlob:(window.__hhBlob===true),episodes:eps,pages:pageList});
}catch(e){return JSON.stringify({src:'',title:'',hasVideo:false,hasBlob:false,episodes:[],pages:[]});}})()
