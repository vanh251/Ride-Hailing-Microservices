// Local runtime verification for an UNCONFIGURED payOS channel. Creates test accounts
// and one completed, unpaid test trip. Never simulates receiving real money.
// Run only after migration/deployment: node scripts/tests/payos-runtime-smoke.mjs
import assert from 'node:assert/strict';
import {randomUUID, randomInt} from 'node:crypto';

const base = process.env.GATEWAY_URL || 'http://localhost:8080';
const paymentBase = process.env.PAYMENT_SERVICE_TEST_URL || 'http://localhost:8086';
const run = randomUUID(), password = `Verify@${randomUUID()}`;
let passenger, driver, stranger, trip;
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));
async function call(path, {method='GET',body,token,origin=base,status=200}={}) {
  const response = await fetch(origin+path, {method,signal:AbortSignal.timeout(15000),headers:{'Content-Type':'application/json','X-Correlation-ID':run,
    ...(token ? {Authorization:`Bearer ${token}`} : {})},...(body===undefined ? {} : {body:JSON.stringify(body)})});
  const text = await response.text();
  let data;try {data=JSON.parse(text);} catch {data=null;}
  assert.equal(response.status,status,`${method} ${path}: expected HTTP ${status}, got ${response.status} (${data?.code||''})`);
  return data;
}
async function account(role) {
  const email=`payos.${role.toLowerCase()}.${randomUUID()}@ds03.test`;
  await call(`/api/auth/register/${role.toLowerCase()}`,{method:'POST',status:201,body:{fullName:`PAYOS Verify ${role}`,email,password,
    ...(role==='DRIVER' ? {licenseNumber:String(randomInt(100000000000,999999999999)),vehicle:{licensePlate:`51F-${randomInt(10000,99999)}`,make:'Toyota',model:'Vios',color:'White',year:2022,vehicleType:'SEDAN'}} : {})}});
  return call('/api/auth/login',{method:'POST',body:{email,password}});
}
try {
  passenger=await account('PASSENGER');driver=await account('DRIVER');stranger=await account('PASSENGER');
  const pickup={latitude:11.178231,longitude:108.482419,address:'payOS verification pickup'};
  const destination={latitude:11.188231,longitude:108.492419,address:'payOS verification destination'};
  await call(`/api/drivers/${driver.userId}/location`,{method:'PATCH',token:driver.accessToken,body:{latitude:pickup.latitude,longitude:pickup.longitude}});
  await call(`/api/drivers/${driver.userId}/status`,{method:'PATCH',token:driver.accessToken,body:{status:'AVAILABLE'}});
  trip=await call('/api/trips',{method:'POST',status:201,token:passenger.accessToken,body:{pickup,destination,paymentProvider:'PAYOS'}});
  assert.equal(trip.paymentProvider,'PAYOS');
  for (const deadline=Date.now()+30000;Date.now()<deadline && trip.status!=='ASSIGNED';) {
    await sleep(500);trip=await call(`/api/trips/${trip.tripId}`,{token:passenger.accessToken});
  }
  assert.equal(trip.status,'ASSIGNED');assert.equal(trip.driverId,driver.userId);
  await call(`/api/payments/${trip.tripId}/checkout`,{method:'POST',token:passenger.accessToken,status:409});
  for (const action of ['accept','start','complete']) trip=await call(`/api/trips/${trip.tripId}/${action}`,{method:'POST',token:driver.accessToken});
  assert.equal(trip.status,'COMPLETED');
  // The legacy simulator cannot bypass the real-money flow via the directly published service port.
  await call(`/api/payments/${trip.tripId}/charge`,{origin:paymentBase,method:'POST',status:403,body:{passengerId:passenger.userId,amount:trip.finalFare,currency:'VND',idempotencyKey:`attack-${run}`}});
  await call(`/api/payments/${trip.tripId}/charge`,{method:'POST',token:passenger.accessToken,status:404,body:{passengerId:passenger.userId,amount:trip.finalFare,currency:'VND',idempotencyKey:`attack-${run}`}});
  const payment=await call(`/api/payments/${trip.tripId}/checkout`,{token:passenger.accessToken});
  assert.equal(payment.provider,'PAYOS');assert.equal(payment.status,'PENDING');assert.equal(Number(payment.amount),Number(trip.finalFare));assert.equal(payment.currency,'VND');
  const driverPayment=await call(`/api/payments/${trip.tripId}/checkout`,{token:driver.accessToken});
  assert.equal(driverPayment.status,'PENDING');
  await call(`/api/payments/${trip.tripId}/checkout`,{token:stranger.accessToken,status:403});
  await call(`/api/payments/${trip.tripId}/checkout`,{origin:paymentBase,status:401});
  await call(`/api/payments/${trip.tripId}/checkout`,{origin:paymentBase,token:stranger.accessToken,status:403});
  await call(`/api/payments/${trip.tripId}/checkout`,{method:'POST',token:driver.accessToken,status:403});
  await call(`/api/payments/${trip.tripId}/checkout`,{origin:paymentBase,method:'POST',token:driver.accessToken,status:403});
  await call(`/api/payments/${trip.tripId}/checkout`,{method:'POST',token:passenger.accessToken,status:503});
  await sleep(3500);
  trip=await call(`/api/trips/${trip.tripId}`,{token:passenger.accessToken});
  assert.equal(trip.status,'COMPLETED','PAYOS must not auto-charge through simulator');
  console.log(JSON.stringify({result:'PASS',tripId:trip.tripId,paymentId:payment.paymentId,driverId:driver.userId,amount:trip.finalFare,
    checked:['assignment','no prepayment','canonical final fare','no auto mock charge','owner/driver access','legacy direct charge blocked','missing keys unavailable without success'],correlationId:run}));
} finally {
  if (driver) {
    for (const deadline=Date.now()+12000;Date.now()<deadline;) {
      const profile=await call(`/api/drivers/${driver.userId}`,{token:driver.accessToken});
      if (['AVAILABLE','OFFLINE'].includes(profile.status)) {
        await call(`/api/drivers/${driver.userId}/status`,{method:'PATCH',token:driver.accessToken,body:{status:'OFFLINE'}});break;
      }
      if(trip && ['SEARCHING','ASSIGNING','ASSIGNED'].includes(trip.status)) {
        trip=await call(`/api/trips/${trip.tripId}/cancel`,{method:'POST',token:passenger.accessToken,body:{reason:'payOS test cleanup'}});
      }
      await sleep(500);
    }
  }
}
