package com.armsx2

/**
 * The buzzer page [BuzzPhones] serves to phones. One self-contained file: no external scripts,
 * fonts or images, because the phones may be on the Thor's hotspot with no internet at all.
 *
 * Unlike the Thor's bottom screen, a phone has the room to show everything at once, so the red
 * buzzer and all four coloured buttons are always visible: no reveal, no fold-back, and the Thor's
 * force-colours toggle has nothing to do here. Messages to the Thor are "d<n>" / "u<n>"
 * (press / release, n = 0 red,
 * 1 blue, 2 orange, 3 green, 4 yellow). It reconnects by itself if the Wi-Fi drops.
 *
 * Kept free of the dollar sign: this is a Kotlin raw string, where it would start a template.
 */
object BuzzPhonePage {
    const val HTML = """<!doctype html>
<html lang="en"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no,viewport-fit=cover">
<meta name="theme-color" content="#0b0d12">
<meta name="apple-mobile-web-app-capable" content="yes">
<title>Buzz! buzzer</title>
<style>
*{box-sizing:border-box;touch-action:none;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
html,body{margin:0;height:100%;background:#0b0d12;color:#e6eaf0;font-family:system-ui,-apple-system,sans-serif;overflow:hidden;touch-action:none;overscroll-behavior:none}
#app{position:fixed;inset:0;display:flex;flex-direction:column;padding:max(14px,env(safe-area-inset-top)) 14px max(14px,env(safe-area-inset-bottom))}
#who{text-align:center;font-weight:700;letter-spacing:1px;font-size:20px;padding:4px 0 12px}
#who small{display:block;font-weight:400;letter-spacing:0;font-size:14px;color:#9aa0a6;margin-top:2px}
#stage{position:relative;flex:1}
.view{position:absolute;inset:0;display:flex;transition:opacity .18s,transform .18s}
#cols{flex-direction:column;gap:12px}
.dome{border-radius:50%;background:#2a2f3a;display:flex;align-items:center;justify-content:center;aspect-ratio:1}
.dome i{display:block;width:84%;height:84%;border-radius:50%;background:#e0201e;transition:transform .06s}
.dome.p i{transform:scale(.92);background:#9d1615}
#smallrow{flex:0 0 34%;display:flex;justify-content:center;align-items:center}
#smallrow .dome{height:100%;max-width:100%}
.bar{flex:1;border-radius:18px;transition:transform .06s,filter .06s}
.bar.p{transform:scale(.97);filter:brightness(.7)}
#msg{position:absolute;inset:0;display:none;align-items:center;justify-content:center;text-align:center;padding:24px;font-size:18px;color:#9aa0a6;background:#0b0d12}
#msg.show{display:flex}
</style></head><body>
<div id="app">
 <div id="who">Connecting<small>&nbsp;</small></div>
 <div id="stage">
  <div class="view" id="cols">
   <div id="smallrow"><div class="dome" data-b="0"><i></i></div></div>
   <div class="bar" data-b="1" style="background:#1f6fdb"></div>
   <div class="bar" data-b="2" style="background:#f2871c"></div>
   <div class="bar" data-b="3" style="background:#2eaf4b"></div>
   <div class="bar" data-b="4" style="background:#f4d21f"></div>
  </div>
  <div id="msg"></div>
 </div>
</div>
<script>
(function(){
var stage=document.getElementById('stage'),who=document.getElementById('who'),msg=document.getElementById('msg');
var ws=null,held={},full=false;
function status(t,s){who.innerHTML=t+'<small>'+(s||'&nbsp;')+'</small>'}
function show(t){msg.textContent=t;msg.className=t?'show':''}
function send(s){if(ws&&ws.readyState===1)ws.send(s)}
function connect(){
 ws=new WebSocket('ws://'+location.host+'/ws');
 ws.onmessage=function(e){var m=JSON.parse(e.data);
  if(m.t==='slot'){full=false;status('PLAYER '+m.p);show('')}
  else if(m.t==='full'){full=true;status('Game full','Every player is taken');show('Every buzzer is in use. Reload when someone leaves, or add players on the Thor.')}
  };
 ws.onclose=function(){releaseAll();if(full)return;status('Reconnecting','Check you are on the same Wi-Fi as the Thor');show('Lost connection to the Thor. Trying again...');setTimeout(connect,1500)};
}
function releaseAll(){for(var id in held){var el=held[id];el.classList.remove('p')}held={}}
function down(e){
 var el=e.target.closest('[data-b]');if(!el)return;e.preventDefault();
 var b=el.getAttribute('data-b');held[e.pointerId]=el;el.classList.add('p');send('d'+b);
 if(navigator.vibrate)navigator.vibrate(12);
}
function up(e){
 var el=held[e.pointerId];if(!el)return;delete held[e.pointerId];
 var b=el.getAttribute('data-b'),still=false;
 for(var id in held)if(held[id]===el)still=true;
 if(!still){el.classList.remove('p');send('u'+b)}
}
stage.addEventListener('pointerdown',down);
window.addEventListener('pointerup',up);
window.addEventListener('pointercancel',up);
document.addEventListener('contextmenu',function(e){e.preventDefault()});
// Phone browsers hold back a quick second tap to see if it is a double-tap zoom, and iPhone
// Safari zooms on it whatever the viewport says. Cancelling the raw touch and gesture events stops
// both, so every tap goes straight through as a press. Pointer events still fire.
['touchstart','touchmove','touchend'].forEach(function(t){document.addEventListener(t,function(e){e.preventDefault()},{passive:false})});
['gesturestart','gesturechange','gestureend','dblclick'].forEach(function(t){document.addEventListener(t,function(e){e.preventDefault()},{passive:false})});
document.addEventListener('visibilitychange',function(){if(document.hidden){for(var id in held){send('u'+held[id].getAttribute('data-b'))}releaseAll()}});
connect();
})();
</script></body></html>"""
}
