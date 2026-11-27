import {test} from 'node:test';
import assert from 'node:assert/strict';
import {request, validSession, driverActions, bookPair, raceOutcome, canReplaceSession, bookingUncertain, parseRoute} from '../../src/api-gateway/src/main/resources/static/demo/client.mjs';

test('request sends the selected bearer and surfaces backend failure without retrying a mutation', async () => {
  let calls = 0;
  await assert.rejects(request('/api/trips', {method:'POST', token:'passenger-a', body:{pickup:{latitude:10}}}, async (url, init) => {
    calls++;
    assert.equal(url, '/api/trips');
    assert.equal(init.headers.Authorization, 'Bearer passenger-a');
    assert.match(init.headers['X-Correlation-ID'], /^[0-9a-f-]{36}$/);
    return new Response(JSON.stringify({message:'Driver unavailable',code:'BUSY'}), {status:409});
  }), /Driver unavailable/);
  assert.equal(calls, 1);
});

test('HTML outage response becomes a useful HTTP error', async () => {
  await assert.rejects(request('/api/trips', {}, async () => new Response('<html>down</html>',{status:503})), /503/);
});

test('expired and wrong-role sessions cannot authorize demo operations', () => {
  assert.equal(validSession({accessToken:'x',userId:'a',role:'DRIVER',expiresAt:Date.now()+60000},'PASSENGER'), false);
  assert.equal(validSession({accessToken:'x',userId:'a',role:'PASSENGER',expiresAt:1},'PASSENGER'), false);
  assert.equal(validSession({accessToken:'x',userId:'a',role:'PASSENGER',expiresAt:Date.now()+60000},'PASSENGER'), true);
});

test('driver actions require ownership and server lifecycle state', () => {
  assert.deepEqual(driverActions({status:'ASSIGNING',driverId:'d'},'d'), []);
  assert.deepEqual(driverActions({status:'ASSIGNED',driverId:'other'},'d'), []);
  assert.deepEqual(driverActions({status:'ASSIGNED',driverId:'d'},'d'), ['accept']);
  assert.deepEqual(driverActions({status:'ASSIGNED',driverId:'d',acceptedAt:'2026-11-27T01:00:00Z'},'d'), ['start']);
  assert.deepEqual(driverActions({status:'IN_PROGRESS',driverId:'d'},'d'), ['complete']);
  assert.deepEqual(driverActions({status:'COMPLETED',driverId:'d'},'d'), []);
});

test('pair dispatch starts both requests before either settles and preserves partial success', async () => {
  const started = [];
  const resolvers = [];
  const pending = bookPair(['a','b'], {pickup:{latitude:10}}, (slot, payload) => {
    started.push(slot);
    assert.equal(payload.pickup.latitude,10);
    return new Promise((resolve,reject) => resolvers.push({resolve,reject}));
  });
  assert.deepEqual(started,['a','b']);
  resolvers[0].resolve({tripId:'one'});
  resolvers[1].reject(new Error('network down'));
  const result = await pending;
  assert.equal(result[0].value.tripId,'one');
  assert.equal(result[1].status,'rejected');
});

test('race conclusion waits for fresh evidence and rejects double ownership', () => {
  assert.equal(raceOutcome([{status:'ASSIGNED',driverId:'d'},{status:'ASSIGNED',driverId:'d'}],{id:'d',status:'BUSY'}),'conflict');
  assert.equal(raceOutcome([{status:'ASSIGNED',driverId:'d'},{status:'SEARCHING'}],{id:'d',status:'BUSY'}),'exclusive');
  assert.equal(raceOutcome([{status:'ASSIGNING'},{status:'SEARCHING'}],{id:'d',status:'AVAILABLE'}),'waiting');
  assert.equal(raceOutcome([{status:'ASSIGNED',driverId:'d'},{status:'SEARCHING'}],null),'waiting');
});

test('same-account reauthentication preserves access to an active trip', () => {
  assert.equal(canReplaceSession({userId:'old'},{userId:'old'},true),true);
  assert.equal(canReplaceSession({userId:'old'},{userId:'new'},true),false);
  assert.equal(canReplaceSession({userId:'old'},{userId:'new'},false),true);
});

test('transport failures preserve preallocated correlation ID for booking reconciliation', async () => {
  const id='11111111-1111-4111-8111-111111111111';
  await assert.rejects(request('/api/trips',{method:'POST',correlationId:id},async()=>{throw new TypeError('connection lost');}),error=>{
    assert.equal(error.correlationId,id);
    assert.equal(bookingUncertain(error),true);
    return true;
  });
  assert.equal(bookingUncertain({status:503}),true);
  assert.equal(bookingUncertain({status:408}),true);
  assert.equal(bookingUncertain({status:409}),false);
  assert.equal(bookingUncertain({status:400}),false);
  assert.equal(bookingUncertain({status:503,code:'DOWNSTREAM_UNAVAILABLE'}),false);
});

test('route validation rejects blank and invalid coordinates even when form controls are disabled', () => {
  const route={pickup:{address:'A',latitude:'0',longitude:'106'},destination:{address:'B',latitude:'1',longitude:'107'}};
  assert.deepEqual(parseRoute(route),{pickup:{address:'A',latitude:0,longitude:106},destination:{address:'B',latitude:1,longitude:107}});
  assert.throws(()=>parseRoute({...route,pickup:{...route.pickup,latitude:''}}));
  assert.throws(()=>parseRoute({...route,pickup:{...route.pickup,latitude:'91'}}));
  assert.throws(()=>parseRoute({...route,destination:{...route.destination,longitude:'Infinity'}}));
  assert.throws(()=>parseRoute({...route,destination:{...route.destination,address:'  '}}));
});
