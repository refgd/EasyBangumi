!function(){var a,e=new URL(location.href),o=e.hostname,n=e.port,r="http://"+o+":"+n,c=null,s=$(".right textarea").get(0);function d(e){var t=$(".loading");e?(a&&(clearTimeout(a),a=null),0==t.length&&$('<div class="loading"><span class="loader"></span></div>').appendTo("body")):0<t.length&&(a=a||setTimeout(function(){t.remove()},600))}function i(){var e="ws://"+o+":"+(Number(n)+1)+"/sourceDebug",t=(c&&c.close(),new WebSocket(e));t.onopen=function(){s.value="",t.send(JSON.stringify({tag:"debug",key:l.getValue()}))},t.onmessage=function(e){e=e.data;s.value+="\n"+e},t.onclose=function(){t=null},c=t}$("[data-act]").click(function(){var e=$(this).data("act");if("test"==e)i();else if("download"==e){var e="/api/downCode",t={code:Base64.encode(l.getValue())},a=function(e){var t,a,o;e=Base64.toUint8Array(e),t="application/octet-stream",a="ext.ebg.jsc",e=new Blob([e],{type:t}),(o=document.createElement("a")).download=a,o.href=URL.createObjectURL(e),o.dataset.downloadurl=[t,o.download,o.href].join(":"),o.style.display="none",document.body.appendChild(o),o.click(),document.body.removeChild(o),setTimeout(function(){URL.revokeObjectURL(o.href)},1500)},o=void 0;(o=void 0===o?!0:o)&&d(!0);var n,c=new FormData;for(n in t)Object.prototype.hasOwnProperty.call(t,n)&&c.append(n,t[n]);$.ajax({url:r+e,data:c,cache:!1,contentType:!1,processData:!1,method:"POST",dataType:"json",success:function(e){o&&d(!1),e.isSuccess?a&&a(e.data):alert(e.errorMsg)}})}});var l=CodeMirror.fromTextArea($(".left textarea").get(0),{lineNumbers:!0,mode:"javascript",theme:"material-darker"});l.on("keyup",function(e,t){190==t.keyCode&&l.showHint()}),$.ajax({url:"../assets/sample.js",method:"GET",dataType:"text",success:function(e){l.setValue(e)}})}();