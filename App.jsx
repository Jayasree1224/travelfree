import {useCallback,useEffect,useState} from 'react';
const cid=localStorage.cid||(localStorage.cid=Math.random().toString(36).slice(2));
const api=(p,o)=>fetch('/api'+p,o&&{
  method:'POST',
  headers:{'Content-Type':'application/json'},
  body:JSON.stringify(o)
})
.then(async r=>{
  const j=await r.json();
  if(!r.ok) throw new Error(j.error);
  return j;
});
const T={en:{from:'From',to:'To',date:'Date',mode:'Travel by',all:'Train or bus',train:'Train',bus:'Bus',search:'Find trips',sort:'Sort by',price:'Cheapest',fast:'Fastest',early:'Earliest',
  left:'seats left',pick:'Choose seats',ladies:'Ladies seat',access:'Easy-access seat (near door)',free:'Free',mine:'Yours',held:'Held by someone',booked:'Booked',
  name:'Passenger name',phone:'Phone (10 digits)',gender:'Gender',pay:'Pay',total:'Total',expires:'Seat hold ends in',none:'No trips found for this day. Try another date.',
  pnr:'Check PNR',cancel:'Cancel booking',done:'Booking confirmed',live:'Live seats'},
 ta:{from:'புறப்படும் இடம்',to:'சேரும் இடம்',date:'தேதி',mode:'பயண வகை',all:'ரயில் அல்லது பேருந்து',train:'ரயில்',bus:'பேருந்து',search:'பயணங்களைத் தேடு',sort:'வரிசை',price:'குறைந்த கட்டணம்',fast:'விரைவானது',early:'முதலில் புறப்படுவது',
  left:'இருக்கைகள் உள்ளன',pick:'இருக்கை தேர்வு',ladies:'பெண்கள் இருக்கை',access:'எளிதில் அணுகும் இருக்கை (கதவருகில்)',free:'காலி',mine:'உங்களுடையது',held:'மற்றவர் தேர்வு',booked:'முன்பதிவு',
  name:'பயணி பெயர்',phone:'தொலைபேசி (10 இலக்கம்)',gender:'பாலினம்',pay:'செலுத்து',total:'மொத்தம்',expires:'இருக்கை தேர்வு முடிய',none:'இந்த நாளுக்குப் பயணங்கள் இல்லை. வேறு தேதியைத் தேர்வுசெய்யுங்கள்.',
  pnr:'PNR பார்க்க',cancel:'முன்பதிவை ரத்து செய்',done:'முன்பதிவு உறுதியானது',live:'நேரடி இருக்கைகள்'}};
const dur=t=>(new Date(t.arrive)-new Date(t.depart))/60000;
const hm=m=>`${Math.floor(m/60)}h ${m%60}m`;

