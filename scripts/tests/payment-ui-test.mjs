import {test} from 'node:test';
import assert from 'node:assert/strict';
import {paymentView, checkoutUrl} from '../../src/api-gateway/src/main/resources/static/demo/payment.mjs';

const completed = {tripId:'trip', status:'COMPLETED', paymentProvider:'PAYOS', finalFare:42000};
test('payOS checkout is available only after completion and only to passenger', () => {
  assert.equal(paymentView({...completed,status:'IN_PROGRESS'},null,'PASSENGER').visible,false);
  assert.equal(paymentView({...completed,paymentProvider:'MOCK'},null,'PASSENGER').visible,false);
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
test('redirect accepts only HTTPS hosted payOS checkout, never callback status or javascript', () => {
  assert.equal(checkoutUrl('https://pay.payos.vn/web/example'),'https://pay.payos.vn/web/example');
  for (const url of ['javascript:alert(1)','http://pay.payos.vn/web/a','https://pay.payos.vn.attacker.test/a','https://pay.payos.vn@attacker.test/a','https://user:secret@pay.payos.vn/web/a','?status=PAID']) {
    assert.throws(()=>checkoutUrl(url),/thanh toán/i);
  }
});
