// k6 load test of the pricing and payment services (docs/timeline.md W6 Performance).
// One iteration = quote a fare, charge it for a new trip, read the payment back.
// Every charge also publishes PaymentSucceeded through the outbox, which notification-service consumes,
// so the Kafka lag panel of the Grafana dashboard shows queue processing under the same load.
// Run with scripts/load/run.sh.
import http from 'k6/http';
import { check } from 'k6';

const PRICING = __ENV.PRICING_URL || 'http://pricing-service:8085';
const PAYMENT = __ENV.PAYMENT_URL || 'http://payment-service:8086';
const JSON_HEADERS = { 'Content-Type': 'application/json' };

export const options = {
  vus: Number(__ENV.VUS || 10),
  duration: __ENV.DURATION || '30s',
  summaryTrendStats: ['med', 'p(95)', 'max'],
};

const uuid = () => 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => ((Math.random() * 16) | 0).toString(16));

export default function () {
  const quote = http.post(`${PRICING}/api/pricing/quote`, JSON.stringify({
    pickup: { latitude: 21.0285, longitude: 105.8542 },
    destination: { latitude: 21.0045, longitude: 105.8431 },
  }), { headers: JSON_HEADERS });
  check(quote, { 'quote 200': (r) => r.status === 200 });

  const tripId = uuid();
  const amount = quote.status === 200 ? quote.json('totalFare') : 30000;
  const charge = http.post(`${PAYMENT}/api/payments/${tripId}/charge`,
    JSON.stringify({ passengerId: uuid(), amount }),
    { headers: { ...JSON_HEADERS, 'X-Idempotency-Key': `load-${tripId}` } });
  check(charge, { 'charge 201': (r) => r.status === 201 });

  const payment = http.get(`${PAYMENT}/api/payments/${tripId}`);
  check(payment, { 'payment 200': (r) => r.status === 200 });
}
