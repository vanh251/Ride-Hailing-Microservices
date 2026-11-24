// Browser side shared by login.html, rider.html and driver.html: session per role, API calls,
// popups and the map. The gateway enforces the roles on every API call; the guard here only keeps
// each account on its own page.
import {request, validSession} from './client.mjs';
import {pageForRole} from './ride-core.mjs';
import {searchPlaces} from './geo.mjs';

export const $ = id => document.getElementById(id);
export const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'}[c]));

// One session per role, so a passenger tab and a driver tab can run side by side in one browser
const sessionKey = role => `di-session-${role}`;

export function loadSession(role) {
  try {
    const session = JSON.parse(localStorage.getItem(sessionKey(role)) || 'null');
    return validSession(session, role) ? session : null;
  } catch {
    return null;
  }
}

export function saveSession(session) {
  try { localStorage.setItem(sessionKey(session.role), JSON.stringify(session)); } catch { /* private window: session lasts for this page only */ }
}

export function logout(role) {
  try { localStorage.removeItem(sessionKey(role)); } catch { /* nothing stored */ }
  location.href = `login.html?role=${role.toLowerCase()}`;
}

// The signed-in session of the page's role, or a redirect to the login page
export function requireRole(role) {
  const session = loadSession(role);
  if (!session) location.replace(`login.html?role=${role.toLowerCase()}`);
  return session;
}

export function goHome(role) {
  location.href = pageForRole(role) || 'login.html';
}

export async function api(path, options = {}, session = null) {
  try {
    return (await request(path, {...options, ...(session ? {token: session.accessToken} : {})})).data;
  } catch (error) {
    if (error.status === 401 && session) logout(session.role);
    if (error.status === 403) error.message = 'Tài khoản của bạn không có quyền thực hiện thao tác này.';
    if (error.name === 'TimeoutError') error.message = 'Máy chủ phản hồi quá lâu. Vui lòng thử lại.';
    throw error;
  }
}

const TOAST_ICONS = {info: '#i-bell', success: '#i-check', warn: '#i-alert', error: '#i-alert'};

export function toast(title, body = '', kind = 'info') {
  const item = document.createElement('div');
  item.className = `toast ${kind}`;
  item.setAttribute('role', kind === 'error' ? 'alert' : 'status');
  item.innerHTML = `<svg class="icon"><use href="${TOAST_ICONS[kind]}"/></svg><div><strong>${esc(title)}</strong>${body ? `<p>${esc(body)}</p>` : ''}</div><button class="toast-close" aria-label="Đóng thông báo"><svg class="icon"><use href="#i-close"/></svg></button>`;
  const close = () => { item.classList.add('leaving'); setTimeout(() => item.remove(), 200); };
  item.querySelector('button').onclick = close;
  $('toasts').prepend(item);
  setTimeout(close, kind === 'error' ? 8000 : 5500);
}

export function failure(error) {
  toast('Không thực hiện được', error.message, 'error');
}

// shortcut: tiles come from openstreetmap.org over the internet (fine for a demo, not for production traffic);
// host a tile server or use a keyed provider before real use
export const HANOI = [21.0285, 105.8542];

export function makeMap(element, center = HANOI, zoom = 15, interactive = true) {
  const map = L.map(element, {zoomControl: false, dragging: interactive, scrollWheelZoom: interactive, doubleClickZoom: interactive,
    touchZoom: interactive, keyboard: interactive}).setView(center, zoom);
  if (interactive) L.control.zoom({position: 'bottomright'}).addTo(map);
  L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a>',
  }).addTo(map);
  return map;
}

export function pinIcon(kind) {
  if (kind === 'me') return L.divIcon({className: 'pin-wrap', html: '<span class="pin-me"></span>', iconSize: [22, 22], iconAnchor: [11, 11]});
  const html = kind === 'car'
    ? '<span class="pin-car"><svg class="icon"><use href="#i-car"/></svg></span>'
    : `<span class="pin ${kind}"><span></span></span>`;
  return L.divIcon({className: 'pin-wrap', html, iconSize: kind === 'car' ? [34, 34] : [28, 36], iconAnchor: kind === 'car' ? [17, 17] : [14, 34]});
}