export default function App(){
 const [lang,setLang]=useState('en'),t=T[lang];
 const [cities,setCities]=useState([]),[q,setQ]=useState({from:'Tiruppur',to:'Chennai',date:new Date().toLocaleDateString('en-CA'),mode:''});
 const [trips,setTrips]=useState(null),[sort,setSort]=useState('price'),[pick,setPick]=useState(null),[seats,setSeats]=useState([]);
 const [f,setF]=useState({name:'',phone:'',gender:'M',method:'upi',upi:'',card:'',exp:''}),[err,setErr]=useState(''),[done,setDone]=useState(null);
 const [pnrIn,setPnrIn]=useState(''),[pnrRes,setPnrRes]=useState(null),[now,setNow]=useState(Date.now());
 useEffect(()=>{
  api('/cities').then(data=>{
    setCities(Array.isArray(data) ? data : data.cities || []);
  });
},[]);
 const search=()=>{
  setPick(null);
  setDone(null);

  api(`/trips?${new URLSearchParams(q)}`)
    .then(data=>{
      setTrips(Array.isArray(data) ? data : data.trips || []);
    })
    .catch(e=>{
      console.error(e);
      setTrips([]);
    });
};
 const loadSeats=useCallback(()=>pick&&api(`/trips/${pick.id}/seats?client=${cid}`).then(setSeats),[pick]);
 useEffect(()=>{if(!pick)return;loadSeats();const es=new EventSource('/api/stream');es.addEventListener('seats',e=>e.data===String(pick.id)&&loadSeats());const i=setInterval(()=>{setNow(Date.now());loadSeats()},5000);
  return()=>{es.close();clearInterval(i)}},[pick,loadSeats]);
 useEffect(()=>{const i=setInterval(()=>setNow(Date.now()),1000);return()=>clearInterval(i)},[]);
 const toggle=async s=>{if(s.status==='booked'||s.status==='held')return;setErr('');
  try{await api(s.status==='mine'?'/release':'/hold',{tripId:pick.id,seat:s.n,client:cid});loadSeats()}catch(e){setErr(e.message)}};
 const mine=seats.filter(s=>s.status==='mine'),left=mine.length?Math.max(0,Math.round((Math.min(...mine.map(s=>s.until))-now)/1000)):0;
 const book=async()=>{setErr('');try{setDone(await api('/book',{tripId:pick.id,client:cid,name:f.name,phone:f.phone,gender:f.gender,method:f.method,pay:{upi:f.upi,card:f.card,exp:f.exp}}));loadSeats()}catch(e){setErr(e.message)}};
 const sorted=[...(trips||[])].sort((a,b)=>sort==='price'?a.price-b.price:sort==='fast'?dur(a)-dur(b):a.depart.localeCompare(b.depart));
 const cheap=Math.min(...(trips||[]).map(x=>x.price)),quick=Math.min(...(trips||[]).map(dur));
 const lookup=()=>api('/pnr/'+pnrIn).then(setPnrRes).catch(e=>setPnrRes({error:e.message}));
 const cancel=()=>api(`/pnr/${pnrIn}/cancel`,{}).then(r=>setPnrRes({msg:`Cancelled. ₹${r.refund} will be refunded (80%).`})).catch(e=>setPnrRes({error:e.message}));
 const set=k=>e=>setQ({...q,[k]:e.target.value}),setf=k=>e=>setF({...f,[k]:e.target.value});

 return <>
  <header><h1>Transit<span>Buddy</span></h1>
   <div className="pnr"><input aria-label="PNR" placeholder="TB12345678" value={pnrIn} onChange={e=>setPnrIn(e.target.value)}/>
    <button className="ghost" onClick={lookup}>{t.pnr}</button>
    <button className="ghost" onClick={()=>setLang(lang==='en'?'ta':'en')}>{lang==='en'?'தமிழ்':'English'}</button></div></header>
  <section className="hero"><h2>{lang==='en'?'Pick your seat. Watch it lock in live.':'இருக்கையைத் தேர்ந்தெடுங்கள். உடனே பூட்டப்படுவதைப் பாருங்கள்.'}</h2><p>{lang==='en'?'Trains and buses, pay with UPI, card or wallet.':'ரயில், பேருந்து – UPI, கார்டு அல்லது வாலட்டில் செலுத்துங்கள்.'}</p></section>
  <main>
   {pnrRes&&<div className="panel">{pnrRes.error?<p className="err">{pnrRes.error}</p>:pnrRes.msg?<p className="ok">{pnrRes.msg}</p>:<>
     <b>{pnrRes[0].from_city} → {pnrRes[0].to_city}</b> · {pnrRes[0].tname} · {pnrRes[0].depart.replace('T',' ')} · Seats {pnrRes.map(x=>x.seat).join(', ')} · {pnrRes[0].status}
     {pnrRes[0].status==='CONFIRMED'&&<p><button className="btn alt" onClick={cancel}>{t.cancel}</button></p>}</>}</div>}
   <div className="search">
    <label>{t.from}<select value={q.from} onChange={set('from')}>{cities.map(c=><option key={c}>{c}</option>)}</select></label>
    <label>{t.to}<select value={q.to} onChange={set('to')}>{cities.map(c=><option key={c}>{c}</option>)}</select></label>
    <label>{t.date}<input type="date" value={q.date} min={new Date().toLocaleDateString('en-CA')} onChange={set('date')}/></label>
    <label>{t.mode}<select value={q.mode} onChange={set('mode')}><option value="">{t.all}</option><option value="train">{t.train}</option><option value="bus">{t.bus}</option></select></label>
    <button className="btn alt" style={{alignSelf:'end'}} onClick={search}>{t.search}</button></div>
   {trips&&<div className="bar"><b>{t.sort}:</b>
    {['price','fast','early'].map(s=><button key={s} className={'btn '+(sort===s?'':'alt')} onClick={()=>setSort(s)}>{t[s]}</button>)}</div>}
   {trips&&!trips.length&&<p>{t.none}</p>}
   {sorted.map(x=><div key={x.id} className={'ticket'+(pick?.id===x.id?' sel':'')}>
     <div><h3><span className="mode">{x.mode==='train'?'🚆':'🚌'} {t[x.mode]}</span>{x.name}
       {x.price===cheap&&<span className="tag">{t.price}</span>}{dur(x)===quick&&<span className="tag">{t.fast}</span>}</h3>
      <div>{x.depart.slice(11)} → {x.arrive.slice(11)} <span className="muted">({hm(dur(x))})</span></div>
      <div className="muted">{x.free} {t.left}</div></div>
     <div><div className="price">₹{x.price}</div><button className="btn" disabled={!x.free} onClick={()=>{setPick(x);setDone(null);setErr('')}}>{t.pick}</button></div></div>)}

   {pick&&!done&&<div className="panel two">
    <div><b><span className="live"/>{t.live}</b>
     <div className="seats">{seats.map(s=><button key={s.n} aria-label={`Seat ${s.n} ${s.status}`} className={`seat ${s.type} ${s.status}`} onClick={()=>toggle(s)}>{s.n}</button>)}</div>
     <div className="legend"><span>◻ {t.free}</span><span style={{color:'var(--sea)'}}>◼ {t.mine}</span><span>🟨 {t.held}</span><span>⬜ {t.booked}</span>
      <span style={{color:'var(--pink)'}}>▭ {t.ladies}</span><span>┅ {t.access}</span></div></div>
    <div className="form">
     {mine.length>0&&<p className="muted">{t.expires} {Math.floor(left/60)}:{String(left%60).padStart(2,'0')}</p>}
     <input placeholder={t.name} value={f.name} onChange={setf('name')}/><input placeholder={t.phone} value={f.phone} onChange={setf('phone')} inputMode="numeric"/>
     <select aria-label={t.gender} value={f.gender} onChange={setf('gender')}><option value="M">Male</option><option value="F">Female</option><option value="O">Other</option></select>
     <div className="pay">{['upi','card','wallet'].map(m=><button key={m} className={f.method===m?'on':''} onClick={()=>setF({...f,method:m})}>{m.toUpperCase()}</button>)}</div>
     {f.method==='upi'&&<input placeholder="name@upi" value={f.upi} onChange={setf('upi')}/>}
     {f.method==='card'&&<><input placeholder="1234 5678 9012 3456" value={f.card} onChange={setf('card')}/><input placeholder="MM/YY" value={f.exp} onChange={setf('exp')}/></>}
     {f.method==='wallet'&&<p className="muted">Demo wallet: balance is always enough.</p>}
     <b>{t.total}: ₹{mine.length*pick.price}</b>
     {err&&<div className="err" role="alert">{err}</div>}
     <button className="btn" disabled={!mine.length} onClick={book}>{t.pay} ₹{mine.length*pick.price}</button></div></div>}
   {done&&<div className="panel ok"><h3>{t.done}</h3><p>PNR <b>{done.pnr}</b> · {done.trip.from_city} → {done.trip.to_city} · {done.trip.depart.replace('T',' ')} · Seats {done.seats.join(', ')} · ₹{done.total}</p></div>}
  </main></>;
}
