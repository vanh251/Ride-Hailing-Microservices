// Runs the actual app event handlers against a real Gateway, with a minimal DOM fixture.
// This verifies HTTP behavior and rendered state, not browser layout/accessibility.
// Use a dedicated demo stack: this creates test users/trips and optionally stops Payment.
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {setTimeout as delay} from 'node:timers/promises';
import {spawnSync} from 'node:child_process';

const gateway=process.env.DEMO_GATEWAY_URL;
if(!gateway) throw new Error('Set DEMO_GATEWAY_URL to a dedicated local demo Gateway before running.');
if(!['localhost','127.0.0.1'].includes(new URL(gateway).hostname))throw new Error('This demo test only targets localhost.');
const html=await readFile(new URL('../../src/api-gateway/src/main/resources/static/demo/index.html',import.meta.url),'utf8');
class Element {
  constructor(id='',value=''){this.id=id;this.value=value;this.disabled=false;this.hidden=false;this.dataset={};this.textContent='';this.innerHTML='';this.outerHTML='';this.className='';this.classList={toggle(){}};}
  setAttribute(){} removeAttribute(){} addEventListener(){} reportValidity(){return true;}
}
const elements=new Map();
for(const match of html.matchAll(/<[^>]*\bid="([^"]+)"[^>]*>/g))elements.set(match[1],new Element(match[1],match[0].match(/\bvalue="([^"]*)"/)?.[1] || ''));
for(const [id,value] of [['login-slot','a'],['driver-trip-slot','a']])elements.get(id).value=value;
const passengers=['a','b'].map(slot=>Object.assign(new Element(),{dataset:{passenger:slot}}));
const views=['passenger','driver','console'].map(view=>Object.assign(new Element(),{dataset:{view}}));
const copies=['stop','start'].map(copy=>Object.assign(new Element(),{dataset:{copy}}));
globalThis.document={getElementById:id=>{assert(elements.has(id),`Unknown element ${id}`);return elements.get(id);},
  querySelector:selector=>passengers.find(e=>selector.includes(`"${e.dataset.passenger}"`)),
  querySelectorAll:selector=>selector==='[data-passenger]'?passengers:selector==='[data-view]'?views:selector==='[data-copy]'?copies:[],
  createElement:()=>Object.assign(new Element(),{click(){}})};
