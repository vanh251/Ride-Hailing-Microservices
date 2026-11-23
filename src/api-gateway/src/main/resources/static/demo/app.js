import {request, validSession, driverActions, bookPair, raceOutcome, canReplaceSession, bookingUncertain, parseRoute} from './client.mjs';

const $ = id => document.getElementById(id);
const esc = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
const storageKey = 'ride-lab-demo-v1';
const terminal = new Set(['PAID','PAYMENT_FAILED','CANCELLED']);
const labels = {SEARCHING:'ĐANG TÌM TÀI XẾ',ASSIGNING:'CHỜ DRIVER XÁC NHẬN',ASSIGNED:'ĐÃ GÁN TÀI XẾ',IN_PROGRESS:'ĐANG DI CHUYỂN',COMPLETED:'CHỜ THANH TOÁN',PAID:'ĐÃ THANH TOÁN',PAYMENT_FAILED:'THANH TOÁN THẤT BẠI',CANCELLED:'ĐÃ HỦY'};
const titles = {
  passenger:['Hành khách','Chuyến đi tiếp theo của bạn.','Đặt chuyến, quan sát matching và theo dõi thanh toán trên cùng một hành trình.'],
  driver:['Tài xế','Sẵn sàng cho hành trình mới.','Cập nhật vị trí, nhận chuyến và đưa hành khách đến nơi an toàn.'],
  console:['Trung tâm demo','Nhìn rõ cách hệ thống vận hành.','Hai yêu cầu đồng thời, phục hồi dịch vụ và những bằng chứng từ hệ thống thật.']
};
let saved;
try { saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null'); } catch { saved = null; }
let sessions = saved?.sessions || {};
let trips = saved?.trips || {};
let pendingBookings = saved?.pendingBookings || {};
let observations = Array.isArray(saved?.observations) ? saved.observations.slice(0,100) : [];
let selected = saved?.selected === 'b' ? 'b' : 'a';
let driver = null, payments = {}, notifications = {}, readAt = {}, lastSync = null;
let busy = false, pollPromise = null, gatewayOnline = null, race = saved?.race || null;
let settings = {...{compose:location.port && location.port!=='8080'?'':'docker compose',grafana:location.port==='18080'?'http://localhost:13000':'http://localhost:3000',prometheus:location.port==='18080'?'http://localhost:19090':'http://localhost:9090'},...saved?.settings};
const role = slot => slot === 'driver' ? 'DRIVER' : 'PASSENGER';
const ready = slot => validSession(sessions[slot],role(slot));
const active = slot => trips[slot] && !terminal.has(trips[slot].status);
const fresh = slot => readAt[slot] && Date.now()-readAt[slot] < 8000;
const money = amount => amount == null ? '—' : new Intl.NumberFormat('vi-VN').format(amount);
const clock = time => new Date(time).toLocaleTimeString('vi-VN',{hour:'2-digit',minute:'2-digit',second:'2-digit'});
function persist() {
  try { sessionStorage.setItem(storageKey,JSON.stringify({sessions,trips,pendingBookings,observations,selected,settings,race,form:formValues()})); }
  catch { /* Demo remains usable when browser storage is unavailable. */ }
}
function notice(message,type='info') { $('notice').textContent=message; $('notice').className=`notice ${type}`; $('notice').hidden=false; }
function observe(message,trace='',error=false) {
  observations.unshift({time:new Date().toISOString(),message,trace,error});
  observations=observations.slice(0,100); renderLog(); persist();
}
function requireSession(slot) { if (!ready(slot)) throw new Error(`Cần đăng nhập phiên ${slot === 'driver' ? 'Tài xế' : `Hành khách ${slot.toUpperCase()}`} hợp lệ.`); return sessions[slot]; }
async function api(path,options={},slot=null,quiet=false) {
  try {
    const response=await request(path,{...options,...(slot ? {token:requireSession(slot).accessToken} : {})});
    if (!quiet) observe(`${options.method || 'GET'} ${path} · HTTP ${response.status}`,response.correlationId);
    return response.data;
  } catch (error) {
    if (error.status === 401 && slot && sessions[slot]) { delete sessions[slot].accessToken; persist(); }
    if (!quiet) observe(`${options.method || 'GET'} ${path} · ${error.message}`,error.correlationId || '',true);
    throw error;
  }
}
async function run(label,action) {
  if (busy) return;
  busy=true; render(); notice(`${label}…`);
  try { if(pollPromise) await pollPromise; await action(); }
  catch(error) { notice(error.name === 'TimeoutError' ? 'Yêu cầu quá thời gian chờ. Hãy làm mới để đối chiếu trạng thái trước khi thử lại.' : error.message,'error'); }
  finally { busy=false; render(); await refresh(); }
}
function formValues() { return ['pickup-address','pickup-lat','pickup-lng','destination-address','destination-lat','destination-lng'].map(id=>[id,$(id)?.value]); }
function route() {
  return parseRoute(Object.fromEntries(['pickup','destination'].map(key=>[key,{
    address:$(key+'-address').value,latitude:$(key+'-lat').value,longitude:$(key+'-lng').value
  }])));
}
function updateTrip(slot,trip) {
  const old=trips[slot]; trips[slot]=trip; readAt[slot]=Date.now();
  if (!old || old.tripId !== trip.tripId || old.status !== trip.status) observe(`Hành khách ${slot.toUpperCase()} · ${trip.status} · Trip ${trip.tripId}`,trip.correlationId || '');
  persist();
}
function badge(status) {
  const color=['PAID','AVAILABLE'].includes(status)?'green':['CANCELLED','PAYMENT_FAILED','OFFLINE'].includes(status)?'red':['SEARCHING','ASSIGNING','RESERVED','COMPLETED'].includes(status)?'orange':'blue';
  return `<span class="badge ${color}">${esc(labels[status] || status || 'CHƯA CÓ CHUYẾN')}</span>`;
}
function tripHtml(trip) {
  return `<div class="detail-grid"><div><small>Điểm đón</small><strong>${esc(trip.pickup?.address)}</strong></div><div><small>Điểm đến</small><strong>${esc(trip.destination?.address)}</strong></div><div><small>Giá ước tính</small><strong>${money(trip.estimatedFare)} VND</strong></div><div><small>Giá cuối cùng</small><strong>${money(trip.finalFare)} VND</strong></div></div><div class="id-line mono">Trip ID · ${esc(trip.tripId)}<br>Driver ID · ${esc(trip.driverId || 'Chưa được gán')}<br>Correlation ID · ${esc(trip.correlationId || '—')}</div>`;
}
function renderLog() {
  $('observation-log').innerHTML=observations.length ? observations.map(o=>`<div class="observation-item"><time>${esc(clock(o.time))}</time><div><strong class="${o.error?'error-text':''}">${esc(o.message)}</strong>${o.trace?`<small class="mono">Correlation · ${esc(o.trace)}</small>`:''}</div></div>`).join('') : '<p class="muted">Chưa có thao tác. Mỗi hành trình sẽ có Trip ID và Correlation ID để đối chiếu.</p>';
}
function render() {
  const current=trips[selected];
  $('session-count').textContent=`${['a','b'].filter(ready).length} / 2 sẵn sàng`;
  $('driver-summary').textContent=ready('driver') ? driver?`${driver.status}${fresh('driver')?'':' · dữ liệu cũ'}`:'Đang đọc trạng thái' : 'Chưa đăng nhập';
  $('last-sync').textContent=lastSync ? `${clock(lastSync)}${Date.now()-lastSync>8000 ? ' · dữ liệu cũ' : ''}` : 'Chưa có dữ liệu';
  $('gateway-status').textContent=gatewayOnline === null ? 'Đang kết nối Gateway…' : gatewayOnline ? 'Gateway đang kết nối' : 'Gateway không phản hồi';
  $('gateway-status').className=`connection ${gatewayOnline === null?'':gatewayOnline?'online':'offline'}`;
  for (const slot of ['a','b']) {
    $(`slot-${slot}-state`).textContent=ready(slot)?'Đã đăng nhập':'Chưa đăng nhập';
    document.querySelector(`[data-passenger="${slot}"]`).classList.toggle('selected',slot===selected);
    document.querySelector(`[data-passenger="${slot}"]`).setAttribute('aria-pressed',String(slot===selected));
  }
  $('passenger-account').innerHTML=ready(selected)?`<strong>${esc(sessions[selected].email || `Hành khách ${selected.toUpperCase()}`)}</strong><span class="mono">${esc(sessions[selected].userId)}</span>`:'Tạo hồ sơ demo hoặc đăng nhập tài khoản có sẵn bên dưới.';
  $('book').disabled=busy || !ready(selected) || Boolean(active(selected)) || Boolean(pendingBookings[selected]);
  $('quote').disabled=busy || !ready(selected);
  $('prepare').disabled=busy;
  for(const control of document.querySelectorAll('#booking-form input, #login-form input, #login-form select, #login-form button, #recover-form input, #recover-form button, [data-passenger], #driver-trip-slot, #clear-session')) control.disabled=busy;
  $('pending-booking').hidden=!pendingBookings[selected];
  $('pending-booking').textContent=pendingBookings[selected]?`Chưa rõ kết quả đặt chuyến. Không đặt lại trước khi đối chiếu. Correlation ID: ${pendingBookings[selected].correlationId}. Khôi phục theo dõi bằng Trip ID bên dưới.`:'';
  $('trip-status').outerHTML=`<span id="trip-status">${current?badge(current.status):'<span class="badge neutral">CHƯA CÓ CHUYẾN</span>'}</span>`;
  if (current) {
    $('trip-detail').className='';
    $('trip-detail').innerHTML=tripHtml(current)+(!fresh(selected)?'<p class="stale-note">Chưa đọc được trạng thái mới. Thời điểm phản hồi gần nhất hiển thị phía trên.</p>':'');
    const stages=['SEARCHING','ASSIGNED','IN_PROGRESS','COMPLETED','PAID'];
    const index=stages.indexOf(current.status);
    $('trip-progress').hidden=false;
    $('trip-progress').innerHTML=stages.map((s,i)=>`<span class="progress-step ${index>=i?'done':''}">${['Tìm xe','Đã gán','Di chuyển','Hoàn thành','Thanh toán'][i]}</span>`).join('');
  } else { $('trip-detail').className='empty-state'; $('trip-detail').innerHTML='<span class="empty-icon"><svg><use href="#i-car"/></svg></span><h3>Hành trình bắt đầu từ một yêu cầu.</h3><p>Chuẩn bị hồ sơ demo hoặc đăng nhập, sau đó chọn điểm đón và đặt chuyến.</p>'; $('trip-progress').hidden=true; }
  $('cancel-trip').hidden=!current || !['SEARCHING','ASSIGNING','ASSIGNED'].includes(current.status);
  $('cancel-trip').disabled=busy || !ready(selected) || !fresh(selected);
  $('copy-trip').hidden=!current;
  const payment=payments[selected];
  $('payment-detail').innerHTML=payment?`<div class="payment-summary ${payment.status==='FAILED'?'failed':''}"><span>Payment · ${esc(payment.status)}</span><strong>${money(payment.amount)} ${esc(payment.currency || 'VND')}</strong></div>${!fresh('payment-'+selected)?'<p class="stale-note">Payment: đang hiển thị phản hồi cũ; chưa đọc lại được dịch vụ.</p>':''}`:'';
  const notes=notifications[selected] || [];
  $('notifications').innerHTML=notes.length?notes.map(n=>`<div class="notification-item"><svg><use href="#i-check"/></svg><div><strong>${esc(n.title)} · ${esc(n.recipientType)}</strong><p>${esc(n.message || n.body || '')}</p></div></div>`).join(''):'<p class="muted">Chưa đọc được thông báo cho chuyến hiện tại.</p>';
  $('driver-name').textContent=driver?.fullName || 'Tài xế demo';
  $('driver-id').textContent=sessions.driver?.userId || 'Chưa có phiên tài xế';
  $('driver-status').outerHTML=`<span id="driver-status">${ready('driver')&&driver?fresh('driver')?badge(driver.status):'<span class="badge orange">TRẠNG THÁI CŨ · '+esc(driver.status)+'</span>':'<span class="badge neutral">CHƯA CÓ DỮ LIỆU</span>'}</span>`;
  const held=driver && ['BUSY','RESERVED'].includes(driver.status);
  for(const id of ['driver-location','driver-available','driver-offline']) $(id).disabled=busy || !ready('driver') || !fresh('driver') || Boolean(held);
  const driverTrip=trips[$('driver-trip-slot').value];
  const owned=ready('driver') && driverTrip?.driverId===sessions.driver.userId;
  $('driver-trip-detail').innerHTML=owned?badge(driverTrip.status)+tripHtml(driverTrip)+(!fresh($('driver-trip-slot').value)?'<p class="stale-note">Đang hiển thị phản hồi Trip cũ.</p>':''):'<p class="muted">Chưa có chuyến được gán cho tài xế này. Chọn chuyến A hoặc B sau khi matching hoàn tất.</p>';
  const actions=driverActions(driverTrip,ready('driver')?sessions.driver.userId:null);
  for(const action of ['accept','start','complete']) $(`driver-${action}`).disabled=busy || !fresh($('driver-trip-slot').value) || !actions.includes(action);
  $('race').disabled=busy || !['a','b','driver'].every(ready) || ['a','b'].some(active) || Object.keys(pendingBookings).length>0 || driver?.status!=='AVAILABLE' || !fresh('driver');
  $('race-trips').innerHTML=['a','b'].map(slot=>`<div class="race-trip"><h3>Hành khách ${slot.toUpperCase()}</h3>${trips[slot]?badge(trips[slot].status):'<span class="badge neutral">CHƯA ĐẶT</span>'}<div class="mono">${esc(trips[slot]?.tripId || 'Chờ yêu cầu mới')}</div></div>`).join('');
  if(race) {
    const currentRace=['a','b'].every(slot=>trips[slot]?.tripId===race[slot] && fresh(slot)) && fresh('driver');
    const outcome=currentRace?raceOutcome([trips.a,trips.b],driver):'waiting';
    $('race-result').className=`info-box ${outcome==='exclusive'?'success':outcome==='conflict'?'error':''}`;
    $('race-result').textContent=outcome==='exclusive'?'Đã quan sát: chỉ một chuyến được gán tài xế; chuyến còn lại chưa được gán hoặc đã hủy. Đối chiếu DB để nghiệm thu quyền sở hữu.':outcome==='conflict'?'Phát hiện bất thường: cùng tài xế được gán cho cả hai chuyến. Cần đối chiếu ngay database.':'Đã gửi yêu cầu. Đang chờ đủ phản hồi mới từ Trip và Driver để so sánh.';
  }
  renderLog();
}
function refresh() {
  if(pollPromise) return pollPromise;
  if(busy) return Promise.resolve();
  pollPromise=readAll().finally(()=>{pollPromise=null;render();});
  return pollPromise;
}
async function readAll() {
  const tasks=[];
  tasks.push(api('/actuator/health',{},null,true).then(data=>{ gatewayOnline=data?.status==='UP'; }).catch(()=>{gatewayOnline=false;}));
  for(const slot of ['a','b']) if(ready(slot) && trips[slot]) {
    const tripId=trips[slot].tripId;
    tasks.push(api(`/api/trips/${tripId}`,{},slot,true).then(data=>{
      if(trips[slot]?.tripId===tripId){updateTrip(slot,data); lastSync=Date.now();}
    }).catch(error=>pollError(slot,error)));
    tasks.push(api(`/api/notifications/trip/${tripId}`,{},slot,true).then(data=>{if(trips[slot]?.tripId===tripId) notifications[slot]=Array.isArray(data)?data:[];}).catch(()=>{}));
    if(['COMPLETED','PAID','PAYMENT_FAILED'].includes(trips[slot].status)) tasks.push(api(`/api/payments/${tripId}`,{},slot,true).then(data=>{if(trips[slot]?.tripId===tripId){payments[slot]=data;readAt['payment-'+slot]=Date.now();}}).catch(error=>{ if(error.status!==404) pollError('payment',error); }));
  }
  if(ready('driver')) tasks.push(api(`/api/drivers/${sessions.driver.userId}`,{},'driver',true).then(data=>{driver=data;readAt.driver=Date.now();lastSync=Date.now();}).catch(error=>pollError('driver',error)));
  await Promise.allSettled(tasks);
}
const pollErrors=new Map();
function pollError(slot,error) {
  if(Date.now()-(pollErrors.get(slot)||0)>30000){
    pollErrors.set(slot,Date.now()); observe(`Không đọc được trạng thái ${slot}: ${error.message}`,error.correlationId || '',true);
    notice('Một dịch vụ chưa phản hồi. Giữ lại trạng thái cuối cùng và tự đọc lại sau 2 giây.','error');
  }
}
async function book(slot,payload) {
  requireSession(slot);
  if(active(slot)) throw new Error(`Hành khách ${slot.toUpperCase()} còn chuyến chưa kết thúc.`);
  if(pendingBookings[slot]) throw new Error('Lần đặt trước chưa rõ kết quả. Khôi phục bằng Trip ID trước khi đặt lại.');
  const correlationId=crypto.randomUUID();pendingBookings[slot]={correlationId,time:new Date().toISOString()};persist();
  try {
    const trip=await api('/api/trips',{method:'POST',body:payload,correlationId},slot);
    if(!trip?.tripId) throw Object.assign(new Error('Phản hồi tạo chuyến thiếu Trip ID.'),{correlationId});
    delete pendingBookings[slot];delete payments[slot];delete notifications[slot];updateTrip(slot,trip);
    return trip;
  } catch(error) {
    if(!bookingUncertain(error)) delete pendingBookings[slot];
    persist();throw error;
  }
}
async function login(slot,email,password) {
  const data=await api('/api/auth/login',{method:'POST',body:{email,password}});
  if(data.role!==role(slot)) throw new Error(`Tài khoản phải có vai trò ${role(slot)} cho phiên này.`);
  const unfinished=slot==='driver'?['a','b'].some(active)||Object.keys(pendingBookings).length>0:active(slot)||Boolean(pendingBookings[slot]);
  if(!canReplaceSession(sessions[slot],data,unfinished))throw new Error('Phiên còn chuyến hoạt động hoặc chưa rõ kết quả. Đăng nhập lại đúng tài khoản cũ để tiếp tục.');
  if(sessions[slot]?.userId!==data.userId){
    if(slot==='driver'){driver=null;delete readAt.driver;}
    else{delete trips[slot];delete payments[slot];delete notifications[slot];}
    race=null;
  }
  sessions[slot]={...data,email,expiresAt:Date.now()+data.expiresIn*1000}; persist();
}
async function prepare() {
  if(Object.keys(pendingBookings).length)throw new Error('Có yêu cầu đặt chuyến chưa rõ kết quả. Khôi phục Trip ID trước khi tạo demo mới.');
  if(['a','b'].some(active)) throw new Error('Hãy hoàn thành hoặc hủy các chuyến đang theo dõi trước khi tạo demo mới.');
  if(ready('driver')) {
    const profile=await api(`/api/drivers/${sessions.driver.userId}`,{},'driver');
    if(['BUSY','RESERVED'].includes(profile.status)) throw new Error('Tài xế hiện còn giữ chuyến. Hãy giải quyết chuyến trước khi đổi hồ sơ.');
  }
  const runId=Date.now().toString(36)+crypto.randomUUID().slice(0,6);
  const cell=crypto.getRandomValues(new Uint32Array(1))[0]%400;
  const lat=9+Math.floor(cell/20)*.2, lng=105+(cell%20)*.2;
  $('pickup-lat').value=lat.toFixed(4); $('pickup-lng').value=lng.toFixed(4);
  $('destination-lat').value=(lat+.02).toFixed(4); $('destination-lng').value=(lng+.02).toFixed(4);
  $('quote-amount').innerHTML='— <span>VND</span>';
  trips={};payments={};notifications={};readAt={};race=null;driver=null;sessions={};persist();
  for(const slot of ['a','b','driver']) {
    const email=`web.${slot}.${runId}@ds03.test`,password='Demo@123456';
    const body={fullName:slot==='driver'?'Tài xế Ride Lab':`Hành khách ${slot.toUpperCase()}`,email,password};
    if(slot==='driver') Object.assign(body,{
      licenseNumber:String(crypto.getRandomValues(new Uint32Array(1))[0]).padStart(12,'0'),
      vehicle:{licensePlate:`51F-${String(crypto.getRandomValues(new Uint32Array(1))[0]%100000).padStart(5,'0')}`,make:'Toyota',model:'Vios',color:'White',year:2022,vehicleType:'SEDAN'}
    });
    await api(`/api/auth/register/${slot==='driver'?'driver':'passenger'}`,{method:'POST',body});
    await login(slot,email,password);render();
  }
  observe(`Demo ${runId} · đã tạo hai Passenger và một Driver với điểm đón riêng.`);
  notice('Đã tạo 3 hồ sơ demo. Sang Tài xế: cập nhật vị trí và bật AVAILABLE, rồi đặt chuyến. Mật khẩu các hồ sơ mới: Demo@123456.','success');
}

function view() {
  const key=Object.hasOwn(titles,location.hash.slice(1))?location.hash.slice(1):'passenger';
  for(const name of Object.keys(titles)) $(`view-${name}`).hidden=name!==key;
  for(const item of document.querySelectorAll('[data-view]')) {item.classList.toggle('active',item.dataset.view===key); if(item.dataset.view===key)item.setAttribute('aria-current','page');else item.removeAttribute('aria-current');}
  [$('breadcrumb').textContent,$('page-title').textContent,$('page-subtitle').textContent]=titles[key];
}
function operationCommand(action) {return settings.compose?`${settings.compose} ${action} payment-service`:'Thiết lập tiền tố Compose đúng stack trước khi copy lệnh.';}
function renderSettings() {
  $('compose-prefix').value=settings.compose;$('grafana-url').value=settings.grafana;$('prometheus-url').value=settings.prometheus;
  for(const service of ['grafana','prometheus']) for(const suffix of ['-link','-side']) $(service+suffix).href=settings[service];
  $('stop-command').textContent=operationCommand('stop');$('start-command').textContent=operationCommand('start');
  for(const button of document.querySelectorAll('[data-copy]')) button.disabled=!settings.compose;
  renderScenarios();
}
async function copy(value) {await navigator.clipboard.writeText(value);notice('Đã sao chép.','success');}
const scenarios=[
  ['Khởi động từng service','Start từng service trong Docker Desktop hoặc dùng lệnh Compose.','Quan sát container healthy; Gateway online không chứng minh mọi backend healthy.','docker compose up -d identity-db driver-db trip-db matching-db payment-db kafka kafka-init'],
  ['Tài xế bật nhận chuyến','Tài xế: cập nhật vị trí gần điểm đón, bật AVAILABLE.','Đọc được Driver status AVAILABLE từ API.'],
  ['Hành khách yêu cầu chuyến','Hành khách A: ước tính giá và bấm Đặt chuyến.','Có Trip ID, Correlation ID và phản hồi tạo chuyến HTTP 201.'],
  ['Matching chọn tài xế','Theo dõi trạng thái chuyến, không cần gọi Matching thủ công.','SEARCHING / ASSIGNING → ASSIGNED và Driver ID khớp tài xế demo.'],
  ['Nhận, bắt đầu, hoàn thành','Tài xế: chọn đúng chuyến, Nhận chuyến, Bắt đầu, Hoàn thành.','IN_PROGRESS → COMPLETED → PAID; Payment SUCCESS, tài xế AVAILABLE.'],
  ['Hai hành khách tranh một tài xế','Chỉ chạy khi A và B không còn chuyến hoạt động. Bấm Gửi đồng thời A + B.','Một chuyến ASSIGNED; chuyến còn lại chưa có driver. Đối chiếu reserved_for_trip và version trong DB.','docker compose exec -T driver-db psql -U postgres -d driver_db -c "SELECT driver_id,status,reserved_for_trip,version FROM drivers;"'],
  ['Dừng service giữa luồng','Khi chuyến IN_PROGRESS, dừng Payment, rồi hoàn thành chuyến.','Trip COMPLETED trong khi Payment container stopped.','docker compose stop payment-service'],
  ['Quan sát queue hoặc compensation','Khi Payment dừng, đọc lag bằng lệnh Kafka bên dưới. Grafana theo dõi health và lag sau restart; metric từ service đang dừng không cập nhật. Hoặc hủy chuyến ASSIGNED.','Broker báo lag tăng; hoặc chuyến CANCELLED và Driver AVAILABLE. Saga log cần kiểm tra trong DB.','docker compose exec -T kafka /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:9092 --describe --group payment-service-group'],
  ['Restart và tiếp tục xử lý','Start lại Payment và giữ nguyên Trip ID đang theo dõi.','Cùng chuyến chuyển PAID, payment SUCCESS và lag về 0; đối chiếu số payment trong DB.','docker compose start payment-service']
];
function renderScenarios(){
  $('scenario-list').innerHTML=scenarios.map(([name,action,evidence,command],i)=>`<article class="scenario"><span class="scenario-number">${String(i+1).padStart(2,'0')}</span><div><h3>${esc(name)}</h3><p>${esc(action)}</p>${command?`<code>${esc(settings.compose?command.replace(/^docker compose/,settings.compose):'Thiết lập tiền tố Compose cho stack đang dùng trong Điều hành & quan sát.')}</code>`:''}</div><p>${esc(evidence)}</p></article>`).join('');
}

$('prepare').onclick=()=>run('Đang tạo hồ sơ demo',prepare);
$('refresh').onclick=()=>refresh();
for(const button of document.querySelectorAll('[data-passenger]')) button.onclick=()=>{selected=button.dataset.passenger;persist();render();};
$('booking-form').onsubmit=event=>{event.preventDefault();run('Đang đặt chuyến',async()=>{await book(selected,route());notice('Đã tạo chuyến. Đang chờ Matching và Driver xác nhận.','success');});};
$('quote').onclick=()=>run('Đang lấy giá ước tính',async()=>{const quote=await api('/api/pricing/quote',{method:'POST',body:route()},selected);$('quote-amount').innerHTML=`${money(quote.totalFare)} <span>VND</span>`;notice('Đã nhận báo giá từ Pricing Service.','success');});
$('login-form').onsubmit=event=>{event.preventDefault();run('Đang đăng nhập',async()=>{await login($('login-slot').value,$('login-email').value,$('login-password').value);$('login-password').value='';notice('Đăng nhập thành công. Các chuyến của cùng tài khoản được giữ lại.','success');});};
$('recover-form').onsubmit=event=>{event.preventDefault();run('Đang khôi phục chuyến',async()=>{
  const slot=selected;
  const trip=await api(`/api/trips/${$('recover-id').value.trim()}`,{},slot);
  if(trip.passengerId!==requireSession(slot).userId)throw new Error('Chuyến không thuộc hành khách đang chọn.');
  if(pendingBookings[slot]&&trip.correlationId!==pendingBookings[slot].correlationId)throw new Error('Correlation ID không khớp yêu cầu đang chờ đối chiếu.');
  if(active(slot)&&trips[slot].tripId!==trip.tripId)throw new Error('Không thay chuyến đang hoạt động bằng chuyến khác.');
  delete pendingBookings[slot];delete payments[slot];delete notifications[slot];updateTrip(slot,trip);
  notice('Đã khôi phục theo dõi chuyến.','success');
});};
$('cancel-trip').onclick=()=>run('Đang hủy chuyến',async()=>{const slot=selected;const trip=await api(`/api/trips/${trips[slot].tripId}/cancel`,{method:'POST',body:{reason:'Hủy từ Ride Lab demo'}},slot);updateTrip(slot,trip);notice('Chuyến đã hủy. Đang đọc lại trạng thái tài xế sau bồi hoàn.','success');});
$('copy-trip').onclick=()=>run('Sao chép Trip ID',()=>copy(trips[selected].tripId));
$('driver-location').onclick=()=>run('Đang cập nhật vị trí',async()=>{const {pickup}=route();await api(`/api/drivers/${requireSession('driver').userId}/location`,{method:'PATCH',body:{latitude:pickup.latitude+.0003,longitude:pickup.longitude+.0003}},'driver');notice('Vị trí tài xế đã cập nhật gần điểm đón.','success');});
for(const status of ['AVAILABLE','OFFLINE']) $(`driver-${status.toLowerCase()}`).onclick=()=>run('Đang cập nhật tài xế',async()=>{await api(`/api/drivers/${requireSession('driver').userId}/status`,{method:'PATCH',body:{status}},'driver');notice(`Đã cập nhật tài xế ${status}.`,'success');});
$('driver-trip-slot').onchange=render;
for(const action of ['accept','start','complete']) $(`driver-${action}`).onclick=()=>run('Đang cập nhật chuyến',async()=>{const slot=$('driver-trip-slot').value;const trip=await api(`/api/trips/${trips[slot].tripId}/${action}`,{method:'POST'},'driver');updateTrip(slot,trip);notice(action==='accept'?'Driver đã nhận chuyến. Trạng thái vẫn ASSIGNED cho đến khi bắt đầu.':`Trip ${trip.status}.`,'success');});
$('race').onclick=()=>run('Đang gửi hai yêu cầu đồng thời',async()=>{
  if(['a','b'].some(active)||Object.keys(pendingBookings).length) throw new Error('Cần giải quyết chuyến A/B hiện tại và yêu cầu chưa rõ kết quả trước khi chạy concurrency.');
  const payload=route(),{pickup}=payload;
  const candidates=await api(`/api/drivers/available?latitude=${pickup.latitude}&longitude=${pickup.longitude}&radiusKm=5&size=50`);
  if(!Array.isArray(candidates)||candidates.length!==1||candidates[0].id!==requireSession('driver').userId)throw new Error('Vùng đón phải có đúng một tài xế AVAILABLE và đó là tài xế demo. Hãy chuẩn bị demo mới hoặc kiểm tra vị trí.');
  race=null;const result=await bookPair(['a','b'],payload,book);
  const errors=result.filter(r=>r.status==='rejected');
  if(errors.length) {persist();throw new Error(`Đã gửi cả hai yêu cầu; ${2-errors.length} thành công. ${errors.map(r=>r.reason.message).join(' ')} Làm mới và đối chiếu trước khi thử lại.`);}
  race={a:trips.a.tripId,b:trips.b.tripId};persist();notice('Đã gửi đồng thời hai yêu cầu. Theo dõi tài xế và hai chuyến bên dưới.','success');
});
for(const button of document.querySelectorAll('[data-copy]')) button.onclick=()=>run('Sao chép lệnh',()=>copy(operationCommand(button.dataset.copy)));
$('save-settings').onclick=()=>run('Lưu cấu hình',async()=>{
  const next={compose:$('compose-prefix').value.trim(),grafana:$('grafana-url').value.trim(),prometheus:$('prometheus-url').value.trim()};
  if(!next.compose)throw new Error('Nhập tiền tố lệnh Compose.');
  for(const key of ['grafana','prometheus'])if(!['http:','https:'].includes(new URL(next[key]).protocol))throw new Error('Liên kết giám sát phải là HTTP hoặc HTTPS.');
  settings=next;renderSettings();persist();notice('Đã lưu cấu hình cho phiên trình duyệt.','success');
});
$('export-log').onclick=()=>{
  const data=JSON.stringify({exportedAt:new Date().toISOString(),source:'Gateway API observations (not Kafka or Saga log)',trips,pendingBookings,observations},null,2);
  const url=URL.createObjectURL(new Blob([data],{type:'application/json'})),link=document.createElement('a');
  link.href=url;link.download=`ride-lab-${new Date().toISOString().slice(0,10)}.json`;link.click();setTimeout(()=>URL.revokeObjectURL(url),1000);
};
$('clear-session').onclick=()=>{
  if(!confirm('Chỉ xóa phiên trên trình duyệt. Các tài khoản, chuyến và reservation trong backend vẫn tồn tại. Bạn muốn tiếp tục?'))return;
  run('Đang xóa phiên cục bộ',async()=>{sessions={};trips={};pendingBookings={};driver=null;payments={};notifications={};observations=[];readAt={};race=null;lastSync=null;persist();render();notice('Đã xóa phiên cục bộ. Dữ liệu backend được giữ nguyên.');});
};
$('booking-form').addEventListener('input',()=>{$('quote-amount').innerHTML='— <span>VND</span>';persist();});
for(const [id,value] of Array.isArray(saved?.form)?saved.form:[])if($(id)&&typeof value==='string')$(id).value=value;
window.addEventListener('hashchange',view);
view();renderSettings();render();refresh();setInterval(refresh,2000);
