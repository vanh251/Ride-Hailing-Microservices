import {$, api, failure} from './ride.mjs';
import {money} from './ride-core.mjs';

// The passenger pays a completed trip by a simulated transfer from the Đi wallet (POST /api/payments/{id}/pay)
export function paymentView(trip, payment, role) {
  if (!['COMPLETED', 'PAID', 'PAYMENT_FAILED'].includes(trip?.status)) return {visible: false};
  const paid = trip.status === 'PAID' || payment?.status === 'SUCCESS';
  if (paid) return {visible: true, paid: true, canPay: false, title: 'Đã thanh toán', message: 'Thanh toán đã được hệ thống xác nhận.'};
  if (trip.status === 'PAYMENT_FAILED' || payment?.status === 'FAILED') return {visible: true, paid: false, canPay: false, title: 'Thanh toán thất bại',
    message: 'Ví Đi từ chối giao dịch, không có tiền nào bị trừ. Chuyến kết thúc ở trạng thái thanh toán thất bại.'};
  if (!payment) return {visible: true, paid: false, canPay: false, title: 'Chờ thanh toán', message: 'Đang chờ hệ thống thanh toán ghi nhận khoản cần trả…'};
  return {visible: true, paid: false, canPay: role === 'PASSENGER', label: `Chuyển khoản ${money(payment.amount)}`, title: 'Chờ thanh toán',
    message: role === 'DRIVER' ? 'Chờ khách chuyển khoản từ ví Đi. Bạn không cần thu tiền mặt.' : ''};
}

// One panel on each role page; the existing trip polling refreshes the payment state.
export function paymentPanel(session) {
  let trip = null, payment = null, error = '', generation = 0, loading = false, creating = false, lastCheck = 0;
  const passenger = session.role === 'PASSENGER';
  function render() {
    const view = paymentView(trip, payment, session.role);
    $('payment-panel').hidden = !view.visible;
    if (!view.visible) return;
    $('payment-title').textContent = view.title;
    $('payment-message').textContent = error || view.message;
    $('payment-message').hidden = !(error || view.message);
    $('payment-panel').classList.toggle('paid', Boolean(view.paid));
    $('pay-checkout').hidden = !view.canPay;
    $('pay-checkout').disabled = creating;
    $('pay-checkout').textContent = creating ? 'Đang xử lý…' : view.label || 'Thanh toán';
  }
  function showError(problem) {
    error = problem.status === 503 ? 'Thanh toán trực tuyến tạm thời chưa sẵn sàng. Vui lòng thử lại sau hoặc liên hệ hỗ trợ.'
      : 'Chưa kiểm tra được thanh toán, trang sẽ tự thử lại. Không thanh toán thêm nếu đã chuyển tiền.';
  }
  async function refresh() {
    if (loading || creating || !paymentView(trip, payment, session.role).visible || trip.status === 'PAID') return;
    const asked = generation, id = trip.tripId;
    loading = true; lastCheck = Date.now(); render();
    try {
      const next = await api(`/api/payments/${id}`, {}, session);
      if (asked !== generation) return;
      payment = next; error = '';
    } catch (problem) {
      // 404: Payment has not recorded the amount due yet (it may be down); keep waiting
      if (asked === generation) { if (problem.status === 404) { payment = null; error = ''; } else showError(problem); }
    }
    finally { if (asked === generation) { loading = false; render(); } }
  }
  $('pay-checkout').onclick = async () => {
    if (!passenger || creating || !paymentView(trip, payment, session.role).canPay) return;
    // Invalidate any GET begun before this mutation so an old PENDING response
    // cannot replace the newly confirmed result.
    const asked = ++generation, id = trip.tripId;
    loading = false;
    creating = true; error = ''; render();
    try {
      const next = await api(`/api/payments/${id}/pay`, {method: 'POST'}, session);
      if (asked === generation) payment = next;
    } catch (problem) {
      if (asked === generation) { showError(problem); failure(new Error(error)); }
    } finally { if (asked === generation) { creating = false; render(); } }
  };
  return {
    update(next) {
      if (trip?.tripId !== next?.tripId) {
        generation++; payment = null; error = ''; loading = false; creating = false; lastCheck = 0;
      }
      trip = next; render();
      if (Date.now() - lastCheck >= 2500) refresh();
    },
    refresh,
  };
}