// Buttons/results are native keyboard controls; requests happen only on Enter or Tìm.
export function placeSearch(kind, map, onSelect, onEdit = () => {}) {
  const input = $(`${kind}-address`), button = $(`${kind}-search`);
  const results = $(`${kind}-results`), status = $(`${kind}-search-status`);
  let version = 0, controller;
  function clear() {
    version++;
    controller?.abort();
    results.replaceChildren();
    results.hidden = true;
    status.textContent = '';
    button.disabled = false;
  }
  input.addEventListener('input', () => { clear(); onEdit(); });
  input.addEventListener('keydown', event => {
    if (event.key === 'Enter') { event.preventDefault(); search(); }
    if (event.key === 'Escape') clear();
  });
  async function search() {
    clear();
    if (input.value.trim().length < 2) { status.textContent = 'Nhập ít nhất 2 ký tự để tìm.'; return; }
    const asked = version;
    controller = new AbortController();
    button.disabled = true;
    status.textContent = 'Đang tìm địa điểm…';
    try {
      const places = await searchPlaces(input.value, map.getCenter(), {signal: controller.signal});
      if (asked !== version) return;
      status.textContent = places.length ? `${places.length} kết quả. Chọn địa điểm để ghim.` : 'Không tìm thấy. Thử thêm tên đường, quận hoặc thành phố.';
      for (const point of places) {
        const item = document.createElement('li'), choice = document.createElement('button');
        choice.type = 'button'; choice.textContent = point.address;
        choice.onclick = event => {
          event.stopPropagation(); clear(); input.value = point.address;
          onSelect(point); input.focus();
        };
        item.append(choice); results.append(item);
      }
      results.hidden = !places.length;
    } catch (error) {
      if (asked === version) status.textContent = error.name === 'TimeoutError'
        ? 'Tìm địa điểm quá lâu. Hãy thử lại hoặc chọn trên bản đồ.'
        : 'Không thể tìm địa điểm. Hãy thử lại hoặc chọn trên bản đồ.';
    } finally { if (asked === version) button.disabled = false; }
  }
  button.onclick = event => { event.stopPropagation(); search(); };
  return {clear};
}

export function locateMe(button, onPosition, onRequest = () => undefined) {
  button.onclick = async () => {
    if (!globalThis.isSecureContext || !navigator.geolocation) {
      failure(new Error('Lấy vị trí cần HTTPS hoặc localhost và trình duyệt hỗ trợ GPS.')); return;
    }
    button.disabled = true;
    button.textContent = 'Đang lấy vị trí…';
    const context = onRequest();
    try {
      const position = await new Promise((resolve, reject) => navigator.geolocation.getCurrentPosition(resolve, reject,
        {enableHighAccuracy: true, timeout: 12000, maximumAge: 0}));
      await onPosition(position, context);
    } catch (error) {
      failure(new Error(error.code === 1 ? 'Bạn chưa cho phép truy cập vị trí. Hãy cấp quyền hoặc chọn trên bản đồ.'
        : error.code === 2 ? 'Không xác định được vị trí. Hãy bật GPS hoặc chọn trên bản đồ.'
        : error.code === 3 ? 'Lấy vị trí quá lâu. Hãy thử lại hoặc chọn trên bản đồ.' : error.message));
    } finally { button.disabled = false; button.textContent = 'Vị trí của tôi'; }
  };
}

// Center in the exposed map: the sheet sits below on mobile and left on desktop.
export function focusPoint(map, latlng, zoom = map.getZoom()) {
  map.stop();
  map.setView(latlng, zoom, {animate: false});
  const sheet = document.querySelector('.sheet:not([hidden])');
  if (sheet) {
    const mapRect = map.getContainer().getBoundingClientRect(), sheetRect = sheet.getBoundingClientRect();
    const offset = innerWidth <= 760 ? [0, Math.max(0, mapRect.bottom - sheetRect.top) / 2]
      : [-Math.max(0, sheetRect.right - mapRect.left) / 2, 0];
    map.panBy(offset, {animate: false});
  }
}

export function placeLabel(latlng) {
  return `Vị trí đã ghim (${latlng.lat.toFixed(4)}, ${latlng.lng.toFixed(4)})`;
}

// The signed-in person in the top bar, with sign-out
export function renderAccount(session, roleLabel) {
  $('account').innerHTML = `<span class="role-chip">${esc(roleLabel)}</span><span class="account-email">${esc(session.email || '')}</span>`;
  $('logout').onclick = () => logout(session.role);
}