globalThis.window={addEventListener(){}};
globalThis.location=new URL(gateway);
globalThis.sessionStorage={getItem:key=>sessionStorage[key] || null,setItem:(key,value)=>{sessionStorage[key]=value;}};
globalThis.confirm=()=>true;
const nativeFetch=globalThis.fetch;
let ambiguousNext=false,unknownTrip=null,failDriverReads=false;
globalThis.fetch=async(path,init)=>{
  if(failDriverReads && path.startsWith('/api/drivers/') && init.method==='GET')return new Response('Unavailable',{status:503});
  const response=await nativeFetch(new URL(path,gateway),init);
  if(ambiguousNext && path==='/api/trips' && init.method==='POST' && response.ok){
    ambiguousNext=false;unknownTrip=await response.clone().json();throw new TypeError('Simulated lost response after successful commit');
  }
  return response;
};
const intervals=[];
const nativeSetInterval=globalThis.setInterval;
globalThis.setInterval=(fn,ms)=>{const timer=nativeSetInterval(fn,ms);intervals.push(timer);return timer;};
const state=()=>JSON.parse(sessionStorage.getItem('ride-lab-demo-v1'));
const element=id=>elements.get(id);
async function until(predicate,message,timeout=45000){
  const deadline=Date.now()+timeout;
  while(Date.now()<deadline){if(await predicate())return;await delay(200);}
  throw new Error(`${message}; notice=${element('notice').textContent}`);
}
async function idle(){await until(()=>!element('prepare').disabled,'App action did not settle',60000);}
async function click(id){assert.equal(element(id).disabled,false,`${id} must be enabled`);await element(id).onclick();await idle();}
async function submit(id){element(id).onsubmit({preventDefault(){}});await idle();}
async function waitTrip(slot,status){await until(async()=>{await element('refresh').onclick();return state()?.trips?.[slot]?.status===status;},`Trip ${slot} did not reach ${status}`,90000);}
function docker(...args){const result=spawnSync('docker',args,{encoding:'utf8',timeout:90000});assert.equal(result.status,0,result.stderr);return result.stdout.trim();}
function compose(action){return docker('compose','-p',process.env.DEMO_COMPOSE_PROJECT,'-f',process.env.DEMO_COMPOSE_FILE,action,'payment-service');}
let paymentStopped=false;
try{
  await import('../../src/api-gateway/src/main/resources/static/demo/app.js');
  await click('prepare');
  assert.deepEqual(Object.keys(state().sessions).sort(),['a','b','driver']);
  assert.equal(element('notice').className,'notice success');
  await until(()=>!element('driver-location').disabled,'Driver profile not ready');
  await click('driver-location');await click('driver-available');
  await until(()=>!element('book').disabled,'Booking not ready');
  const latitude=element('pickup-lat').value;element('pickup-lat').value='';
  await click('quote');assert.equal(element('notice').className,'notice error');
  element('pickup-lat').value=latitude;
  console.log('PASS route validation rejects blank coordinates');
  await click('quote');assert.match(element('quote-amount').innerHTML,/VND/);
  await submit('booking-form');await waitTrip('a','ASSIGNED');
  console.log('PASS prepare, location, availability, quote and assignment',state().trips.a.tripId);
  // Same-account reauthentication while a trip is active must retain its ID.
  const original=state().trips.a.tripId;
  element('login-email').value=state().sessions.a.email;element('login-password').value='Demo@123456';
  await submit('login-form');assert.equal(state().trips.a.tripId,original);
  console.log('PASS same-account reauthentication retained active trip');
  await click('driver-accept');await click('driver-start');
  if(process.env.DEMO_COMPOSE_FILE){
    assert(process.env.DEMO_COMPOSE_PROJECT,'Set DEMO_COMPOSE_PROJECT to the dedicated test project');
    compose('stop');paymentStopped=true;
  }
  await click('driver-complete');
  if(paymentStopped){
    assert.equal(state().trips.a.status,'COMPLETED');
    console.log('PASS app completed real trip while Payment stopped',original);
    compose('start');paymentStopped=false;
  }
  await waitTrip('a','PAID');
  assert.match(element('payment-detail').innerHTML,/SUCCESS/);
  console.log('PASS app observed PAID and Payment SUCCESS',original);
  await until(()=>!element('race').disabled,'Driver or passengers not ready for race');
  await click('race');
  await until(async()=>{await element('refresh').onclick();return element('race-result').className==='info-box success';},'Race did not show exclusive assignment');
  const pair=state().trips;
  assert.equal([pair.a,pair.b].filter(trip=>trip.status==='ASSIGNED').length,1);
  console.log('PASS simultaneous A/B booking and exclusive assignment',pair.a.tripId,pair.b.tripId);
  for(const slot of ['a','b']){
    passengers.find(e=>e.dataset.passenger===slot).onclick();
    if(['SEARCHING','ASSIGNING','ASSIGNED'].includes(state().trips[slot].status))await click('cancel-trip');
  }
  await until(()=>element('driver-summary').textContent==='AVAILABLE','Cancellation did not release driver');
  console.log('PASS cancellation compensation released driver');
  passengers[0].onclick();ambiguousNext=true;
  await submit('booking-form');
  assert(unknownTrip?.tripId,'Real booking did not commit before simulated transport failure');
  assert(state().pendingBookings.a,'Ambiguous booking must remain pending');
  assert.equal(element('book').disabled,true);
  assert.equal(element('pending-booking').hidden,false);
  element('recover-id').value=unknownTrip.tripId;await submit('recover-form');
  assert.equal(state().trips.a.tripId,unknownTrip.tripId);assert.equal(state().pendingBookings.a,undefined);
  await waitTrip('a','ASSIGNED');await click('cancel-trip');
  console.log('PASS lost-response guard and Trip ID reconciliation',unknownTrip.tripId);
  failDriverReads=true;
  await until(()=>element('driver-summary').textContent.includes('dữ liệu cũ'),'Driver status did not become stale',16000);
  assert.equal(element('driver-available').disabled,true);
  failDriverReads=false;await element('refresh').onclick();
  console.log('PASS stale Driver status is marked independently of fresh Trip reads');
  console.log('DEMO WEB APPLICATION E2E PASSED');
}finally{
  if(paymentStopped)compose('start');
  for(const timer of intervals)clearInterval(timer);
}
