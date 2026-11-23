import {test} from 'node:test';
import assert from 'node:assert/strict';
import {stageIndex, statusText, pageForRole, sessionFrom, unseen, assignedTripId, canCancel, money} from '../../src/api-gateway/src/main/resources/static/demo/ride-core.mjs';

test('each role lands on its own page and an unknown role on none', () => {
  assert.equal(pageForRole('PASSENGER'), 'rider.html');
  assert.equal(pageForRole('DRIVER'), 'driver.html');
  assert.equal(pageForRole('ADMIN'), 'index.html');
  assert.equal(pageForRole('SOMETHING'), null);
});

test('timeline follows the saga and ended trips sit outside it', () => {
  assert.equal(stageIndex('SEARCHING'), 0);
  assert.equal(stageIndex('ASSIGNING') < stageIndex('ASSIGNED'), true);
  assert.equal(stageIndex('PAID'), 5);
  assert.equal(stageIndex('CANCELLED'), -1);
  assert.equal(statusText('PAYMENT_FAILED'), 'Thanh toán thất bại');
  assert.equal(statusText(undefined), 'Chưa có chuyến');
});

test('session expiry comes from expiresIn seconds', () => {
  assert.equal(sessionFrom({role: 'PASSENGER', expiresIn: 60}, 'a@b', 1000).expiresAt, 61000);
});

test('only new notifications pop up, oldest first', () => {
  const list = [{id: '3', timestamp: '2026-11-23T10:00:03Z'}, {id: '2', timestamp: '2026-11-23T10:00:02Z'}, {id: '1', timestamp: '2026-11-23T10:00:01Z'}];
  assert.deepEqual(unseen(list, new Set(['1'])).map(n => n.id), ['2', '3']);
  assert.deepEqual(unseen(list, new Set(['1', '2', '3'])), []);
});

test('driver trip is the newest confirmed assignment, never a tentative one', () => {
  const list = [
    {eventType: 'DriverAssignmentConfirmed', tripId: 'old', timestamp: '2026-11-23T09:00:00Z'},
    {eventType: 'PaymentSucceeded', tripId: 'old', timestamp: '2026-11-23T09:30:00Z'},
    {eventType: 'DriverAssignmentConfirmed', tripId: 'new', timestamp: '2026-11-23T10:00:00Z'},
    {eventType: 'DriverAssigned', tripId: 'tentative', timestamp: '2026-11-23T11:00:00Z'},
  ];
  assert.equal(assignedTripId(list), 'new');
  assert.equal(assignedTripId([]), null);
});

test('a trip can be cancelled only before it starts', () => {
  assert.equal(canCancel({status: 'ASSIGNED'}), true);
  assert.equal(canCancel({status: 'IN_PROGRESS'}), false);
  assert.equal(canCancel(null), false);
});

test('fares are whole dong', () => {
  assert.equal(money(47500.4).replace(/\s/g, ' '), '47.500 đ');
  assert.equal(money(null), '—');
});

test('notifications read in Vietnamese and unknown ones keep the server text', async () => {
  const {noteText} = await import('../../src/api-gateway/src/main/resources/static/demo/ride-core.mjs');
  assert.deepEqual(noteText({recipientType: 'DRIVER', eventType: 'DriverAssignmentConfirmed', title: 'New Trip Assigned'})[0], 'Có chuyến mới');
  assert.deepEqual(noteText({recipientType: 'PASSENGER', eventType: 'ManualNotification', title: 'Hi', message: 'There'}), ['Hi', 'There']);
});
