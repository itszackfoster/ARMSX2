package com.armsx2

/**
 * The buzzer page [BuzzPhones] serves to phones. One self-contained file: no external scripts,
 * fonts or images, because the phones may be on the Thor's hotspot with no internet at all.
 *
 * Screens, in order: a join form (your name, and a seat, with a free one suggested), then the
 * buzzer: your name and seat colour across the top with a signal indicator, the red buzzer (it
 * shows "Buzzed in" for a moment when pressed) and the four answer colours, always visible.
 * If the Wi-Fi drops, a "Reconnecting" card covers it while the page gets back in with the seat
 * token the Thor gave it; the seat is held for a minute. Being moved to the Thor or removed ends
 * the session with a message and a "Join again" button.
 *
 * The game's buzzer lights arrive as "light" messages and light the dome, as on a real buzzer.
 *
 * Messages to the Thor: "d<n>" / "u<n>" press and release button n (0 red, 1 blue, 2 orange,
 * 3 green, 4 yellow); JSON for join, rejoin and pong.
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
<title>Buzz Launcher</title>
<style>
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html,body{margin:0;height:100%;background:#0b0d12;color:#e6eaf0;font-family:system-ui,-apple-system,sans-serif;overflow:hidden;overscroll-behavior:none}
.screen{position:fixed;inset:0;display:none;flex-direction:column;padding:max(20px,env(safe-area-inset-top)) 16px max(20px,env(safe-area-inset-bottom))}
.screen.on{display:flex}
#play,#play *{touch-action:none;-webkit-user-select:none;user-select:none;-webkit-touch-callout:none}
.brand{display:flex;align-items:center;gap:10px;font-size:20px;font-weight:900;margin:24px 8px 28px}
.brand i{display:flex;width:30px;height:30px;border-radius:50%;background:#2a2f3a;align-items:center;justify-content:center}
.brand i b{display:block;width:24px;height:24px;border-radius:50%;background:#e0201e}
h1{font-size:28px;line-height:1.15;margin:0 8px 8px;font-weight:900}
.sub{font-size:16px;color:#9aa0a6;line-height:1.5;margin:0 8px 24px}
label{display:block;font-size:14px;font-weight:700;color:#9aa0a6;margin:0 8px 8px}
input{width:100%;height:56px;border-radius:14px;border:2px solid #2a2f3a;background:#161a22;color:#e6eaf0;font:inherit;font-size:20px;padding:0 16px;margin-bottom:24px}
input:focus{outline:none;border-color:#f4d21f}
.seats{display:grid;grid-template-columns:repeat(4,1fr);gap:8px;margin:0 0 8px}
.seat{height:52px;border-radius:12px;border:2px solid transparent;background:#161a22;color:#e6eaf0;font:inherit;font-size:18px;font-weight:900}
.seat.pick{background:#f4d21f;color:#0b0d12}
.seat:disabled{background:transparent;color:#5c626b;border-color:#ffffff14}
.hint{font-size:13px;color:#9aa0a6;margin:0 8px}
.go{margin-top:auto;height:64px;border-radius:999px;border:0;background:#f4d21f;color:#0b0d12;font:inherit;font-size:20px;font-weight:700}
.go:disabled{opacity:.4}
#head{display:flex;align-items:center;justify-content:space-between;padding:4px 4px 12px}
#who{display:flex;align-items:center;gap:10px}
#dot{width:34px;height:34px;border-radius:50%;color:#0b0d12;display:flex;align-items:center;justify-content:center;font-weight:900}
#nm{font-size:18px;font-weight:900}
#pl{font-size:12px;color:#9aa0a6}
#sig{display:flex;align-items:flex-end;gap:3px}
#sig span{width:5px;border-radius:2px;background:#2a2f3a}
#stage{flex:1;display:flex;flex-direction:column;gap:12px}
#bz{flex:0 0 36%;display:flex;align-items:center;justify-content:center;position:relative}
.dome{border-radius:50%;background:#2a2f3a;display:flex;align-items:center;justify-content:center;aspect-ratio:1;height:min(100%,62vw);position:relative}
.dome i{display:block;width:84%;height:84%;border-radius:50%;background:#e0201e;transition:transform .06s}
.dome.p i{transform:scale(.92);background:#9d1615}
.dome.lit{background:#4a2a2a;box-shadow:0 0 36px 10px #ff3b2f88}
.dome.lit i{background:#ff5a47;box-shadow:inset 0 0 0 999px #ffe1dc22}
.dome.lit.p i{background:#c22a1d}
#ring{position:absolute;inset:-14px;border-radius:50%;border:6px solid #e0201e66;display:none}
#tag{position:absolute;bottom:-8px;padding:6px 16px;border-radius:999px;background:#e6eaf0;color:#0b0d12;font-size:14px;font-weight:900;display:none}
.buzzed #ring,.buzzed #tag{display:block}
.bar{flex:1;border-radius:18px;transition:transform .06s,filter .06s}
.bar.p{transform:scale(.97);filter:brightness(.7)}
.cover{position:fixed;inset:0;display:none;align-items:center;justify-content:center;padding:24px;background:#0b0d12cc}
.cover.on{display:flex}
.card{width:100%;border-radius:22px;background:#161a22;padding:24px;display:flex;flex-direction:column;gap:12px;align-items:center;text-align:center}
.card b{font-size:22px}
.card p{margin:0;font-size:15px;color:#9aa0a6;line-height:1.5}
.spin{width:44px;height:44px;border-radius:50%;border:4px solid #2a2f3a;border-top-color:#f4d21f;animation:sp 1s linear infinite}
@keyframes sp{to{transform:rotate(360deg)}}
.card button{height:56px;padding:0 28px;border-radius:999px;border:0;background:#f4d21f;color:#0b0d12;font:inherit;font-size:18px;font-weight:700;margin-top:8px}
</style></head><body>

<div class="screen on" id="join">
 <div class="brand"><i><b></b></i>Buzz Launcher</div>
 <h1 id="jt">Join the game</h1>
 <p class="sub">Your name shows on the Thor so everyone knows whose buzzer is whose.</p>
 <label for="name">Your name</label>
 <input id="name" maxlength="16" autocomplete="nickname" placeholder="Sam">
 <label>Seat</label>
 <div class="seats" id="seats"></div>
 <p class="hint" id="seathint">Any free seat works, 1 to 4 included. Greyed seats are taken.</p>
 <button class="go" id="go" disabled>Join</button>
</div>

<div class="screen" id="play">
 <div id="head">
  <div id="who"><div id="dot"></div><div><div id="nm"></div><div id="pl"></div></div></div>
  <div id="sig" role="img" aria-label="Connection"><span style="height:8px"></span><span style="height:13px"></span><span style="height:18px"></span></div>
 </div>
 <div id="stage">
  <div id="bz"><div class="dome" data-b="0"><div id="ring"></div><i></i></div><div id="tag">Buzzed in</div></div>
  <div class="bar" data-b="1" style="background:#1f6fdb"></div>
  <div class="bar" data-b="2" style="background:#f2871c"></div>
  <div class="bar" data-b="3" style="background:#2eaf4b"></div>
  <div class="bar" data-b="4" style="background:#f4d21f"></div>
 </div>
</div>

<div class="cover" id="lost"><div class="card"><div class="spin"></div><b>Reconnecting to the Thor</b><p id="lostmsg">Check you are still on the same Wi-Fi.</p></div></div>
<div class="cover" id="ended"><div class="card"><b id="endt">Game full</b><p id="endm"></p><button id="again">Join again</button></div></div>

<script>
(function(){
var ws=null,seat=0,suggest=0,seats=[],held={},timer=null,stopped=false,joined=false,mySeat=0,lock=null;
var TOKEN_KEY='buzzToken',NAME_KEY='buzzName';
function store(k,v){try{if(v==null)localStorage.removeItem(k);else localStorage.setItem(k,v)}catch(e){}}
function load(k){try{return localStorage.getItem(k)}catch(e){return null}}
function el(id){return document.getElementById(id)}
function show(id){['join','play'].forEach(function(s){el(s).classList.toggle('on',s===id)})}
function cover(id,on){el(id).classList.toggle('on',on)}
function send(s){if(ws&&ws.readyState===1)ws.send(s)}
var nameIn=el('name');nameIn.value=load(NAME_KEY)||'';
nameIn.addEventListener('input',renderJoin);

function renderJoin(){
 var box=el('seats');box.innerHTML='';
 if(!seat||seats[seat-1]==='phone'||seats[seat-1]==='off')seat=suggest;
 seats.forEach(function(st,i){
  var b=document.createElement('button');b.className='seat';b.textContent=i+1;
  var free=st==='free'||st==='thor';
  b.disabled=!free;
  if(i+1===seat)b.className+=' pick';
  b.onclick=function(){seat=i+1;renderJoin()};
  box.appendChild(b);
 });
 var thorPick=seat&&seats[seat-1]==='thor';
 el('seathint').textContent=thorPick?'Seat '+seat+' is on the Thor now. Taking it moves that player to your phone.':'Any free seat works, 1 to 4 included. Greyed seats are taken.';
 var ok=seat>0&&nameIn.value.trim().length>0;
 el('go').disabled=!ok;
 el('go').textContent=seat?'Join as Player '+seat:'Every seat is taken';
}
el('go').onclick=function(){
 var n=nameIn.value.trim();if(!n||!seat)return;
 store(NAME_KEY,n);
 var t=load(TOKEN_KEY)||(Date.now().toString(36)+Math.random().toString(36).slice(2));
 store(TOKEN_KEY,t);
 send(JSON.stringify({t:'join',name:n,seat:seat,token:t}));
};

function sig(q){
 var c=q==='good'?'#2eaf4b':q==='ok'?'#f2871c':'#e0201e',n=q==='good'?3:q==='ok'?2:1;
 var bars=el('sig').children;
 for(var i=0;i<3;i++)bars[i].style.background=i<n?c:'#2a2f3a';
 el('sig').setAttribute('aria-label',q==='good'?'Strong connection':q==='ok'?'Fair connection':'Weak connection');
}
function keepAwake(){try{if(navigator.wakeLock&&!lock)navigator.wakeLock.request('screen').then(function(l){lock=l;l.addEventListener('release',function(){lock=null})}).catch(function(){})}catch(e){}}

function end(title,msg){
 stopped=true;joined=false;releaseAll();
 cover('lost',false);el('endt').textContent=title;el('endm').textContent=msg;cover('ended',true);
}
el('again').onclick=function(){store(TOKEN_KEY,null);location.reload()};

function connect(){
 ws=new WebSocket('ws://'+location.host+'/ws');
 ws.onmessage=function(e){var m=JSON.parse(e.data);
  if(m.t==='hello'){
   seats=m.seats;suggest=m.suggest>0?m.suggest:0;
   var tok=load(TOKEN_KEY);
   if(tok&&!joined&&mySeat){send(JSON.stringify({t:'rejoin',token:tok}));mySeat=0;return}
   if(joined)return;
   cover('lost',false);
   el('jt').textContent=m.game?'Join '+m.game:'Join the game';
   show('join');renderJoin();
  }
  else if(m.t==='slot'){
   joined=true;mySeat=m.p;store(TOKEN_KEY,m.token);
   cover('lost',false);
   el('dot').textContent=m.p;el('dot').style.background=m.colour;
   el('nm').textContent=m.name;el('pl').textContent='Player '+m.p;
   show('play');sig('good');keepAwake();
  }
  else if(m.t==='ping'){send(JSON.stringify({t:'pong',k:m.k}))}
  else if(m.t==='sig'){sig(m.q)}
  else if(m.t==='light'){document.querySelector('#bz .dome').classList.toggle('lit',!!m.on)}
  else if(m.t==='full'){end('Game full','Every seat is taken. Ask for more players on the Thor, then join again.')}
  else if(m.t==='kicked'){
   store(TOKEN_KEY,null);
   end(m.why==='moved'?'Moved to the Thor':'Removed from the game',m.why==='moved'?'Your seat is back on the Thor screen.':'Someone took you out of the game on the Thor.');
  }
 };
 ws.onclose=function(){
  releaseAll();if(stopped)return;
  if(joined){mySeat=mySeat||1;joined=false;el('lostmsg').textContent='You keep your seat for a minute. Check you are still on the same Wi-Fi.';cover('lost',true)}
  setTimeout(connect,1500);
 };
}
// A phone that was seated rejoins with its token on reconnect; a fresh page asks first.
if(load(TOKEN_KEY))mySeat=1;

function releaseAll(){for(var id in held){var x=held[id];x.classList.remove('p')}held={}}
function down(e){
 var x=e.target.closest('[data-b]');if(!x)return;e.preventDefault();
 var b=x.getAttribute('data-b');held[e.pointerId]=x;x.classList.add('p');send('d'+b);
 if(navigator.vibrate)navigator.vibrate(12);
 if(b==='0'){var bz=el('bz');bz.classList.add('buzzed');clearTimeout(timer);timer=setTimeout(function(){bz.classList.remove('buzzed')},1500)}
}
function up(e){
 var x=held[e.pointerId];if(!x)return;delete held[e.pointerId];
 var b=x.getAttribute('data-b'),still=false;
 for(var id in held)if(held[id]===x)still=true;
 if(!still){x.classList.remove('p');send('u'+b)}
}
var play=el('play');
play.addEventListener('pointerdown',down);
window.addEventListener('pointerup',up);
window.addEventListener('pointercancel',up);
document.addEventListener('contextmenu',function(e){e.preventDefault()});
// Quick taps on the buzzer page must never wait for, or turn into, a double-tap zoom.
['touchstart','touchmove','touchend'].forEach(function(t){play.addEventListener(t,function(e){e.preventDefault()},{passive:false})});
['gesturestart','gesturechange','gestureend','dblclick'].forEach(function(t){document.addEventListener(t,function(e){e.preventDefault()},{passive:false})});
document.addEventListener('visibilitychange',function(){
 if(document.hidden){for(var id in held){send('u'+held[id].getAttribute('data-b'))}releaseAll()}
 else keepAwake();
});
connect();
})();
</script></body></html>"""
}
