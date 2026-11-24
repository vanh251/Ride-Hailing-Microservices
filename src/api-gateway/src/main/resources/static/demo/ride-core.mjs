// Pure logic of the passenger and driver pages (login.html, rider.html, driver.html); tested by
// scripts/tests/ride-core-test.mjs. DOM, storage and the map live in ride.mjs.

// The trip lifecycle as the passenger sees it (docs/events.md §9)
export const STAGES = [
  ['SEARCHING', 'Đang tìm tài xế'],
  ['ASSIGNING', 'Chờ tài xế xác nhận'],
  ['ASSIGNED', 'Tài xế đang đến'],
  ['IN_PROGRESS', 'Đang di chuyển'],
  ['COMPLETED', 'Đang thanh toán'],
  ['PAID', 'Đã thanh toán'],
];
const ENDED = {CANCELLED: 'Chuyến đã hủy', PAYMENT_FAILED: 'Thanh toán thất bại'};
export const TERMINAL = new Set(['PAID', 'CANCELLED', 'PAYMENT_FAILED']);

export function statusText(status) {
  return ENDED[status] || STAGES.find(([key]) => key === status)?.[1] || 'Chưa có chuyến';
}

// Position of the status on the timeline; -1 for a trip that ended without being paid
export function stageIndex(status) {
  return STAGES.findIndex(([key]) => key === status);
}

// Where a signed-in account lands; null for a role without a page
export function pageForRole(role) {
  return {PASSENGER: 'rider.html', DRIVER: 'driver.html', ADMIN: 'index.html'}[role] || null;
}

export function sessionFrom(login, email, now = Date.now()) {
  return {...login, email, expiresAt: now + login.expiresIn * 1000};
}

// Notifications not shown yet, oldest first so popups appear in the order things happened
export function unseen(notifications, seenIds) {
  return [...notifications].filter(n => !seenIds.has(n.id))
    .sort((a, b) => String(a.timestamp).localeCompare(String(b.timestamp)));
}

// The driver learns its trip from Notification: the newest confirmed assignment (docs/events.md §4.10)
export function assignedTripId(notifications) {
  return [...notifications].filter(n => n.eventType === 'DriverAssignmentConfirmed' && n.tripId)
    .sort((a, b) => String(b.timestamp).localeCompare(String(a.timestamp)))[0]?.tripId || null;
}

// Notification Service writes English titles; the apps show them in Vietnamese, by event and recipient
const NOTE_TEXT = {
  PASSENGER: {
    RideRequested: ['Đã gửi yêu cầu đặt xe', 'Hệ thống đang tìm tài xế gần bạn.'],
    DriverAssignmentConfirmed: ['Đã có tài xế', 'Tài xế đã nhận chuyến và đang đến điểm đón.'],
    TripStarted: ['Chuyến đi bắt đầu', 'Chúc bạn có một chuyến đi vui vẻ.'],
    TripCompleted: ['Đã đến nơi', 'Chuyến đi kết thúc. Hệ thống đang thanh toán.'],
    PaymentSucceeded: ['Thanh toán thành công', 'Cảm ơn bạn đã đi cùng Đi.'],
    PaymentFailed: ['Thanh toán thất bại', 'Vui lòng liên hệ hỗ trợ để hoàn tất thanh toán.'],
    TripCancelled: ['Chuyến đã hủy', 'Chuyến đi của bạn đã được hủy.'],
  },
  DRIVER: {
    DriverAssignmentConfirmed: ['Có chuyến mới', 'Hãy đến điểm đón hành khách.'],
    PaymentSucceeded: ['Đã nhận thanh toán', 'Hành khách đã thanh toán chuyến đi.'],
    TripCancelled: ['Chuyến đã bị hủy', 'Chuyến không còn hiệu lực. Bạn có thể nhận chuyến mới.'],
  },
};

export function noteText(note) {
  return NOTE_TEXT[note.recipientType]?.[note.eventType] || [note.title, note.message];
}

export function canCancel(trip) {
  return ['SEARCHING', 'ASSIGNING', 'ASSIGNED'].includes(trip?.status);
}

export function money(amount) {
  return amount == null ? '—' : `${new Intl.NumberFormat('vi-VN').format(Math.round(amount))} đ`;
}
