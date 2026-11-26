import {test} from 'node:test';
import assert from 'node:assert/strict';
import {paymentView, checkoutUrl} from '../../src/api-gateway/src/main/resources/static/demo/payment.mjs';

const completed = {tripId:'trip', status:'COMPLETED', paymentProvider:'PAYOS', finalFare:42000};
test('payOS checkout is available only after completion and only to passenger', () => {
  assert.equal(paymentView({...completed,status:'IN_PROGRESS'},null,'PASSENGER').visible,false);
  assert.equal(paymentView(completed,null,'PASSENGER').canPay,true);
  assert.equal(paymentView(completed,null,'DRIVER').canPay,false);
  assert.match(paymentView(completed,null,'DRIVER').message,/khách/i);
});
test('backend success or paid trip prevents another checkout; a cancelled link remains retryable', () => {
  assert.equal(paymentView(completed,{status:'SUCCESS'},'PASSENGER').canPay,false);
  assert.equal(paymentView({...completed,status:'PAID'},{status:'PENDING'},'PASSENGER').paid,true);
  assert.equal(paymentView(completed,{status:'CANCELLED'},'PASSENGER').canPay,true);
  assert.equal(paymentView(completed,{status:'EXPIRED'},'PASSENGER').canPay,true);
  assert.equal(paymentView(completed,{status:'REVIEW_REQUIRED'},'PASSENGER').canPay,false);
});
test('wallet (MOCK) trip: wait for the amount due, then only the passenger transfers; a decline is final', () => {
  const wallet = {...completed, paymentProvider:'MOCK'};
  assert.equal(paymentView({...wallet,status:'IN_PROGRESS'},null,'PASSENGER').visible,false);
  assert.equal(paymentView(wallet,null,'PASSENGER').canPay,false, 'Payment has not recorded the amount due yet');
  const due = {status:'PENDING', amount:33000};
  assert.equal(paymentView(wallet,due,'PASSENGER').canPay,true);
  assert.match(paymentView(wallet,due,'PASSENGER').label,/33\.000/);
  assert.equal(paymentView(wallet,due,'DRIVER').canPay,false);
  assert.equal(paymentView(wallet,{status:'FAILED'},'PASSENGER').canPay,false);
  assert.equal(paymentView({...wallet,status:'PAYMENT_FAILED'},null,'PASSENGER').title,'Thanh toán thất bại');
  assert.equal(paymentView(wallet,{status:'SUCCESS'},'PASSENGER').paid,true);
});
test('redirect accepts only HTTPS hosted payOS checkout, never callback status or javascript', () => {
  assert.equal(checkoutUrl('https://pay.payos.vn/web/example'),'https://pay.payos.vn/web/example');
  for (const url of ['javascript:alert(1)','http://pay.payos.vn/web/a','https://pay.payos.vn.attacker.test/a','https://pay.payos.vn@attacker.test/a','https://user:secret@pay.payos.vn/web/a','?status=PAID']) {
    assert.throws(()=>checkoutUrl(url),/thanh toán/i);
  }
});
