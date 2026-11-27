import {test} from 'node:test';
import assert from 'node:assert/strict';
import {paymentView} from '../src/api-gateway/src/main/resources/static/demo/payment.mjs';

const completed = {tripId:'trip', status:'COMPLETED', finalFare:33000};
test('wallet payment: wait for the amount due, then only the passenger transfers', () => {
  assert.equal(paymentView({...completed,status:'IN_PROGRESS'},null,'PASSENGER').visible,false);
  assert.equal(paymentView(completed,null,'PASSENGER').canPay,false, 'Payment has not recorded the amount due yet');
  const due = {status:'PENDING', amount:33000};
  assert.equal(paymentView(completed,due,'PASSENGER').canPay,true);
  assert.match(paymentView(completed,due,'PASSENGER').label,/33\.000/);
  assert.equal(paymentView(completed,due,'DRIVER').canPay,false);
  assert.match(paymentView(completed,due,'DRIVER').message,/khách/i);
});
test('a paid or declined trip offers no second transfer', () => {
  assert.equal(paymentView(completed,{status:'SUCCESS'},'PASSENGER').paid,true);
  assert.equal(paymentView({...completed,status:'PAID'},{status:'PENDING'},'PASSENGER').paid,true);
  assert.equal(paymentView(completed,{status:'FAILED'},'PASSENGER').canPay,false);
  assert.equal(paymentView({...completed,status:'PAYMENT_FAILED'},null,'PASSENGER').title,'Thanh toán thất bại');
});
