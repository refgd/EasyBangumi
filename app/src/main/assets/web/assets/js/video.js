!function(){var a,{hostname:e,port:s}=new URL(location.href);const n="http://"+e+":"+s,c=n+"/stream/p/p/p/p/p/p/p/p/p/p/p/p?url=";function o(e,a,s,i=!0){i&&r(!0);const t=new FormData;for(const l in a)Object.prototype.hasOwnProperty.call(a,l)&&t.append(l,a[l]);$.ajax({url:n+e,data:t,cache:!1,contentType:!1,processData:!1,method:"POST",dataType:"json",success:function(e){i&&r(!1),e.isSuccess?s&&s(e.data):alert(e.errorMsg)}})}let i;function r(e){let a=$(".loading");e?(i&&(clearTimeout(i),i=null),0==a.length&&$('<div class="loading"><span class="loader"></span></div>').appendTo("body")):0<a.length&&(i=i||setTimeout(()=>{a.remove()},600))}const d=$("#wrapper"),p={},v={source_key:"",main_tab:"",sub_tab:"",page:""};let u="";let h=!1;function b(){var e;!h&&v.page&&""!=v.page&&(e=d.get(0)).scrollHeight-e.clientHeight-e.scrollTop<150&&f(v.source_key,v.main_tab,v.sub_tab,v.page,!1)}function t(a){p[a]&&(p[a].mainTabs?(O(a,p[a].mainTabs),l(a,p[a].mainTabs[0])):o("/api/getMainTabs",{source_key:a},function(e){p[a].mainTabs=e,O(a,p[a].mainTabs),l(a,p[a].mainTabs[0])}))}function l(a,s){p[a]&&(1==s.type?(f(a,s.label,"",0),S(a,s,[])):(p[a].subTabs||(p[a].subTabs={}),p[a].subTabs[s.label]?(S(a,s,p[a].subTabs[s.label]),f(a,s.label,p[a].subTabs[s.label][0].label,0)):o("/api/getSubTabs",{source_key:a,main_tab:s.label},function(e){p[a].subTabs[s.label]=e,S(a,s,p[a].subTabs[s.label]),f(a,s.label,p[a].subTabs[s.label][0].label,0)})))}function f(a,s,i,e,t=!0){const l=$(".video-list",d);h=!0,0===e&&l.html(""),o("/api/getPageContent",{source_key:a,main_tab:s,sub_tab:i,page:e},function(e){U(l,e.data),v.source_key=a,v.main_tab=s,v.sub_tab=i,v.page=e.nextKey,h=!1,b()},t)}function g(e){o("/api/getDetailed",{source_key:e.source,video_id:e.id},function(e){{var s=e.cartoon;e=e.data;let a='<div class="page detail-page">';if(a=(a=(a=(a=(a=(a=(a=(a=(a+='<div class="header"><div class="logo">')+'<span class="back">< 返回</span></div>')+'</div><div class="panel">')+'<div class="video"><div class="artplayer-app"></div>')+'</div><div class="info">')+'<div class="card"><div class="cover">')+'<img src="'+m(s.coverUrl)+'">')+'</div><div class="right">')+'<div class="title">'+s.title+"</div>",s.genre){let e=s.genre.split(",");0<e.length&&(a+='<div class="genre">',e.forEach(e=>{a+="<span>"+e+"</span>"}),a+="</div>")}a=(a=(a=(a+="</div></div>")+'<div class="desc">'+s.description+'</div><div class="playline"></div>')+'<div class="episodes"></div></div>')+"</div></div>";const t=$(a).appendTo("body"),l=$(".playline",t),n=$(".episodes",t);var i=new Artplayer({container:".artplayer-app",pip:!0,autoMini:!0,screenshot:!0,setting:!0,loop:!0,flip:!0,playbackRate:!0,aspectRatio:!0,fullscreen:!0,subtitleOffset:!0,miniProgressBar:!0,autoOrientation:!0,mutex:!0,backdrop:!0,playsInline:!0,autoPlayback:!0,airplay:!0,customType:{m3u8:function(e,a,s){if(Hls&&Hls.isSupported()){s.hls&&s.hls.destroy();const i=new Hls({xhrSetup:(e,a)=>{-1===a.indexOf(c)&&(a=C(s.baseUrl_,a),a=c+encodeURIComponent(a)),e.open("GET",a,!0)},fetchSetup:function(e,a){return-1===e.url.indexOf(c)&&(e.url=C(s.baseUrl_,e.url),e.url=c+encodeURIComponent(e.url)),new Request(e.url,a)}});i.loadSource(a),i.attachMedia(e),s.hls=i,s.on("destroy",()=>i.destroy())}else e.canPlayType("application/vnd.apple.mpegurl")?e.src=a:s.notice.show="Unsupported playback format: m3u8"}}});return e.forEach((a,e)=>{$('<div class="line'+(0==e?" active":"")+'">'+a.label+"</div>").appendTo(l).click(function(){const e=$(this);e.hasClass("active")||(e.siblings().removeClass("active"),e.addClass("active"),x(i,s.source,s.id,n,a.episode))})}),x(i,s.source,s.id,n,e[0].episode,!0),void $(".back",t).click(function(){i.destroy(),t.remove()})}})}function y(a,e,s,i){o("/api/getPlayInfo",{source_key:e,video_id:s,episode:JSON.stringify(i)},function(e){2==e.decodeType?(a.baseUrl_=e.uri,a.type="m3u8"):(a.baseUrl_=e.uri,a.type="mp4"),a.url=c+encodeURIComponent(e.uri)})}function m(e){return"file://"==e.substring(0,7)||"content://"==e.substring(0,10)?c+encodeURIComponent(e):e}let _=!1,k={source_key:"",key_word:"",page:""};function T(e){var a;!_&&k.page&&""!=k.page&&(a=e.get(0)).scrollHeight-a.clientHeight-a.scrollTop<150&&w(e,k.source_key,k.key_word,k.page,!1)}function w(a,s,i,e,t=!0){const l=$(".video-list",a);_=!0,0===e&&l.html(""),o("/api/search",{source_key:s,key_word:i,page:e},function(e){U(l,e.data),k.source_key=s,k.key_word=i,k.page=e.nextKey,_=!1,T(a)},t)}function C(e,a){a=function(e,a){const s=new URL(e),i=new URL(a);if(s.origin!==i.origin)return a;const t=s.pathname.split("/").filter(Boolean),l=i.pathname.split("/").filter(Boolean);s.pathname.endsWith("/")||t.pop();let n=0;for(;n<t.length&&n<l.length&&t[n]===l[n];)n++;e=t.length-n;const c=l.slice(n);return"../".repeat(e)+c.join("/")+(i.search||"")+(i.hash||"")}(c,a);return new URL(a,e).href}function x(s,i,t,l,e,n=!1){let c=null;e.forEach((a,e)=>{n&&0==e&&(c=a),$("<span"+(n&&0==e?' class="active"':"")+">"+a.label+"</span>").appendTo(l).click(function(){const e=$(this);e.hasClass("active")||(e.siblings().removeClass("active"),e.addClass("active"),y(s,i,t,a))})}),null!=c&&y(s,i,t,c)}function U(s,e){e.forEach(e=>{let a='<div class="item">';a=(a+='<div class="cover">')+'<img src="'+m(e.coverUrl)+'">',e.intro&&(a+='<div class="intro">'+e.intro+"</div>"),a=(a+="</div>")+'<div class="title">'+e.title+"</div></div>",$(a).appendTo(s).find(".cover").click(function(){g(e)})})}function O(s,e){let i=$('<div class="holder"></div>');e.forEach((a,e)=>{$('<div class="tab'+(0==e?" active":"")+'">'+a.label+"</div>").appendTo(i).click(function(){const e=$(this);e.hasClass("active")||(e.siblings().removeClass("active"),e.addClass("active"),l(s,a))})});e=$('<div class="line"><div class="name">分类:</div></div>').append(i);$("#wrapper .tabs").html(e)}function S(i,t,e){let a=$("#wrapper .tabs .line.subtabs");if(0<e.length){let s=$('<div class="holder"></div>');e.forEach((a,e)=>{$('<div class="tab'+(0==e?" active":"")+'">'+a.label+"</div>").appendTo(s).click(function(){const e=$(this);e.hasClass("active")||(e.siblings().removeClass("active"),e.addClass("active"),f(i,t.label,a.label,0))})}),(a=0==a.length?$('<div class="line subtabs"></div>'):a).html('<div class="name">类型:</div>').append(s),$("#wrapper .tabs").append(a)}else 0<a.length&&a.remove()}e="/api/getSources",s={},a=function(e){if(e.forEach(e=>{!(p[e.key]=e).active&&""!=u||(u=e.key)}),""!=u){let e='<div class="wr-page">';e=(e=(e+='<div class="header"><div class="logo">')+'<img src="../assets/imgs/logo.png"></div>')+'<div class="dropdown"><select class="tabsSel">';for(const s in p){var a;Object.prototype.hasOwnProperty.call(p,s)&&(a=p[s],e+='<option value="'+a.key+'"'+(u==a.key?" selected":"")+">"+a.label+"</option>")}e=(e=(e=(e=(e=(e+="</select></div>")+'<div class="search"><div class="search-box">')+'<span class="search-in"><input autocomplete="off" placeholder="搜索 ..." type="text" value=""name="keyword"></span><span class="search-out"><span class="search-btn"><i><svg viewBox="0 0 26 26" aria-hidden="true"class="qy20-header-symbol"><linearGradient x1="0%" y1="99.189%" x2="97.403%" y2="-15.586%"id="__gradient_header_search"><stop offset="0%" class="symbol-stop1-search"></stop><stop offset="100%" class="symbol-stop2-search"></stop></linearGradient><path d="M12.5 4a8.5 8.5 0 0 1 6.66 13.782l2.716 2.533a1 1 0 1 1-1.364 1.462l-2.772-2.584A8.5 8.5 0 1 1 12.5 4zm0 2a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z" fill="url(#__gradient_header_search)"></path></svg></i></span></span>')+"</div></div>")+'</div><div class="tabs"></div>')+'<div class="video-list"></div></div>',d.html(e),$(".header .search-btn",d).click(function(){{var e=$(".header .search-in input",d).val(),a='<div class="page search-page">';a=(a='<div class="page search-page"><div class="wr-page"><div class="header"><div class="logo"><span class="back">< 返回</span></div><div class="search">')+'<div class="search-box"><span class="search-in"><input autocomplete="off" placeholder="搜索 ..." type="text" value="'+e+'" name="keyword"></span><span class="search-out"><span class="search-btn"><i><svg viewBox="0 0 26 26" aria-hidden="true"class="qy20-header-symbol"><linearGradient x1="0%" y1="99.189%" x2="97.403%" y2="-15.586%"id="__gradient_header_search"><stop offset="0%" class="symbol-stop1-search"></stop><stop offset="100%" class="symbol-stop2-search"></stop></linearGradient><path d="M12.5 4a8.5 8.5 0 0 1 6.66 13.782l2.716 2.533a1 1 0 1 1-1.364 1.462l-2.772-2.584A8.5 8.5 0 1 1 12.5 4zm0 2a6.5 6.5 0 1 0 0 13 6.5 6.5 0 0 0 0-13z" fill="url(#__gradient_header_search)"></path></svg></i></span></span></div></div></div><div class="playline"></div><div class="video-list"></div></div></div>';const s=$(a).appendTo("body"),i=$(".playline",s);for(const t in p)if(Object.prototype.hasOwnProperty.call(p,t)){const l=p[t];0<l.versionCode&&$('<div class="line'+(u==t?" active":"")+'">'+l.label+"</div>").appendTo(i).click(function(){const e=$(this);e.hasClass("active")||(e.siblings().removeClass("active"),e.addClass("active"),w(s,l.key,k.key_word,0))})}return w(s,u,e,0),$(".header .search-btn",s).click(function(){w(s,k.source_key,$(".header .search-in input",s).val(),0)}),$(".back",s).click(function(){k.source_key="",k.key_word="",k.page="",s.remove()}),void s.on("scroll",()=>{T(s)})}}),$(".header .tabsSel",d).change(function(){var e=$(this).val();u!=e&&t(u=e)}),d.on("scroll",()=>{b()}),t(u)}},r(!0),$.ajax({url:n+e,data:s,method:"GET",dataType:"json",success:function(e){r(!1),e.isSuccess?a&&a(e.data):alert(e.errorMsg)}})}();