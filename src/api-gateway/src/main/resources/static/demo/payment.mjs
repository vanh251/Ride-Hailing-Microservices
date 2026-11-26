import {$, api, failure} from './ride.mjs';
import {money} from './ride-core.mjs';

// MOCK trips are paid by the passenger's simulated transfer from the Đi wallet (POST /api/payments/{id}/pay);
// PAYOS trips through the hosted payOS checkout.
export function paymentView(trip, payment, role) {
  if (!['COMPLETED', 'PAID', 'PAYMENT_FAILED'].includes(trip?.status)) return {visible: false};
  const paid = trip.status === 'PAID' || payment?.status === 'SUCCESS';
  if (paid) return {visible: true, paid: true, canPay: false, title: 'Đã thanh toán', message: 'Thanh toán đã được hệ thống xác nhận.'};
  if (payment?.status === 'REVIEW_REQUIRED') return {visible: true, paid: false, canPay: false, title: 'Đang đối soát thanh toán', message: 'Giao dịch cần được kiểm tra. Vui lòng liên hệ hỗ trợ, chưa thanh toán thêm.'};
  if (trip.status === 'PAYMENT_FAILED' || payment?.status === 'FAILED') return {visible: true, paid: false, canPay: false, title: 'Thanh toán thất bại',
    message: 'Ví Đi từ chối giao dịch, không có tiền nào bị trừ. Chuyến kết thúc ở trạng thái thanh toán thất bại.'};
  if (trip.paymentProvider !== 'PAYOS') {
    if (!payment) return {visible: true, paid: false, canPay: false, title: 'Chờ thanh toán', message: 'Đang chờ hệ thống thanh toán ghi nhận khoản cần trả…'};
    return {visible: true, paid: false, canPay: role === 'PASSENGER', label: `Chuyển khoản ${money(payment.amount)}`, title: 'Chờ thanh toán',
      message: role === 'DRIVER' ? 'Chờ khách chuyển khoản từ ví Đi. Bạn không cần thu tiền mặt.' : ''};
  }
  const expired = ['EXPIRED', 'CANCELLED'].includes(payment?.status);
  return {visible: true, paid: false, canPay: role === 'PASSENGER', label: 'Thanh toán payOS', title: 'Chờ thanh toán',
    message: role === 'DRIVER' ? 'Chờ khách thanh toán qua payOS. Bạn không cần thu thêm tiền mặt.'
      : expired ? 'Phiên thanh toán trước đã kết thúc. Bấm thanh toán để thử lại.'
      : 'Mở payOS để quét QR hoặc thanh toán bằng ứng dụng ngân hàng. Sau khi chuyển khoản, trang tự cập nhật trong vài giây.'};
}

export function checkoutUrl(value) {
  let url;
  try { url = new URL(value); } catch { throw new Error('Đường dẫn thanh toán không hợp lệ.'); }
  if (url.protocol !== 'https:' || !['pay.payos.vn', 'payos.vn'].includes(url.hostname) || url.username || url.password)
    throw new Error('Đường dẫn thanh toán không hợp lệ.');
  return url.href;
}

// One panel on each role page. Existing trip polling refreshes payment state (the backend asks payOS on these reads);
// URL return/cancel parameters are never proof that money was received.
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
      const mock = trip.paymentProvider !== 'PAYOS';
      const next = await api(mock ? `/api/payments/${id}` : `/api/payments/${id}/checkout`, {}, session);
      if (asked !== generation) return;
      payment = next; error = '';
    } catch (problem) {
      // 404 on a wallet trip: Payment has not recorded the amount due yet (it may be down); keep waiting
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
      if (trip.paymentProvider !== 'PAYOS') {
        payment = await api(`/api/payments/${id}/pay`, {method: 'POST'}, session);
        return;
      }
      const next = await api(`/api/payments/${id}/checkout`, {method: 'POST'}, session);
      if (asked !== generation) return;
      payment = next;
      if (next.status !== 'SUCCESS') {
        if (!next.checkoutUrl) throw new Error('Chưa có đường dẫn thanh toán.');
        location.assign(checkoutUrl(next.checkoutUrl));
      }
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
